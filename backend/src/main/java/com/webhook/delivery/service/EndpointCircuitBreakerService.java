package com.webhook.delivery.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class EndpointCircuitBreakerService {

    private static final Logger log = LoggerFactory.getLogger(EndpointCircuitBreakerService.class);

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final int failureThreshold;
    private final long cooldownMillis;
    private final Map<String, EndpointCircuit> circuits = new ConcurrentHashMap<>();

    public EndpointCircuitBreakerService(
            @Value("${app.circuit-breaker.failure-threshold:5}") int failureThreshold,
            @Value("${app.circuit-breaker.cooldown-seconds:60}") long cooldownSeconds) {
        this.failureThreshold = failureThreshold;
        this.cooldownMillis = cooldownSeconds * 1000;
    }

    public boolean allowRequest(String endpointId) {
        if (endpointId == null) {
            return true;
        }
        EndpointCircuit circuit = circuits.computeIfAbsent(endpointId, id -> new EndpointCircuit());
        return circuit.allowRequest(cooldownMillis);
    }

    public void recordSuccess(String endpointId) {
        if (endpointId == null) {
            return;
        }
        EndpointCircuit circuit = circuits.get(endpointId);
        if (circuit != null) {
            circuit.recordSuccess();
        }
    }

    public void recordFailure(String endpointId) {
        if (endpointId == null) {
            return;
        }
        EndpointCircuit circuit = circuits.computeIfAbsent(endpointId, id -> new EndpointCircuit());
        circuit.recordFailure(failureThreshold, cooldownMillis);
    }

    public State getState(String endpointId) {
        EndpointCircuit circuit = circuits.get(endpointId);
        if (circuit == null) {
            return State.CLOSED;
        }
        return circuit.getState(cooldownMillis);
    }

    public void reset(String endpointId) {
        if (endpointId != null) {
            circuits.remove(endpointId);
        }
    }

    public void clearAll() {
        circuits.clear();
    }

    private static class EndpointCircuit {
        private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
        private volatile State state = State.CLOSED;
        private volatile long openUntil = 0L;

        public synchronized boolean allowRequest(long cooldownMillis) {
            long now = System.currentTimeMillis();
            if (state == State.OPEN) {
                if (now >= openUntil) {
                    state = State.HALF_OPEN;
                    log.info("Circuit breaker transitioning to HALF_OPEN for probing");
                    return true;
                }
                return false;
            }
            return true;
        }

        public synchronized void recordSuccess() {
            consecutiveFailures.set(0);
            if (state != State.CLOSED) {
                log.info("Circuit breaker closed after successful probe request");
                state = State.CLOSED;
            }
        }

        public synchronized void recordFailure(int threshold, long cooldownMillis) {
            int failures = consecutiveFailures.incrementAndGet();
            if (state == State.HALF_OPEN || failures >= threshold) {
                state = State.OPEN;
                openUntil = System.currentTimeMillis() + cooldownMillis;
                log.warn("Circuit breaker opened due to {} consecutive failures. Cooldown until {}", failures, openUntil);
            }
        }

        public synchronized State getState(long cooldownMillis) {
            if (state == State.OPEN && System.currentTimeMillis() >= openUntil) {
                return State.HALF_OPEN;
            }
            return state;
        }
    }
}
