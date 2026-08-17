package com.webhook.delivery.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TenantRateLimiterService {

    private final double refillRatePerSecond;
    private final double capacity;
    private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    public TenantRateLimiterService(
            @Value("${app.rate-limit.events-per-second:100}") double refillRatePerSecond,
            @Value("${app.rate-limit.capacity:100}") double capacity) {
        this.refillRatePerSecond = refillRatePerSecond;
        this.capacity = capacity;
    }

    public boolean tryAcquire(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return false;
        }
        TokenBucket bucket = buckets.computeIfAbsent(tenantId, id -> new TokenBucket(capacity, refillRatePerSecond));
        return bucket.tryConsume();
    }

    public void clear() {
        buckets.clear();
    }

    private static class TokenBucket {
        private final double capacity;
        private final double refillRatePerSecond;
        private double tokens;
        private long lastRefillTimestampNanos;

        public TokenBucket(double capacity, double refillRatePerSecond) {
            this.capacity = capacity;
            this.refillRatePerSecond = refillRatePerSecond;
            this.tokens = capacity;
            this.lastRefillTimestampNanos = System.nanoTime();
        }

        public synchronized boolean tryConsume() {
            refill();
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }

        private void refill() {
            long now = System.nanoTime();
            double elapsedSeconds = (now - lastRefillTimestampNanos) / 1_000_000_000.0;
            if (elapsedSeconds > 0) {
                tokens = Math.min(capacity, tokens + elapsedSeconds * refillRatePerSecond);
                lastRefillTimestampNanos = now;
            }
        }
    }
}
