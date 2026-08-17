package com.webhook.delivery.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Random;

@Component
public class BackoffCalculator {

    private final long baseDelaySeconds;
    private final long maxDelaySeconds;
    private final int maxAttempts;
    private final Random random;

    public BackoffCalculator(
            @Value("${app.worker.base-delay-seconds:5}") long baseDelaySeconds,
            @Value("${app.worker.max-delay-seconds:86400}") long maxDelaySeconds,
            @Value("${app.worker.max-attempts:8}") int maxAttempts) {
        this.baseDelaySeconds = baseDelaySeconds;
        this.maxDelaySeconds = maxDelaySeconds;
        this.maxAttempts = maxAttempts;
        this.random = new Random();
    }

    public long calculateNextDelaySeconds(int attemptNumber) {
        if (attemptNumber <= 0) {
            return baseDelaySeconds;
        }

        // Exponential calculation: base * 2^(attempt - 1)
        double exponential = baseDelaySeconds * Math.pow(2, attemptNumber - 1);
        long cap = Math.min((long) Math.min(exponential, Long.MAX_VALUE), maxDelaySeconds);

        // Equal jitter: 50% deterministic + 50% random variation
        long minJitter = cap / 2;
        long jitterSpan = cap - minJitter;
        long delay = minJitter + (jitterSpan > 0 ? (long) (random.nextDouble() * jitterSpan) : 0);

        return Math.max(1, delay);
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }
}
