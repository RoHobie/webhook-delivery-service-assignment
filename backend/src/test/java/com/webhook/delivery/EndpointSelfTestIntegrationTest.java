package com.webhook.delivery;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.webhook.delivery.dto.CreateEndpointRequest;
import com.webhook.delivery.dto.EndpointResponse;
import com.webhook.delivery.dto.EndpointTestResultResponse;
import com.webhook.delivery.repository.EndpointRepository;
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
class EndpointSelfTestIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private EndpointRepository endpointRepository;

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
    @DisplayName("Should send synchronous ping request and return signed test result")
    void testSuccessfulEndpointPing() {
        String tenantId = "tenant-ping-1";
        HttpHeaders headers = headersForTenant(tenantId);

        wireMockServer.stubFor(post(urlEqualTo("/ping-receiver"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"pong\"}")));

        String targetUrl = "http://localhost:" + wireMockServer.port() + "/ping-receiver";

        CreateEndpointRequest epReq = CreateEndpointRequest.builder()
                .url(targetUrl)
                .subscribedEventTypes(List.of("ping"))
                .build();
        ResponseEntity<EndpointResponse> epRes = restTemplate.postForEntity("/api/v1/endpoints", new HttpEntity<>(epReq, headers), EndpointResponse.class);
        String endpointId = epRes.getBody().getId();

        // Trigger test ping
        ResponseEntity<EndpointTestResultResponse> testRes = restTemplate.postForEntity(
                "/api/v1/endpoints/" + endpointId + "/test",
                new HttpEntity<>(headers),
                EndpointTestResultResponse.class
        );

        assertThat(testRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        EndpointTestResultResponse result = testRes.getBody();
        assertThat(result).isNotNull();
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getStatusCode()).isEqualTo(200);
        assertThat(result.getResponseSnippet()).contains("pong");

        // Verify WireMock received signed ping POST request
        wireMockServer.verify(postRequestedFor(urlEqualTo("/ping-receiver"))
                .withHeader("X-Webhook-Event-Type", equalTo("ping"))
                .withHeader("X-Webhook-Signature", matching("t=.*,v1=.*"))
                .withHeader("X-Webhook-Timestamp", matching("\\d+")));
    }

    @Test
    @DisplayName("Should handle failing ping test and reject test on disabled endpoint or cross-tenant")
    void testFailingAndDisabledEndpointPing() {
        String tenantA = "tenant-ping-a";
        String tenantB = "tenant-ping-b";
        HttpHeaders headersA = headersForTenant(tenantA);
        HttpHeaders headersB = headersForTenant(tenantB);

        wireMockServer.stubFor(post(urlEqualTo("/fail-ping"))
                .willReturn(aResponse().withStatus(500).withBody("Server Error")));

        String targetUrl = "http://localhost:" + wireMockServer.port() + "/fail-ping";

        CreateEndpointRequest epReq = CreateEndpointRequest.builder()
                .url(targetUrl)
                .subscribedEventTypes(List.of("*"))
                .build();
        ResponseEntity<EndpointResponse> epRes = restTemplate.postForEntity("/api/v1/endpoints", new HttpEntity<>(epReq, headersA), EndpointResponse.class);
        String endpointId = epRes.getBody().getId();

        // Ping failing endpoint -> 200 OK wrapper containing success: false & statusCode: 500
        ResponseEntity<EndpointTestResultResponse> failTestRes = restTemplate.postForEntity(
                "/api/v1/endpoints/" + endpointId + "/test",
                new HttpEntity<>(headersA),
                EndpointTestResultResponse.class
        );
        assertThat(failTestRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(failTestRes.getBody().isSuccess()).isFalse();
        assertThat(failTestRes.getBody().getStatusCode()).isEqualTo(500);

        // Disable endpoint
        restTemplate.exchange("/api/v1/endpoints/" + endpointId, HttpMethod.DELETE, new HttpEntity<>(headersA), EndpointResponse.class);

        // Ping disabled endpoint -> 400 Bad Request
        ResponseEntity<String> disabledTestRes = restTemplate.postForEntity(
                "/api/v1/endpoints/" + endpointId + "/test",
                new HttpEntity<>(headersA),
                String.class
        );
        assertThat(disabledTestRes.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // Tenant B attempting ping on Tenant A's endpoint -> 404 Not Found
        ResponseEntity<String> crossTenantRes = restTemplate.postForEntity(
                "/api/v1/endpoints/" + endpointId + "/test",
                new HttpEntity<>(headersB),
                String.class
        );
        assertThat(crossTenantRes.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
