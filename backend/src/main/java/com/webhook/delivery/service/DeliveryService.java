package com.webhook.delivery.service;

import com.webhook.delivery.domain.Delivery;
import com.webhook.delivery.domain.DeliveryAttempt;
import com.webhook.delivery.dto.DeliveryAttemptResponse;
import com.webhook.delivery.dto.DeliveryResponse;
import com.webhook.delivery.dto.PageResponse;
import com.webhook.delivery.repository.DeliveryAttemptRepository;
import com.webhook.delivery.repository.DeliveryRepository;

import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class DeliveryService {

    private final DeliveryRepository deliveryRepository;
    private final DeliveryAttemptRepository attemptRepository;

    public DeliveryService(DeliveryRepository deliveryRepository,
                           DeliveryAttemptRepository attemptRepository) {
        this.deliveryRepository = deliveryRepository;
        this.attemptRepository = attemptRepository;
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
    public PageResponse<DeliveryResponse> getDeliveries(String tenantId, String endpointId, String eventId, String status, Pageable pageable) {
        Specification<Delivery> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("tenantId"), tenantId));

            if (endpointId != null && !endpointId.isBlank()) {
                predicates.add(cb.equal(root.get("endpointId"), endpointId));
            }
            if (eventId != null && !eventId.isBlank()) {
                predicates.add(cb.equal(root.get("eventId"), eventId));
            }
            if (status != null && !status.isBlank()) {
                predicates.add(cb.equal(root.get("status"), status));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };

        Page<Delivery> page = deliveryRepository.findAll(spec, pageable);
        Page<DeliveryResponse> dtoPage = page.map(this::mapToResponse);
        return PageResponse.from(dtoPage);
    }

    @Transactional(readOnly = true)
    public List<DeliveryAttemptResponse> getDeliveryAttempts(String tenantId, String deliveryId) {
        // Enforce tenant scoping: verify delivery belongs to tenantId
        deliveryRepository.findByTenantIdAndId(tenantId, deliveryId)
                .orElseThrow(() -> new ResourceNotFoundException("Delivery not found: " + deliveryId));

        List<DeliveryAttempt> attempts = attemptRepository.findAllByDeliveryIdOrderByAttemptNumberAsc(deliveryId);
        return attempts.stream()
                .map(a -> DeliveryAttemptResponse.builder()
                        .id(a.getId())
                        .deliveryId(a.getDeliveryId())
                        .attemptNumber(a.getAttemptNumber())
                        .responseCode(a.getResponseCode())
                        .latencyMs(a.getLatencyMs())
                        .error(a.getError())
                        .createdAt(a.getCreatedAt())
                        .build())
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
