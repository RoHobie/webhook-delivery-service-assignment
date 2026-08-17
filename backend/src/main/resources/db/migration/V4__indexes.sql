CREATE INDEX idx_deliveries_claim ON deliveries(status, next_attempt_at);
CREATE INDEX idx_deliveries_tenant_endpoint ON deliveries(tenant_id, endpoint_id, created_at);
CREATE INDEX idx_deliveries_tenant_event ON deliveries(tenant_id, event_id);
