package com.webhook.delivery.repository;

import com.webhook.delivery.domain.Endpoint;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EndpointRepository extends JpaRepository<Endpoint, String> {

    Optional<Endpoint> findByTenantIdAndId(String tenantId, String id);

    List<Endpoint> findAllByTenantId(String tenantId);

    List<Endpoint> findAllByTenantIdAndStatus(String tenantId, String status);
}
