package com.webhook.delivery.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.delivery.domain.Delivery;
import com.webhook.delivery.domain.Endpoint;
import com.webhook.delivery.domain.WebhookEvent;
import com.webhook.delivery.dto.IngestEventRequest;
import com.webhook.delivery.dto.IngestEventResponse;
import com.webhook.delivery.repository.DeliveryRepository;
import com.webhook.delivery.repository.EndpointRepository;
import com.webhook.delivery.repository.WebhookEventRepository;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class EventIngestionService {

    private final WebhookEventRepository webhookEventRepository;
    private final EndpointRepository endpointRepository;
    private final DeliveryRepository deliveryRepository;
    private final ObjectMapper objectMapper;
    private final MetricsService metricsService;

    public EventIngestionService(WebhookEventRepository webhookEventRepository,
                                 EndpointRepository endpointRepository,
                                 DeliveryRepository deliveryRepository,
                                 ObjectMapper objectMapper,
                                 MetricsService metricsService) {
        this.webhookEventRepository = webhookEventRepository;
        this.endpointRepository = endpointRepository;
        this.deliveryRepository = deliveryRepository;
        this.objectMapper = objectMapper;
        this.metricsService = metricsService;
    }

    @Transactional
    public IngestEventResponse ingestEvent(String tenantId, IngestEventRequest request) {
        // Pre-check for duplicate external eventId under the same tenant
        Optional<WebhookEvent> existingOpt = webhookEventRepository.findByTenantIdAndEventIdExternal(tenantId, request.getEventId());
        if (existingOpt.isPresent()) {
            metricsService.recordEventDuplicate(tenantId);
            WebhookEvent existing = existingOpt.get();
            long count = deliveryRepository.countByTenantIdAndEventId(tenantId, existing.getId());
            return IngestEventResponse.builder()
                    .id(existing.getId())
                    .eventId(existing.getEventIdExternal())
                    .status("ACCEPTED")
                    .deliveriesCreated((int) count)
                    .createdAt(existing.getCreatedAt())
                    .build();
        }

        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(request.getPayload());
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid payload JSON: " + e.getMessage());
        }

        WebhookEvent eventToSave = WebhookEvent.builder()
                .id(UUID.randomUUID().toString())
                .tenantId(tenantId)
                .eventIdExternal(request.getEventId())
                .type(request.getType())
                .payload(payloadJson)
                .createdAt(OffsetDateTime.now())
                .build();

        WebhookEvent savedEvent;
        try {
            savedEvent = webhookEventRepository.save(eventToSave);
        } catch (DataIntegrityViolationException ex) {
            metricsService.recordEventDuplicate(tenantId);
            WebhookEvent existing = webhookEventRepository.findByTenantIdAndEventIdExternal(tenantId, request.getEventId())
                    .orElseThrow(() -> ex);
            long count = deliveryRepository.countByTenantIdAndEventId(tenantId, existing.getId());
            return IngestEventResponse.builder()
                    .id(existing.getId())
                    .eventId(existing.getEventIdExternal())
                    .status("ACCEPTED")
                    .deliveriesCreated((int) count)
                    .createdAt(existing.getCreatedAt())
                    .build();
        }

        metricsService.recordEventIngested(tenantId, request.getType());

        // Fan out to active endpoints subscribing to this event type
        List<Endpoint> activeEndpoints = endpointRepository.findAllByTenantIdAndStatus(tenantId, "ACTIVE");
        List<Endpoint> matchingEndpoints = activeEndpoints.stream()
                .filter(ep -> isSubscribed(ep.getSubscribedEventTypes(), request.getType()))
                .collect(Collectors.toList());

        OffsetDateTime now = OffsetDateTime.now();
        List<Delivery> deliveries = matchingEndpoints.stream()
                .map(ep -> Delivery.builder()
                        .id(UUID.randomUUID().toString())
                        .eventId(savedEvent.getId())
                        .endpointId(ep.getId())
                        .tenantId(tenantId)
                        .status("PENDING")
                        .attemptCount(0)
                        .nextAttemptAt(now)
                        .createdAt(now)
                        .updatedAt(now)
                        .build())
                .collect(Collectors.toList());

        if (!deliveries.isEmpty()) {
            deliveryRepository.saveAll(deliveries);
        }

        return IngestEventResponse.builder()
                .id(savedEvent.getId())
                .eventId(savedEvent.getEventIdExternal())
                .status("ACCEPTED")
                .deliveriesCreated(deliveries.size())
                .createdAt(savedEvent.getCreatedAt())
                .build();
    }

    private boolean isSubscribed(String subscribedTypesStr, String eventType) {
        if (subscribedTypesStr == null || subscribedTypesStr.isBlank()) {
            return false;
        }
        String[] types = subscribedTypesStr.split(",");
        return Arrays.stream(types)
                .map(String::trim)
                .anyMatch(t -> t.equals("*") || t.equalsIgnoreCase(eventType));
    }
}
