package com.webhook.delivery.controller;

import com.webhook.delivery.dto.CreateEndpointRequest;
import com.webhook.delivery.dto.EndpointResponse;
import com.webhook.delivery.dto.EndpointTestResultResponse;
import com.webhook.delivery.security.TenantContext;
import com.webhook.delivery.service.EndpointService;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/endpoints")
public class EndpointController {

    private final EndpointService endpointService;

    public EndpointController(EndpointService endpointService) {
        this.endpointService = endpointService;
    }

    @PostMapping
    public ResponseEntity<EndpointResponse> createEndpoint(@Valid @RequestBody CreateEndpointRequest request) {
        String tenantId = TenantContext.getTenantId();
        EndpointResponse response = endpointService.createEndpoint(tenantId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<List<EndpointResponse>> listEndpoints() {
        String tenantId = TenantContext.getTenantId();
        List<EndpointResponse> responses = endpointService.getEndpoints(tenantId);
        return ResponseEntity.ok(responses);
    }

    @GetMapping("/{id}")
    public ResponseEntity<EndpointResponse> getEndpoint(@PathVariable("id") String id) {
        String tenantId = TenantContext.getTenantId();
        EndpointResponse response = endpointService.getEndpoint(tenantId, id);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<EndpointResponse> disableEndpoint(@PathVariable("id") String id) {
        String tenantId = TenantContext.getTenantId();
        EndpointResponse response = endpointService.disableEndpoint(tenantId, id);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{id}/test")
    public ResponseEntity<EndpointTestResultResponse> testEndpoint(@PathVariable("id") String id) {
        String tenantId = TenantContext.getTenantId();
        EndpointTestResultResponse result = endpointService.testEndpoint(tenantId, id);
        return ResponseEntity.ok(result);
    }
}
