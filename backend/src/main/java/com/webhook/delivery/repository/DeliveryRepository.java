package com.webhook.delivery.repository;

import com.webhook.delivery.domain.Delivery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface DeliveryRepository extends JpaRepository<Delivery, String> {

    @Query(value = """
        SELECT * FROM deliveries d
        WHERE d.status = 'PENDING'
          AND d.next_attempt_at <= :now
          AND (d.locked_until IS NULL OR d.locked_until < :now)
        ORDER BY d.next_attempt_at ASC
        LIMIT :limit
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<Delivery> claimDueDeliveriesNative(@Param("now") OffsetDateTime now, @Param("limit") int limit);

    List<Delivery> findAllByTenantIdAndEventId(String tenantId, String eventId);

    List<Delivery> findAllByTenantIdAndEndpointId(String tenantId, String endpointId);

    Optional<Delivery> findByTenantIdAndId(String tenantId, String id);

    long countByTenantIdAndEventId(String tenantId, String eventId);
}
