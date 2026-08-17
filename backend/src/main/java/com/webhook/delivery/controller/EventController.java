package com.webhook.delivery.controller;

import com.webhook.delivery.dto.IngestEventRequest;
import com.webhook.delivery.dto.IngestEventResponse;
import com.webhook.delivery.security.TenantContext;
import com.webhook.delivery.service.EventIngestionService;
import com.webhook.delivery.service.TenantRateLimiterService;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/events")
public class EventController {

    private final EventIngestionService eventIngestionService;
    private final TenantRateLimiterService rateLimiterService;

    public EventController(EventIngestionService eventIngestionService,
                           TenantRateLimiterService rateLimiterService) {
        this.eventIngestionService = eventIngestionService;
        this.rateLimiterService = rateLimiterService;
    }

    @PostMapping
    public ResponseEntity<?> ingestEvent(@Valid @RequestBody IngestEventRequest request) {
        String tenantId = TenantContext.getTenantId();

        if (!rateLimiterService.tryAcquire(tenantId)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", "1")
                    .body(Map.of("error", "Rate limit exceeded for tenant: " + tenantId));
        }

        IngestEventResponse response = eventIngestionService.ingestEvent(tenantId, request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }
}
