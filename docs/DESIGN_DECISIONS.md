# Architectural Design & Rationale

This document details the engineering rationale (Why) and technical implementation (How) behind key architectural decisions in this repository.

---

## 1. At-Least-Once Delivery Guarantee vs. "Usually"

### The Challenge
Webhooks must never be dropped silently during process restarts, server crashes, or network partitions. Memory-queued jobs lost on app restart represent a critical failure in delivery infrastructure.

### Why This Design Was Chosen
We chose a **Database-Backed Persistent State Machine** over in-memory queues (e.g. RxJava, Java `BlockingQueue`). Every event ingestion immediately persists delivery rows with state `PENDING` within the same database transaction.

### How It Is Implemented
- **Transactional Ingestion**: Ingestion fan-out creates `deliveries` rows in state `PENDING`.
- **Atomic Lease Duration**: When claimed by a worker, `locked_until` is set to `NOW() + 30 seconds`.
- **Self-Healing Crash Recovery**: If a worker node crashes while delivering a webhook, `CrashRecoveryService` automatically clears expired leases (`WHERE locked_until < NOW() AND status = 'PENDING'`), making those deliveries instantly claimable by surviving worker nodes.

---

## 2. DB-Level Due-Work Selection & Row Locking (`FOR UPDATE SKIP LOCKED`)

### The Challenge
A common anti-pattern is fetching all pending delivery rows into Java memory (`SELECT * FROM deliveries`) and filtering due items via Java `.stream()`. This causes severe OOM crashes at scale, table scan lock contention, and duplicate execution across multiple worker nodes.

### Why This Design Was Chosen
Delegating claim selection directly to PostgreSQL via `FOR UPDATE SKIP LOCKED` guarantees sub-millisecond job acquisition, zero lock contention between concurrent worker threads, and linear scalability to millions of pending deliveries.

### How It Is Implemented
- **Claim Query**:
  ```sql
  SELECT * FROM deliveries
  WHERE status = 'PENDING'
    AND next_attempt_at <= NOW()
    AND locked_until < NOW()
  ORDER BY next_attempt_at ASC
  LIMIT 50
  FOR UPDATE SKIP LOCKED;
  ```
- **Partial Indexing Support**:
  ```sql
  CREATE INDEX idx_deliveries_pending_claim
  ON deliveries (status, next_attempt_at)
  WHERE status = 'PENDING';
  ```
  This partial index excludes historical `DELIVERED` and `DEAD_LETTERED` records, keeping index size small and claim queries fast even with tens of millions of historical rows.

---

## 3. Strict Multi-Tenant Isolation

### The Challenge
In a multi-tenant platform, data leakage between tenants is an unacceptable vulnerability. Tenant A must never access Tenant B's endpoints, delivery logs, or signing secrets—even if Tenant A attempts URL tampering with Tenant B's UUIDs.

### Why This Design Was Chosen
We implemented **Layered Defensive Isolation**: HTTP header authentication at the interceptor level, ThreadLocal context propagation, and mandatory tenant predicate enforcement in all SQL repository queries.

### How It Is Implemented
- **Tenant Context Interceptor**: `TenantInterceptor` extracts `X-Tenant-Id` header and validates tenant existence against the `tenants` table before reaching any controller logic.
- **ThreadLocal Scoping**: Stored in `TenantContext.getTenantId()`.
- **Query Hardening & Obfuscation**: Every JPA query explicitly includes `WHERE tenantId = :tenantId`. Requesting another tenant's entity returns `HTTP 404 Not Found` (or `401`), hiding the existence of foreign resources.

---

## 4. Producer Event Idempotency

### The Challenge
Upstream producer systems frequently retry event ingestion due to network timeouts. Re-ingesting the same producer `eventId` must not generate duplicate outbound webhooks.

### Why This Design Was Chosen
Relying on application-level checks-then-inserts creates race conditions under high concurrency. We backed idempotency with a PostgreSQL database-level unique constraint.

