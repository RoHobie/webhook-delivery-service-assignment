package com.webhook.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.webhook.delivery.domain.Delivery;
import com.webhook.delivery.domain.DeliveryAttempt;
import com.webhook.delivery.dto.CreateEndpointRequest;
import com.webhook.delivery.dto.EndpointResponse;
import com.webhook.delivery.dto.IngestEventRequest;
import com.webhook.delivery.dto.IngestEventResponse;
import com.webhook.delivery.repository.DeliveryAttemptRepository;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.allow-internal-urls=true",
        "app.worker.scheduler-enabled=false"
})
@Testcontainers
class DeliveryWorkerIntegrationTest {

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
    private DeliveryAttemptRepository attemptRepository;

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
    @DisplayName("Should claim pending delivery, sign payload, send to WireMock, and record DELIVERED status")
    void testSuccessfulDeliveryWorkerFlow() {
        String tenantId = "tenant-worker-test";
        HttpHeaders headers = headersForTenant(tenantId);

        wireMockServer.stubFor(post(urlEqualTo("/webhook-receiver"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"ok\"}")));

        String targetUrl = "http://localhost:" + wireMockServer.port() + "/webhook-receiver";

        CreateEndpointRequest epReq = CreateEndpointRequest.builder()
                .url(targetUrl)
                .subscribedEventTypes(List.of("invoice.paid"))
                .build();
        restTemplate.postForEntity("/api/v1/endpoints", new HttpEntity<>(epReq, headers), EndpointResponse.class);

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("invoiceId", "inv_9988");
        payload.put("amount", 2500);

        IngestEventRequest ingestReq = IngestEventRequest.builder()
                .eventId("evt_inv_9988")
                .type("invoice.paid")
                .payload(payload)
                .build();

        ResponseEntity<IngestEventResponse> ingestRes = restTemplate.postForEntity(
                "/api/v1/events",
                new HttpEntity<>(ingestReq, headers),
                IngestEventResponse.class
        );
        assertThat(ingestRes.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        List<Delivery> claimed = claimService.claimDeliveries("test-worker-1", 10);
        assertThat(claimed).hasSize(1);
        Delivery delivery = claimed.get(0);

        dispatchService.processDelivery(delivery.getId());

        Delivery updated = deliveryRepository.findById(delivery.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo("DELIVERED");
        assertThat(updated.getLastResponseCode()).isEqualTo(200);
        assertThat(updated.getLastResponseSnippet()).contains("{\"status\":\"ok\"}");

        List<DeliveryAttempt> attempts = attemptRepository.findAllByDeliveryIdOrderByAttemptNumberAsc(delivery.getId());
        assertThat(attempts).hasSize(1);
        assertThat(attempts.get(0).getResponseCode()).isEqualTo(200);
        assertThat(attempts.get(0).getLatencyMs()).isGreaterThanOrEqualTo(0);

        wireMockServer.verify(postRequestedFor(urlEqualTo("/webhook-receiver"))
                .withHeader("X-Webhook-Signature", matching("^t=\\d+,v1=[a-f0-9]+$"))
                .withHeader("X-Webhook-Timestamp", matching("^\\d+$"))
                .withHeader("X-Webhook-Event-Type", equalTo("invoice.paid"))
                .withHeader("X-Webhook-Delivery-Id", equalTo(delivery.getId())));
    }
}
