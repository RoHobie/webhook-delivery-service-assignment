package com.webhook.delivery.controller;

import com.webhook.delivery.dto.DeliveryResponse;
import com.webhook.delivery.security.TenantContext;
import com.webhook.delivery.service.DeliveryService;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/deliveries")
public class DeliveryController {

    private final DeliveryService deliveryService;

    public DeliveryController(DeliveryService deliveryService) {
        this.deliveryService = deliveryService;
    }

    @PostMapping("/{id}/redrive")
    public ResponseEntity<DeliveryResponse> redriveDelivery(@PathVariable("id") String id) {
        String tenantId = TenantContext.getTenantId();
        DeliveryResponse response = deliveryService.redriveDelivery(tenantId, id);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<DeliveryResponse> getDelivery(@PathVariable("id") String id) {
        String tenantId = TenantContext.getTenantId();
        DeliveryResponse response = deliveryService.getDelivery(tenantId, id);
        return ResponseEntity.ok(response);
    }
}
