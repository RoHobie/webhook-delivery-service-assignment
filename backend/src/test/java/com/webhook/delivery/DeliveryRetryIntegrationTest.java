package com.webhook.delivery;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.webhook.delivery.domain.Delivery;
import com.webhook.delivery.dto.CreateEndpointRequest;
import com.webhook.delivery.dto.DeliveryResponse;
import com.webhook.delivery.dto.EndpointResponse;
import com.webhook.delivery.dto.IngestEventRequest;
import com.webhook.delivery.dto.IngestEventResponse;
import com.webhook.delivery.repository.DeliveryRepository;
import com.webhook.delivery.service.DeliveryClaimService;
import com.webhook.delivery.service.DeliveryDispatchService;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.allow-internal-urls=true",
        "app.worker.scheduler-enabled=false",
        "app.worker.max-attempts=3",
        "app.worker.base-delay-seconds=1"
})
@Testcontainers
class DeliveryRetryIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private DeliveryClaimService claimService;

    @Autowired
    private DeliveryDispatchService dispatchService;

    @Autowired
    private DeliveryRepository deliveryRepository;

    @Autowired
    private ObjectMapper objectMapper;

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

    private HttpHeaders headersForTenant(String tenantId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Tenant-Id", tenantId);
        return headers;
    }

    @Test
    @DisplayName("Delivery should transition to DEAD_LETTERED after max attempts and allow manual redrive")
    void testDeadLetteringAndManualRedrive() {
        String tenantId = "tenant-retry-test";
        HttpHeaders headersA = headersForTenant(tenantId);

        // Stub endpoint returning HTTP 500 error
        wireMockServer.stubFor(post(urlEqualTo("/failing-receiver"))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withBody("Internal Server Error")));

        String targetUrl = "http://localhost:" + wireMockServer.port() + "/failing-receiver";

        CreateEndpointRequest epReq = CreateEndpointRequest.builder()
                .url(targetUrl)
                .subscribedEventTypes(List.of("order.failed"))
                .build();
        restTemplate.postForEntity("/api/v1/endpoints", new HttpEntity<>(epReq, headersA), EndpointResponse.class);

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("orderId", "ord_555");

        IngestEventRequest ingestReq = IngestEventRequest.builder()
                .eventId("evt_fail_555")
                .type("order.failed")
                .payload(payload)
                .build();
        restTemplate.postForEntity("/api/v1/events", new HttpEntity<>(ingestReq, headersA), IngestEventResponse.class);

        List<Delivery> claimed = claimService.claimDeliveries("retry-worker", 10);
        assertThat(claimed).hasSize(1);
        String deliveryId = claimed.get(0).getId();

        // Attempt 1 -> Fails, scheduled for retry (PENDING)
        dispatchService.processDelivery(deliveryId);
        Delivery state1 = deliveryRepository.findById(deliveryId).orElseThrow();
        assertThat(state1.getStatus()).isEqualTo("PENDING");
        assertThat(state1.getAttemptCount()).isEqualTo(1);

        // Attempt 2 -> Fails, scheduled for retry (PENDING)
        dispatchService.processDelivery(deliveryId);
        Delivery state2 = deliveryRepository.findById(deliveryId).orElseThrow();
        assertThat(state2.getStatus()).isEqualTo("PENDING");
        assertThat(state2.getAttemptCount()).isEqualTo(2);

        // Attempt 3 (max-attempts = 3) -> Fails, transitions to DEAD_LETTERED
        dispatchService.processDelivery(deliveryId);
        Delivery state3 = deliveryRepository.findById(deliveryId).orElseThrow();
        assertThat(state3.getStatus()).isEqualTo("DEAD_LETTERED");
        assertThat(state3.getAttemptCount()).isEqualTo(3);

        // Attempt redrive by another tenant -> 404 Not Found
        HttpHeaders headersB = headersForTenant("tenant-other");
        ResponseEntity<String> redriveBRes = restTemplate.exchange(
                "/api/v1/deliveries/" + deliveryId + "/redrive",
                HttpMethod.POST,
                new HttpEntity<>(headersB),
                String.class
        );
        assertThat(redriveBRes.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // Manual redrive by correct tenant A -> 200 OK, transitions to PENDING
        ResponseEntity<DeliveryResponse> redriveARes = restTemplate.exchange(
                "/api/v1/deliveries/" + deliveryId + "/redrive",
                HttpMethod.POST,
                new HttpEntity<>(headersA),
                DeliveryResponse.class
        );
        assertThat(redriveARes.getStatusCode()).isEqualTo(HttpStatus.OK);
        DeliveryResponse redriven = redriveARes.getBody();
        assertThat(redriven).isNotNull();
        assertThat(redriven.getStatus()).isEqualTo("PENDING");

        // Attempt redrive on PENDING delivery -> 400 Bad Request
        ResponseEntity<String> invalidRedriveRes = restTemplate.exchange(
                "/api/v1/deliveries/" + deliveryId + "/redrive",
                HttpMethod.POST,
                new HttpEntity<>(headersA),
                String.class
        );
        assertThat(invalidRedriveRes.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
