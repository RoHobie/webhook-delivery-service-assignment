package com.webhook.delivery;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.webhook.delivery.domain.Delivery;
import com.webhook.delivery.domain.Endpoint;
import com.webhook.delivery.domain.Tenant;
import com.webhook.delivery.domain.WebhookEvent;
import com.webhook.delivery.repository.DeliveryRepository;
import com.webhook.delivery.repository.EndpointRepository;
import com.webhook.delivery.repository.TenantRepository;
import com.webhook.delivery.repository.WebhookEventRepository;
import com.webhook.delivery.service.CrashRecoveryService;
import com.webhook.delivery.service.DeliveryClaimService;
import com.webhook.delivery.service.DeliveryDispatchService;
import com.webhook.delivery.service.EndpointCircuitBreakerService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.allow-internal-urls=true",
        "app.worker.scheduler-enabled=false",
        "app.circuit-breaker.failure-threshold=2",
        "app.circuit-breaker.cooldown-seconds=60"
})
@Testcontainers
class CircuitBreakerAndRecoveryIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private EndpointRepository endpointRepository;

    @Autowired
    private WebhookEventRepository eventRepository;

    @Autowired
    private DeliveryRepository deliveryRepository;

    @Autowired
    private DeliveryDispatchService dispatchService;

    @Autowired
    private DeliveryClaimService claimService;

    @Autowired
    private CrashRecoveryService crashRecoveryService;

    @Autowired
    private EndpointCircuitBreakerService circuitBreakerService;

    private WireMockServer wireMockServer;

    @BeforeEach
    void setUp() {
        circuitBreakerService.clearAll();
        wireMockServer = new WireMockServer(wireMockConfig().dynamicPort());
        wireMockServer.start();
    }

    @AfterEach
    void tearDown() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    @Test
    @DisplayName("Should open circuit breaker after 2 consecutive failures and defer further requests")
    void testCircuitBreakerOpens() {
        String tenantId = "tenant-cb-1";
        tenantRepository.save(Tenant.builder().id(tenantId).name("CB Tenant").createdAt(OffsetDateTime.now()).build());

        wireMockServer.stubFor(post(urlEqualTo("/cb-failing"))
                .willReturn(aResponse().withStatus(503).withBody("Service Unavailable")));

        String targetUrl = "http://localhost:" + wireMockServer.port() + "/cb-failing";
        Endpoint ep = endpointRepository.save(Endpoint.builder()
                .id(UUID.randomUUID().toString()).tenantId(tenantId).url(targetUrl).secret("whsec_testcb").subscribedEventTypes("*").status("ACTIVE")
                .createdAt(OffsetDateTime.now()).updatedAt(OffsetDateTime.now()).build());

        WebhookEvent evt = eventRepository.save(WebhookEvent.builder()
                .id(UUID.randomUUID().toString()).tenantId(tenantId).eventIdExternal("evt_cb_1").type("test").payload("{}")
                .createdAt(OffsetDateTime.now()).build());

        Delivery d1 = deliveryRepository.save(Delivery.builder()
                .id(UUID.randomUUID().toString()).eventId(evt.getId()).endpointId(ep.getId()).tenantId(tenantId)
                .status("PENDING").attemptCount(0).nextAttemptAt(OffsetDateTime.now()).createdAt(OffsetDateTime.now()).updatedAt(OffsetDateTime.now()).build());

        Delivery d2 = deliveryRepository.save(Delivery.builder()
                .id(UUID.randomUUID().toString()).eventId(evt.getId()).endpointId(ep.getId()).tenantId(tenantId)
                .status("PENDING").attemptCount(0).nextAttemptAt(OffsetDateTime.now()).createdAt(OffsetDateTime.now()).updatedAt(OffsetDateTime.now()).build());

        Delivery d3 = deliveryRepository.save(Delivery.builder()
                .id(UUID.randomUUID().toString()).eventId(evt.getId()).endpointId(ep.getId()).tenantId(tenantId)
                .status("PENDING").attemptCount(0).nextAttemptAt(OffsetDateTime.now()).createdAt(OffsetDateTime.now()).updatedAt(OffsetDateTime.now()).build());

        // Process d1 -> Failure 1
        dispatchService.processDelivery(d1.getId());
        assertThat(circuitBreakerService.getState(ep.getId())).isEqualTo(EndpointCircuitBreakerService.State.CLOSED);

        // Process d2 -> Failure 2 (Threshold = 2) -> Circuit opens!
        dispatchService.processDelivery(d2.getId());
        assertThat(circuitBreakerService.getState(ep.getId())).isEqualTo(EndpointCircuitBreakerService.State.OPEN);

        // Process d3 -> Skipped due to OPEN circuit breaker
        dispatchService.processDelivery(d3.getId());

        // Verify WireMock received ONLY 2 requests (d3 was blocked by Circuit Breaker)
        wireMockServer.verify(2, postRequestedFor(urlEqualTo("/cb-failing")));
    }

    @Test
    @DisplayName("Should release stale locks from crashed workers and allow reclaiming")
    void testCrashRecoveryStaleLockRelease() {
        String tenantId = "tenant-crash-1";
        tenantRepository.save(Tenant.builder().id(tenantId).name("Crash Tenant").createdAt(OffsetDateTime.now()).build());

        Endpoint ep = endpointRepository.save(Endpoint.builder()
                .id(UUID.randomUUID().toString()).tenantId(tenantId).url("https://example.com/hook").secret("whsec_crash").subscribedEventTypes("*").status("ACTIVE")
                .createdAt(OffsetDateTime.now()).updatedAt(OffsetDateTime.now()).build());

        WebhookEvent evt = eventRepository.save(WebhookEvent.builder()
                .id(UUID.randomUUID().toString()).tenantId(tenantId).eventIdExternal("evt_crash_1").type("test").payload("{}")
                .createdAt(OffsetDateTime.now()).build());

        // Delivery locked by dead worker 10 minutes ago
        Delivery dCrashed = deliveryRepository.save(Delivery.builder()
                .id(UUID.randomUUID().toString()).eventId(evt.getId()).endpointId(ep.getId()).tenantId(tenantId)
                .status("PENDING").attemptCount(0).nextAttemptAt(OffsetDateTime.now().minusMinutes(10))
                .lockedBy("crashed-worker-99").lockedUntil(OffsetDateTime.now().minusMinutes(5))
                .createdAt(OffsetDateTime.now().minusMinutes(10)).updatedAt(OffsetDateTime.now().minusMinutes(10)).build());

        int released = crashRecoveryService.releaseStaleLocks();
        assertThat(released).isGreaterThanOrEqualTo(1);

        Delivery recovered = deliveryRepository.findById(dCrashed.getId()).orElseThrow();
        assertThat(recovered.getLockedBy()).isNull();
        assertThat(recovered.getLockedUntil()).isNull();

        // Active worker should now successfully claim the recovered delivery
        List<Delivery> claimed = claimService.claimDeliveries("active-worker-1", 10);
        assertThat(claimed).extracting(Delivery::getId).contains(dCrashed.getId());
    }
}
