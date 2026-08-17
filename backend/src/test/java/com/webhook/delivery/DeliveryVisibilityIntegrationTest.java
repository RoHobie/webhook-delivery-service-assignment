package com.webhook.delivery;

import com.webhook.delivery.domain.Delivery;
import com.webhook.delivery.domain.DeliveryAttempt;
import com.webhook.delivery.domain.Endpoint;
import com.webhook.delivery.domain.Tenant;
import com.webhook.delivery.domain.WebhookEvent;
import com.webhook.delivery.dto.DeliveryAttemptResponse;
import com.webhook.delivery.dto.DeliveryResponse;
import com.webhook.delivery.dto.PageResponse;
import com.webhook.delivery.repository.DeliveryAttemptRepository;
import com.webhook.delivery.repository.DeliveryRepository;
import com.webhook.delivery.repository.EndpointRepository;
import com.webhook.delivery.repository.TenantRepository;
import com.webhook.delivery.repository.WebhookEventRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.ParameterizedTypeReference;
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

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.worker.scheduler-enabled=false"
})
@Testcontainers
class DeliveryVisibilityIntegrationTest {

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
    private DeliveryRepository deliveryRepository;

    @Autowired
    private DeliveryAttemptRepository attemptRepository;

    private HttpHeaders headersForTenant(String tenantId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Tenant-Id", tenantId);
        return headers;
    }

    @Test
    @DisplayName("Should list deliveries with status filtering, pagination, and tenant isolation")
    void testListDeliveriesWithFilteringAndPagination() {
        String tenantA = "tenant-vis-a";
        String tenantB = "tenant-vis-b";

        tenantRepository.save(Tenant.builder().id(tenantA).name("Tenant Vis A").createdAt(OffsetDateTime.now()).build());
        tenantRepository.save(Tenant.builder().id(tenantB).name("Tenant Vis B").createdAt(OffsetDateTime.now()).build());

        Endpoint ep1 = endpointRepository.save(Endpoint.builder()
                .id(UUID.randomUUID().toString()).tenantId(tenantA).url("https://a.com/wh").secret("whsec_test123").subscribedEventTypes("*").status("ACTIVE")
                .createdAt(OffsetDateTime.now()).updatedAt(OffsetDateTime.now()).build());

        WebhookEvent evt1 = eventRepository.save(WebhookEvent.builder()
                .id(UUID.randomUUID().toString()).tenantId(tenantA).eventIdExternal("evt_vis_1").type("test").payload("{}")
                .createdAt(OffsetDateTime.now()).build());

        // Create 3 deliveries for tenant A: 2 DELIVERED, 1 FAILED
        Delivery d1 = deliveryRepository.save(Delivery.builder()
                .id(UUID.randomUUID().toString()).eventId(evt1.getId()).endpointId(ep1.getId()).tenantId(tenantA)
                .status("DELIVERED").attemptCount(1).nextAttemptAt(OffsetDateTime.now()).createdAt(OffsetDateTime.now().minusMinutes(3)).updatedAt(OffsetDateTime.now()).build());

        Delivery d2 = deliveryRepository.save(Delivery.builder()
                .id(UUID.randomUUID().toString()).eventId(evt1.getId()).endpointId(ep1.getId()).tenantId(tenantA)
                .status("DELIVERED").attemptCount(1).nextAttemptAt(OffsetDateTime.now()).createdAt(OffsetDateTime.now().minusMinutes(2)).updatedAt(OffsetDateTime.now()).build());

        Delivery d3 = deliveryRepository.save(Delivery.builder()
                .id(UUID.randomUUID().toString()).eventId(evt1.getId()).endpointId(ep1.getId()).tenantId(tenantA)
                .status("FAILED").attemptCount(2).nextAttemptAt(OffsetDateTime.now()).createdAt(OffsetDateTime.now().minusMinutes(1)).updatedAt(OffsetDateTime.now()).build());

        // Create 1 attempt log for d1
        attemptRepository.save(DeliveryAttempt.builder()
                .id(UUID.randomUUID().toString()).deliveryId(d1.getId()).attemptNumber(1).responseCode(200).latencyMs(120L).createdAt(OffsetDateTime.now()).build());

        // Test GET /api/v1/deliveries?status=DELIVERED
        HttpHeaders headersA = headersForTenant(tenantA);
        ResponseEntity<PageResponse<DeliveryResponse>> resDelivered = restTemplate.exchange(
                "/api/v1/deliveries?status=DELIVERED&page=0&size=10",
                HttpMethod.GET,
                new HttpEntity<>(headersA),
                new ParameterizedTypeReference<PageResponse<DeliveryResponse>>() {}
        );

        assertThat(resDelivered.getStatusCode()).isEqualTo(HttpStatus.OK);
        PageResponse<DeliveryResponse> pageDelivered = resDelivered.getBody();
        assertThat(pageDelivered).isNotNull();
        assertThat(pageDelivered.getContent()).hasSize(2);
        assertThat(pageDelivered.getTotalElements()).isEqualTo(2L);
        assertThat(pageDelivered.getContent()).extracting(DeliveryResponse::getId).containsExactlyInAnyOrder(d1.getId(), d2.getId());
        assertThat(pageDelivered.getContent()).extracting(DeliveryResponse::getId).doesNotContain(d3.getId());

        // Test GET /api/v1/deliveries/{id}/attempts for d1
        ResponseEntity<List<DeliveryAttemptResponse>> resAttempts = restTemplate.exchange(
                "/api/v1/deliveries/" + d1.getId() + "/attempts",
                HttpMethod.GET,
                new HttpEntity<>(headersA),
                new ParameterizedTypeReference<List<DeliveryAttemptResponse>>() {}
        );

        assertThat(resAttempts.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<DeliveryAttemptResponse> attempts = resAttempts.getBody();
        assertThat(attempts).hasSize(1);
        assertThat(attempts.get(0).getResponseCode()).isEqualTo(200);

        // Test Tenant B attempting to view Tenant A's delivery attempts -> 404 Not Found
        HttpHeaders headersB = headersForTenant(tenantB);
        ResponseEntity<String> resAttemptsB = restTemplate.exchange(
                "/api/v1/deliveries/" + d1.getId() + "/attempts",
                HttpMethod.GET,
                new HttpEntity<>(headersB),
                String.class
        );

        assertThat(resAttemptsB.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
