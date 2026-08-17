package com.webhook.delivery.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Component
public class OutboundHttpClient {

    private final HttpClient httpClient;
    private final Duration requestTimeout;

    public OutboundHttpClient(
            @Value("${app.http.connect-timeout-seconds:5}") int connectTimeoutSeconds,
            @Value("${app.http.request-timeout-seconds:10}") int requestTimeoutSeconds) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.requestTimeout = Duration.ofSeconds(requestTimeoutSeconds);
    }

    public HttpResponseResult sendWebhook(
            String targetUrl,
            String payloadJson,
            String eventType,
            String deliveryId,
            SignatureService.SignedHeaders signedHeaders) {

        long startTime = System.currentTimeMillis();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(targetUrl))
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .header("X-Webhook-Signature", signedHeaders.signature())
                    .header("X-Webhook-Timestamp", signedHeaders.timestamp())
                    .header("X-Webhook-Event-Type", eventType)
                    .header("X-Webhook-Delivery-Id", deliveryId)
                    .header("User-Agent", "WebhookDeliveryService/1.0")
                    .POST(HttpRequest.BodyPublishers.ofString(payloadJson))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long latencyMs = System.currentTimeMillis() - startTime;

            String snippet = truncateSnippet(response.body(), 1024);
            int statusCode = response.statusCode();
            boolean success = statusCode >= 200 && statusCode < 300;

            return new HttpResponseResult(statusCode, snippet, latencyMs, success ? null : "HTTP " + statusCode, success);

        } catch (Exception e) {
            long latencyMs = System.currentTimeMillis() - startTime;
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return new HttpResponseResult(null, null, latencyMs, errorMsg, false);
        }
    }

    private String truncateSnippet(String text, int maxLength) {
        if (text == null) return null;
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "...[truncated]";
    }

    public record HttpResponseResult(
            Integer statusCode,
            String responseSnippet,
            long latencyMs,
            String error,
            boolean success
    ) {}
}
