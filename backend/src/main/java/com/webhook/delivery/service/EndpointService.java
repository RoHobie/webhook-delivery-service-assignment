package com.webhook.delivery.service;

import com.webhook.delivery.domain.Endpoint;
import com.webhook.delivery.domain.Tenant;
import com.webhook.delivery.dto.CreateEndpointRequest;
import com.webhook.delivery.dto.EndpointResponse;
import com.webhook.delivery.repository.EndpointRepository;
import com.webhook.delivery.repository.TenantRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class EndpointService {

    private final EndpointRepository endpointRepository;
    private final TenantRepository tenantRepository;
    private final UrlValidationService urlValidationService;
    private final SecureRandom secureRandom = new SecureRandom();

    public EndpointService(EndpointRepository endpointRepository,
                           TenantRepository tenantRepository,
                           UrlValidationService urlValidationService) {
        this.endpointRepository = endpointRepository;
        this.tenantRepository = tenantRepository;
        this.urlValidationService = urlValidationService;
    }

    @Transactional
    public EndpointResponse createEndpoint(String tenantId, CreateEndpointRequest request) {
        ensureTenantExists(tenantId);
        urlValidationService.validateUrl(request.getUrl());

        String secret = generateSecret();
        String eventTypes = String.join(",", request.getSubscribedEventTypes());

        Endpoint endpoint = Endpoint.builder()
                .id(UUID.randomUUID().toString())
                .tenantId(tenantId)
                .url(request.getUrl())
                .secret(secret)
                .subscribedEventTypes(eventTypes)
                .status("ACTIVE")
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .build();

        Endpoint saved = endpointRepository.save(endpoint);
        return mapToResponse(saved, true);
    }

    @Transactional(readOnly = true)
    public List<EndpointResponse> getEndpoints(String tenantId) {
        return endpointRepository.findAllByTenantId(tenantId).stream()
                .map(ep -> mapToResponse(ep, false))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public EndpointResponse getEndpoint(String tenantId, String id) {
        Endpoint ep = endpointRepository.findByTenantIdAndId(tenantId, id)
                .orElseThrow(() -> new ResourceNotFoundException("Endpoint not found with id: " + id));
        return mapToResponse(ep, false);
    }

    @Transactional
    public EndpointResponse disableEndpoint(String tenantId, String id) {
        Endpoint ep = endpointRepository.findByTenantIdAndId(tenantId, id)
                .orElseThrow(() -> new ResourceNotFoundException("Endpoint not found with id: " + id));
        ep.setStatus("DISABLED");
        ep.setUpdatedAt(OffsetDateTime.now());
        Endpoint updated = endpointRepository.save(ep);
        return mapToResponse(updated, false);
    }

    private void ensureTenantExists(String tenantId) {
        if (!tenantRepository.existsById(tenantId)) {
            Tenant tenant = Tenant.builder()
                    .id(tenantId)
                    .name("Tenant " + tenantId)
                    .createdAt(OffsetDateTime.now())
                    .build();
            tenantRepository.save(tenant);
        }
    }

    private String generateSecret() {
        byte[] bytes = new byte[24];
        secureRandom.nextBytes(bytes);
        return "whsec_" + HexFormat.of().formatHex(bytes);
    }

    private EndpointResponse mapToResponse(Endpoint ep, boolean includeSecret) {
        List<String> eventTypes = Arrays.stream(ep.getSubscribedEventTypes().split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());

        return EndpointResponse.builder()
                .id(ep.getId())
                .tenantId(ep.getTenantId())
                .url(ep.getUrl())
                .secret(includeSecret ? ep.getSecret() : null)
                .subscribedEventTypes(eventTypes)
                .status(ep.getStatus())
                .createdAt(ep.getCreatedAt())
                .updatedAt(ep.getUpdatedAt())
                .build();
    }
}
