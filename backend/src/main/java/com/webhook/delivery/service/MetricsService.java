package com.webhook.delivery.service;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
public class MetricsService {

    private final MeterRegistry meterRegistry;

    public MetricsService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void recordEventIngested(String tenantId, String eventType) {
        meterRegistry.counter("webhook.events.ingested.total",
                "tenant_id", sanitize(tenantId),
                "event_type", sanitize(eventType)).increment();
    }

    public void recordEventDuplicate(String tenantId) {
        meterRegistry.counter("webhook.events.duplicate.total",
                "tenant_id", sanitize(tenantId)).increment();
    }

    public void recordDeliveryDispatched(String tenantId, String status) {
        meterRegistry.counter("webhook.deliveries.dispatched.total",
                "tenant_id", sanitize(tenantId),
                "status", sanitize(status)).increment();
    }

    public void recordDeliveryLatency(String tenantId, int statusCode, long durationMs) {
        meterRegistry.timer("webhook.delivery.latency",
                "tenant_id", sanitize(tenantId),
                "status_code", String.valueOf(statusCode))
                .record(durationMs, TimeUnit.MILLISECONDS);
    }

    private String sanitize(String value) {
        return (value != null && !value.isBlank()) ? value : "unknown";
    }

    public MeterRegistry getMeterRegistry() {
        return meterRegistry;
    }
}
