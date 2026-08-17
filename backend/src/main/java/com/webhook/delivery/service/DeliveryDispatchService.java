package com.webhook.delivery.service;

import com.webhook.delivery.domain.Delivery;
import com.webhook.delivery.domain.DeliveryAttempt;
import com.webhook.delivery.domain.Endpoint;
import com.webhook.delivery.domain.WebhookEvent;
import com.webhook.delivery.repository.DeliveryAttemptRepository;
import com.webhook.delivery.repository.DeliveryRepository;
import com.webhook.delivery.repository.EndpointRepository;
import com.webhook.delivery.repository.WebhookEventRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

@Service
public class DeliveryDispatchService {

    private static final Logger log = LoggerFactory.getLogger(DeliveryDispatchService.class);

    private final WebhookEventRepository eventRepository;
    private final EndpointRepository endpointRepository;
    private final DeliveryRepository deliveryRepository;
    private final DeliveryAttemptRepository attemptRepository;
    private final SignatureService signatureService;
    private final OutboundHttpClient outboundHttpClient;
    private final BackoffCalculator backoffCalculator;
    private final EndpointCircuitBreakerService circuitBreakerService;
    private final MetricsService metricsService;

    public DeliveryDispatchService(
            WebhookEventRepository eventRepository,
            EndpointRepository endpointRepository,
            DeliveryRepository deliveryRepository,
            DeliveryAttemptRepository attemptRepository,
            SignatureService signatureService,
            OutboundHttpClient outboundHttpClient,
            BackoffCalculator backoffCalculator,
            EndpointCircuitBreakerService circuitBreakerService,
            MetricsService metricsService) {
        this.eventRepository = eventRepository;
        this.endpointRepository = endpointRepository;
        this.deliveryRepository = deliveryRepository;
        this.attemptRepository = attemptRepository;
        this.signatureService = signatureService;
        this.outboundHttpClient = outboundHttpClient;
        this.backoffCalculator = backoffCalculator;
        this.circuitBreakerService = circuitBreakerService;
        this.metricsService = metricsService;
    }

    @Transactional
    public void processDelivery(String deliveryId) {
        Delivery delivery = deliveryRepository.findById(deliveryId).orElse(null);
        if (delivery == null) {
            return;
        }

        WebhookEvent event = eventRepository.findById(delivery.getEventId()).orElse(null);
        Endpoint endpoint = endpointRepository.findById(delivery.getEndpointId()).orElse(null);

        if (event == null || endpoint == null || "DISABLED".equalsIgnoreCase(endpoint.getStatus())) {
            log.warn("Delivery {} aborted: event or endpoint missing/disabled", deliveryId);
            delivery.setStatus("DEAD_LETTERED");
            delivery.setLockedBy(null);
            delivery.setLockedUntil(null);
            delivery.setUpdatedAt(OffsetDateTime.now());
            deliveryRepository.save(delivery);
            metricsService.recordDeliveryDispatched(delivery.getTenantId(), "DEAD_LETTERED");
            return;
        }

        // Circuit breaker check
        if (!circuitBreakerService.allowRequest(endpoint.getId())) {
            log.warn("Delivery {} deferred because endpoint {} circuit breaker is OPEN", deliveryId, endpoint.getId());
            delivery.setNextAttemptAt(OffsetDateTime.now().plusSeconds(60));
            delivery.setLockedBy(null);
            delivery.setLockedUntil(null);
            delivery.setUpdatedAt(OffsetDateTime.now());
            deliveryRepository.save(delivery);
            return;
        }

        SignatureService.SignedHeaders signedHeaders = signatureService.generateSignature(
                endpoint.getSecret(),
                event.getPayload()
        );

        OutboundHttpClient.HttpResponseResult result = outboundHttpClient.sendWebhook(
                endpoint.getUrl(),
                event.getPayload(),
                event.getType(),
                delivery.getId(),
                signedHeaders
        );

        int newAttemptCount = delivery.getAttemptCount() + 1;
        delivery.setAttemptCount(newAttemptCount);
        delivery.setLastResponseCode(result.statusCode());
        delivery.setLastResponseSnippet(result.responseSnippet());
        delivery.setLockedBy(null);
        delivery.setLockedUntil(null);
        delivery.setUpdatedAt(OffsetDateTime.now());

        DeliveryAttempt attempt = DeliveryAttempt.builder()
                .id(UUID.randomUUID().toString())
                .deliveryId(delivery.getId())
                .attemptNumber(newAttemptCount)
                .responseCode(result.statusCode())
                .latencyMs(result.latencyMs())
                .error(result.error())
                .createdAt(OffsetDateTime.now())
                .build();
        attemptRepository.save(attempt);

        metricsService.recordDeliveryLatency(delivery.getTenantId(), result.statusCode() != null ? result.statusCode() : 0, result.latencyMs());

        if (result.success()) {
            delivery.setStatus("DELIVERED");
            circuitBreakerService.recordSuccess(endpoint.getId());
            log.info("Delivery {} to endpoint {} succeeded with status {}", delivery.getId(), endpoint.getId(), result.statusCode());
        } else {
            circuitBreakerService.recordFailure(endpoint.getId());
            if (newAttemptCount >= backoffCalculator.getMaxAttempts()) {
                delivery.setStatus("DEAD_LETTERED");
                log.warn("Delivery {} to endpoint {} reached max attempts ({}) and was DEAD_LETTERED. Code: {}",
                        delivery.getId(), endpoint.getId(), newAttemptCount, result.statusCode());
            } else {
                long delaySeconds = backoffCalculator.calculateNextDelaySeconds(newAttemptCount);
                delivery.setStatus("PENDING");
                delivery.setNextAttemptAt(OffsetDateTime.now().plusSeconds(delaySeconds));
                log.info("Delivery {} to endpoint {} failed attempt {}. Next attempt scheduled in {}s",
                        delivery.getId(), endpoint.getId(), newAttemptCount, delaySeconds);
            }
        }

        deliveryRepository.save(delivery);
        metricsService.recordDeliveryDispatched(delivery.getTenantId(), delivery.getStatus());
    }
}
