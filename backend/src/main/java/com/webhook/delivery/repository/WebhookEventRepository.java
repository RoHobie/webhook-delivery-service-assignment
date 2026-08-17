package com.webhook.delivery.repository;

import com.webhook.delivery.domain.WebhookEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WebhookEventRepository extends JpaRepository<WebhookEvent, String> {

    Optional<WebhookEvent> findByTenantIdAndEventIdExternal(String tenantId, String eventIdExternal);

    Optional<WebhookEvent> findByTenantIdAndId(String tenantId, String id);
}
