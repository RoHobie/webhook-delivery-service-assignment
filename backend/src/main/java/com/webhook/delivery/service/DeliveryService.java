package com.webhook.delivery.service;

import com.webhook.delivery.domain.Delivery;
import com.webhook.delivery.dto.DeliveryResponse;
import com.webhook.delivery.repository.DeliveryRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class DeliveryService {

    private final DeliveryRepository deliveryRepository;

    public DeliveryService(DeliveryRepository deliveryRepository) {
        this.deliveryRepository = deliveryRepository;
    }

    @Transactional
    public DeliveryResponse redriveDelivery(String tenantId, String deliveryId) {
        Delivery delivery = deliveryRepository.findByTenantIdAndId(tenantId, deliveryId)
                .orElseThrow(() -> new ResourceNotFoundException("Delivery not found: " + deliveryId));

        if (!"DEAD_LETTERED".equalsIgnoreCase(delivery.getStatus()) && !"FAILED".equalsIgnoreCase(delivery.getStatus())) {
            throw new IllegalArgumentException("Only DEAD_LETTERED or FAILED deliveries can be redriven. Current status: " + delivery.getStatus());
        }

        delivery.setStatus("PENDING");
        delivery.setNextAttemptAt(OffsetDateTime.now());
        delivery.setLockedBy(null);
        delivery.setLockedUntil(null);
        delivery.setUpdatedAt(OffsetDateTime.now());

        Delivery updated = deliveryRepository.save(delivery);
        return mapToResponse(updated);
    }

    @Transactional(readOnly = true)
    public DeliveryResponse getDelivery(String tenantId, String deliveryId) {
        Delivery delivery = deliveryRepository.findByTenantIdAndId(tenantId, deliveryId)
                .orElseThrow(() -> new ResourceNotFoundException("Delivery not found: " + deliveryId));
        return mapToResponse(delivery);
    }

    @Transactional(readOnly = true)
    public List<DeliveryResponse> getDeliveriesForEvent(String tenantId, String eventId) {
        return deliveryRepository.findAllByTenantIdAndEventId(tenantId, eventId).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    private DeliveryResponse mapToResponse(Delivery d) {
        return DeliveryResponse.builder()
                .id(d.getId())
                .eventId(d.getEventId())
                .endpointId(d.getEndpointId())
                .tenantId(d.getTenantId())
                .status(d.getStatus())
                .attemptCount(d.getAttemptCount())
                .lastResponseCode(d.getLastResponseCode())
                .lastResponseSnippet(d.getLastResponseSnippet())
                .nextAttemptAt(d.getNextAttemptAt())
                .createdAt(d.getCreatedAt())
                .updatedAt(d.getUpdatedAt())
                .build();
    }
}
