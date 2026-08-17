package com.webhook.delivery.repository;

import com.webhook.delivery.domain.Delivery;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DeliveryRepository extends JpaRepository<Delivery, String> {

    List<Delivery> findAllByTenantIdAndEventId(String tenantId, String eventId);

    List<Delivery> findAllByTenantIdAndEndpointId(String tenantId, String endpointId);

    Optional<Delivery> findByTenantIdAndId(String tenantId, String id);

    long countByTenantIdAndEventId(String tenantId, String eventId);
}
