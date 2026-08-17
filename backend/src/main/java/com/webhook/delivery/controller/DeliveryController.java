package com.webhook.delivery.controller;

import com.webhook.delivery.dto.DeliveryAttemptResponse;
import com.webhook.delivery.dto.DeliveryResponse;
import com.webhook.delivery.dto.PageResponse;
import com.webhook.delivery.security.TenantContext;
import com.webhook.delivery.service.DeliveryService;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/deliveries")
public class DeliveryController {

    private final DeliveryService deliveryService;

    public DeliveryController(DeliveryService deliveryService) {
        this.deliveryService = deliveryService;
    }

    @GetMapping
    public ResponseEntity<PageResponse<DeliveryResponse>> listDeliveries(
            @RequestParam(value = "endpointId", required = false) String endpointId,
            @RequestParam(value = "eventId", required = false) String eventId,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {

        String tenantId = TenantContext.getTenantId();
        int safeSize = Math.min(Math.max(1, size), 100);
        int safePage = Math.max(0, page);
        Pageable pageable = PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt"));

        PageResponse<DeliveryResponse> response = deliveryService.getDeliveries(tenantId, endpointId, eventId, status, pageable);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<DeliveryResponse> getDelivery(@PathVariable("id") String id) {
        String tenantId = TenantContext.getTenantId();
        DeliveryResponse response = deliveryService.getDelivery(tenantId, id);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}/attempts")
    public ResponseEntity<List<DeliveryAttemptResponse>> getDeliveryAttempts(@PathVariable("id") String id) {
        String tenantId = TenantContext.getTenantId();
        List<DeliveryAttemptResponse> attempts = deliveryService.getDeliveryAttempts(tenantId, id);
        return ResponseEntity.ok(attempts);
    }

    @PostMapping("/{id}/redrive")
    public ResponseEntity<DeliveryResponse> redriveDelivery(@PathVariable("id") String id) {
        String tenantId = TenantContext.getTenantId();
        DeliveryResponse response = deliveryService.redriveDelivery(tenantId, id);
        return ResponseEntity.ok(response);
    }
}
