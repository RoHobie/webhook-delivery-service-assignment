package com.webhook.delivery;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.webhook.delivery.domain.Endpoint;
import com.webhook.delivery.domain.Tenant;
import com.webhook.delivery.domain.WebhookEvent;
import com.webhook.delivery.dto.CreateEndpointRequest;
import com.webhook.delivery.dto.EndpointResponse;
import com.webhook.delivery.repository.EndpointRepository;
import com.webhook.delivery.repository.TenantRepository;
import com.webhook.delivery.repository.WebhookEventRepository;
import com.webhook.delivery.service.DeliveryClaimService;
import com.webhook.delivery.service.DeliveryDispatchService;
import com.webhook.delivery.service.MetricsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.allow-internal-urls=true",
        "app.worker.scheduler-enabled=false"
})
@Testcontainers
class ObservabilityIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private EndpointRepository endpointRepository;

    @Autowired
    private WebhookEventRepository eventRepository;

    @Autowired
    private MetricsService metricsService;

    private WireMockServer wireMockServer;

    @BeforeEach
    void setUp() {
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
    @DisplayName("Should return 200 OK for actuator health endpoint")
    void testActuatorHealth() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    @DisplayName("Should expose custom webhook metrics on /actuator/prometheus endpoint")
    void testPrometheusMetricsExposed() {
        String tenantId = "tenant-obs-1";
        tenantRepository.save(Tenant.builder().id(tenantId).name("Observability Tenant").createdAt(OffsetDateTime.now()).build());

        metricsService.recordEventIngested(tenantId, "order.created");
        metricsService.recordDeliveryDispatched(tenantId, "DELIVERED");
        metricsService.recordDeliveryLatency(tenantId, 200, 150L);

        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/prometheus", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = response.getBody();
        assertThat(body).contains("webhook_events_ingested_total");
        assertThat(body).contains("webhook_deliveries_dispatched_total");
        assertThat(body).contains("webhook_delivery_latency_seconds");
    }
}
