package com.webhook.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.webhook.delivery.domain.Tenant;
import com.webhook.delivery.dto.IngestEventRequest;
import com.webhook.delivery.repository.TenantRepository;
import com.webhook.delivery.service.TenantRateLimiterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.test.client.TestRestTemplate;
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

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.tenant-validation.enforce-db-check=true",
        "app.rate-limit.capacity=2",
        "app.rate-limit.events-per-second=0.01",
        "app.worker.scheduler-enabled=false"
})
@Testcontainers
class TenantHardeningIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private TenantRateLimiterService rateLimiterService;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        rateLimiterService.clear();
    }

    private HttpHeaders headersForTenant(String tenantId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (tenantId != null) {
            headers.set("X-Tenant-Id", tenantId);
        }
        return headers;
    }

    @Test
    @DisplayName("Should return 400 Bad Request when X-Tenant-Id header is missing")
    void testMissingTenantHeader() {
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/events",
                new HttpEntity<>(createSampleEventRequest("evt_h_1"), new HttpHeaders()),
                String.class
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("Missing or empty X-Tenant-Id header");
    }

    @Test
    @DisplayName("Should return 401 Unauthorized for non-existent tenant when DB check is enforced")
    void testInvalidTenantReturns401() {
        HttpHeaders headers = headersForTenant("non-existent-tenant-999");
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/events",
                new HttpEntity<>(createSampleEventRequest("evt_h_2"), headers),
                String.class
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).contains("Unauthorized: Tenant ID does not exist");
    }

    @Test
    @DisplayName("Should return 429 Too Many Requests when tenant event ingestion capacity is exceeded")
    void testTenantRateLimiting() {
        String validTenant = "tenant-rate-limited";
        tenantRepository.save(Tenant.builder().id(validTenant).name("Rate Limited Tenant").createdAt(OffsetDateTime.now()).build());
        HttpHeaders headers = headersForTenant(validTenant);

        // 1st request -> 202 Accepted
        ResponseEntity<String> res1 = restTemplate.postForEntity("/api/v1/events", new HttpEntity<>(createSampleEventRequest("evt_rl_1"), headers), String.class);
        assertThat(res1.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        // 2nd request -> 202 Accepted (capacity = 2)
        ResponseEntity<String> res2 = restTemplate.postForEntity("/api/v1/events", new HttpEntity<>(createSampleEventRequest("evt_rl_2"), headers), String.class);
        assertThat(res2.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        // 3rd request -> 429 Too Many Requests
        ResponseEntity<String> res3 = restTemplate.postForEntity("/api/v1/events", new HttpEntity<>(createSampleEventRequest("evt_rl_3"), headers), String.class);
        assertThat(res3.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(res3.getHeaders().getFirst("Retry-After")).isEqualTo("1");
        assertThat(res3.getBody()).contains("Rate limit exceeded for tenant");
    }

    private IngestEventRequest createSampleEventRequest(String eventId) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("key", "val");
        return IngestEventRequest.builder()
                .eventId(eventId)
                .type("order.created")
                .payload(payload)
                .build();
    }
}
