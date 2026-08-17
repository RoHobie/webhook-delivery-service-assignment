package com.webhook.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.webhook.delivery.dto.CreateEndpointRequest;
import com.webhook.delivery.dto.EndpointResponse;
import com.webhook.delivery.dto.IngestEventRequest;
import com.webhook.delivery.dto.IngestEventResponse;
import com.webhook.delivery.repository.DeliveryRepository;
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

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.allow-internal-urls=true"
})
@Testcontainers
class EventIngestionIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private DeliveryRepository deliveryRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private HttpHeaders headersForTenant(String tenantId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Tenant-Id", tenantId);
        return headers;
    }

    @Test
    @DisplayName("Should ingest event, fan out PENDING deliveries, and maintain idempotency on duplicate eventId")
    void eventIngestionAndIdempotency() {
        String tenantId = "tenant-ingest-test";
        HttpHeaders headers = headersForTenant(tenantId);

        // Register 2 endpoints: 1 matching "payment.success", 1 subscribing to "user.created"
        CreateEndpointRequest ep1Req = CreateEndpointRequest.builder()
                .url("http://localhost:9999/wh1")
                .subscribedEventTypes(List.of("payment.success"))
                .build();
        CreateEndpointRequest ep2Req = CreateEndpointRequest.builder()
                .url("http://localhost:9999/wh2")
                .subscribedEventTypes(List.of("user.created"))
                .build();

        restTemplate.postForEntity("/api/v1/endpoints", new HttpEntity<>(ep1Req, headers), EndpointResponse.class);
        restTemplate.postForEntity("/api/v1/endpoints", new HttpEntity<>(ep2Req, headers), EndpointResponse.class);

        // Ingest event for payment.success
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("amount", 100);
        payload.put("currency", "USD");

        IngestEventRequest ingestReq = IngestEventRequest.builder()
                .eventId("evt_unique_1001")
                .type("payment.success")
                .payload(payload)
                .build();

        ResponseEntity<IngestEventResponse> response1 = restTemplate.postForEntity(
                "/api/v1/events",
                new HttpEntity<>(ingestReq, headers),
                IngestEventResponse.class
        );

        assertThat(response1.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        IngestEventResponse body1 = response1.getBody();
        assertThat(body1).isNotNull();
        assertThat(body1.getEventId()).isEqualTo("evt_unique_1001");
        assertThat(body1.getDeliveriesCreated()).isEqualTo(1); // Only ep1 matched

        long deliveriesInDb = deliveryRepository.countByTenantIdAndEventId(tenantId, body1.getId());
        assertThat(deliveriesInDb).isEqualTo(1);

        // Submit DUPLICATE eventId
        ResponseEntity<IngestEventResponse> response2 = restTemplate.postForEntity(
                "/api/v1/events",
                new HttpEntity<>(ingestReq, headers),
                IngestEventResponse.class
        );

        assertThat(response2.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        IngestEventResponse body2 = response2.getBody();
        assertThat(body2).isNotNull();
        assertThat(body2.getId()).isEqualTo(body1.getId()); // Same internal event ID returned
        assertThat(body2.getDeliveriesCreated()).isEqualTo(1);

        // Verify total deliveries in DB did NOT duplicate
        long deliveriesInDbAfterDuplicate = deliveryRepository.countByTenantIdAndEventId(tenantId, body1.getId());
        assertThat(deliveriesInDbAfterDuplicate).isEqualTo(1);
    }
}
