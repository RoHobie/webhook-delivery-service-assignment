package com.webhook.delivery.controller;

import com.webhook.delivery.dto.IngestEventRequest;
import com.webhook.delivery.dto.IngestEventResponse;
import com.webhook.delivery.security.TenantContext;
import com.webhook.delivery.service.EventIngestionService;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/events")
public class EventController {

    private final EventIngestionService eventIngestionService;

    public EventController(EventIngestionService eventIngestionService) {
        this.eventIngestionService = eventIngestionService;
    }

    @PostMapping
    public ResponseEntity<IngestEventResponse> ingestEvent(@Valid @RequestBody IngestEventRequest request) {
        String tenantId = TenantContext.getTenantId();
        IngestEventResponse response = eventIngestionService.ingestEvent(tenantId, request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }
}