### How It Is Implemented
- **Unique Constraint**: `UNIQUE (tenant_id, event_id_external)` in table `events`.
- **Graceful Collision Handling**: On duplicate submission, `EventIngestionService` catches constraint violations / queries the existing event and returns `HTTP 202 Accepted` with existing status details, bypassing endpoint fan-out logic entirely.

---

## 5. High-Throughput Concurrency & Virtual Threads

### The Challenge
Traditional OS thread pools (e.g. 200 Tomcat threads) exhaust quickly when outbound webhook targets are slow or hanging.

### Why This Design Was Chosen
Java 21 **Virtual Threads** (`Executors.newVirtualThreadPerTaskExecutor()`) allow millions of concurrent non-blocking tasks to execute with minimal RAM and CPU thread overhead.

### How It Is Implemented
- **Virtual Thread Dispatcher**: `DeliveryDispatchService` dispatches each claimed delivery attempt onto a dedicated Virtual Thread.
- **Strict Timeout Safeguards**: `OutboundHttpClient` enforces strict HTTP connect timeouts (3 seconds) and read timeouts (5 seconds). Slow endpoints are disconnected without blocking underlying platform resources.

---

## 6. Security, HMAC-SHA256 Signing & SSRF Protection

### The Challenge
Receivers need cryptographic proof that webhooks originate from our platform. Additionally, malicious users might attempt Server-Side Request Forgery (SSRF) by registering internal IPs (`http://127.0.0.1/admin`).

### Why This Design Was Chosen
- **HMAC-SHA256**: Standards-compliant payload signing with random per-endpoint secrets.
- **SSRF Defensive Guard**: Preventive validation upon endpoint creation and before HTTP execution.

### How It Is Implemented
- **HMAC Signing**: Standardized signature header `X-Webhook-Signature: t=<timestamp>,v1=<hex_digest>` generated over `$timestamp.$rawBody`.
- **SSRF Blocklist**: Reject endpoints resolving to `127.0.0.1`, `localhost`, RFC 1918 private subnets (`10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`), link-local (`169.254.0.0/16`), and multicast IP ranges.

---

## 7. Endpoint Circuit Breaker & Exponential Backoff + Jitter

### The Challenge
- Repeatedly calling dead endpoints wastes system resources and risks crashing receiver servers as soon as they reboot.
- Retrying on fixed intervals causes "thundering herd" traffic spikes.

### Why This Design Was Chosen
- **Circuit Breaker**: Pauses delivery attempts to consistently failing endpoints.
- **Equal Jitter Backoff**: Distributes retry attempts evenly over time.

### How It Is Implemented
- **Circuit Breaker State Machine**: `EndpointCircuitBreakerService` tracks consecutive failures per endpoint. 5 consecutive failures trip state to `OPEN` for a 60-second cooldown window.
- **Backoff Formula**:
  `delay = base_delay * 2^(attempt - 1)`
  `actual_delay = (delay / 2) + random(0, delay / 2)`
  Verified by unit test suite (`BackoffCalculatorTest`).

---

## 8. Design Summary & Verification

| Feature / Requirement | Design Choice | Implementation Verification |
|---|---|---|
| **Setup & Deployment** | Spring Boot 4.x + Docker Compose + Flyway | Verified via `docker-compose up` |
| **At-Least-Once Delivery** | Persistent `PENDING` state + lease recovery | `CircuitBreakerAndRecoveryIntegrationTest` |
| **Tenant Isolation** | Header interceptor + DB tenant scoping | `EndpointIntegrationTest` |
| **DB-Level Work Selection** | `FOR UPDATE SKIP LOCKED` + partial index | `ConcurrencyClaimTest` |
| **Resilience & SSRF Guard** | Circuit breaker + SSRF guard + timeouts | `DeliveryRetryIntegrationTest` |
| **Idempotent Ingestion** | `(tenant_id, event_id_ext)` constraint | `EventIngestionIntegrationTest` |

