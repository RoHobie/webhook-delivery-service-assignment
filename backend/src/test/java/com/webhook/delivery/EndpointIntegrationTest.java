package com.webhook.delivery;

import com.webhook.delivery.dto.CreateEndpointRequest;
import com.webhook.delivery.dto.EndpointResponse;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.allow-internal-urls=false"
})
@Testcontainers
class EndpointIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @Autowired
    private TestRestTemplate restTemplate;

    private HttpHeaders headersForTenant(String tenantId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Tenant-Id", tenantId);
        return headers;
    }

    @Test
    @DisplayName("Should register endpoint and return secret on creation only")
    void createAndGetEndpoint() {
        HttpHeaders headers = headersForTenant("tenant-a");
        CreateEndpointRequest request = CreateEndpointRequest.builder()
                .url("https://example.com/webhook")
                .subscribedEventTypes(List.of("order.created", "invoice.paid"))
                .build();

        ResponseEntity<EndpointResponse> createRes = restTemplate.postForEntity(
                "/api/v1/endpoints",
                new HttpEntity<>(request, headers),
                EndpointResponse.class
        );

        assertThat(createRes.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        EndpointResponse created = createRes.getBody();
        assertThat(created).isNotNull();
        assertThat(created.getId()).isNotNull();
        assertThat(created.getTenantId()).isEqualTo("tenant-a");
        assertThat(created.getUrl()).isEqualTo("https://example.com/webhook");
        assertThat(created.getSecret()).startsWith("whsec_");
        assertThat(created.getSubscribedEventTypes()).containsExactly("order.created", "invoice.paid");
        assertThat(created.getStatus()).isEqualTo("ACTIVE");

        // GET endpoint by ID should hide secret
        ResponseEntity<EndpointResponse> getRes = restTemplate.exchange(
                "/api/v1/endpoints/" + created.getId(),
                HttpMethod.GET,
                new HttpEntity<>(headers),
                EndpointResponse.class
        );

        assertThat(getRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        EndpointResponse fetched = getRes.getBody();
        assertThat(fetched).isNotNull();
        assertThat(fetched.getSecret()).isNull(); // Secret hidden on GET
    }

    @Test
    @DisplayName("Should enforce tenant isolation: tenant-b cannot access tenant-a endpoint")
    void tenantIsolationOnEndpoint() {
        HttpHeaders headersA = headersForTenant("tenant-isolation-a");
        CreateEndpointRequest request = CreateEndpointRequest.builder()
                .url("https://example.com/tenant-a-webhook")
                .subscribedEventTypes(List.of("*"))
                .build();

        ResponseEntity<EndpointResponse> createRes = restTemplate.postForEntity(
                "/api/v1/endpoints",
                new HttpEntity<>(request, headersA),
                EndpointResponse.class
        );
        String endpointId = createRes.getBody().getId();

        // Attempt fetch with tenant-b credentials
        HttpHeaders headersB = headersForTenant("tenant-isolation-b");
        ResponseEntity<String> getResB = restTemplate.exchange(
                "/api/v1/endpoints/" + endpointId,
                HttpMethod.GET,
                new HttpEntity<>(headersB),
                String.class
        );

        assertThat(getResB.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Should reject invalid or internal URLs when allow-internal-urls is false")
    void rejectInternalUrl() {
        HttpHeaders headers = headersForTenant("tenant-url-test");
        CreateEndpointRequest internalRequest = CreateEndpointRequest.builder()
                .url("http://127.0.0.1:8080/hook")
                .subscribedEventTypes(List.of("test"))
                .build();

        ResponseEntity<String> res = restTemplate.postForEntity(
                "/api/v1/endpoints",
                new HttpEntity<>(internalRequest, headers),
                String.class
        );

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).contains("Internal / local IP addresses and hostnames are forbidden");
    }

    @Test
    @DisplayName("Should reject requests missing X-Tenant-Id header")
    void missingTenantHeader() {
        CreateEndpointRequest request = CreateEndpointRequest.builder()
                .url("https://example.com/webhook")
                .subscribedEventTypes(List.of("test"))
                .build();

        ResponseEntity<String> res = restTemplate.postForEntity(
                "/api/v1/endpoints",
                new HttpEntity<>(request, new HttpHeaders()),
                String.class
        );

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).contains("Missing or empty X-Tenant-Id header");
    }
}
