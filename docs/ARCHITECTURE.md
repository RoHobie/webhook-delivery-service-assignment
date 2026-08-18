# System Architecture

This document details the architectural design, core components, concurrency model, security, and database design of the **Multi-Tenant Webhook Delivery Service**.

---

## 1. High-Level Architecture Overview

The system uses an asynchronous, lock-free pipeline backed by PostgreSQL state persistence.

```
                                  +------------------------------------+
                                  |     Tenant Applications / Producers |
                                  +------------------------------------+
                                                    |
                                                    | HTTP POST /api/v1/events
                                                    v
+---------------------------------------------------------------------------------------------------+
| Webhook Delivery Service (Spring Boot 4.x)                                                        |
|                                                                                                   |
|  +-----------------------+      +--------------------------+      +----------------------------+  |
|  | TenantInterceptor     | ---> | TenantRateLimiterService | ---> | EventIngestionService      |  |
|  | (X-Tenant-Id check)   |      | (In-Memory Token Bucket) |      | (DB Duplicate Check)       |  |
|  +-----------------------+      +--------------------------+      +----------------------------+  |
|                                                                                 |                 |
|                                                                                 v                 |
|                                                                   +----------------------------+  |
|                                                                   | Fan-out Matching Engine    |  |
|                                                                   | (Subscribed Endpoints)     |  |
|                                                                   +----------------------------+  |
|                                                                                 |                 |
+---------------------------------------------------------------------------------|-----------------+
                                                                                  v
                                                                    +----------------------------+
                                                                    |   PostgreSQL Database      |
                                                                    |  - events                  |
                                                                    |  - deliveries (PENDING)    |
                                                                    +----------------------------+
                                                                                  |
                                                                                  | SELECT FOR UPDATE
                                                                                  | SKIP LOCKED
                                                                                  v
+---------------------------------------------------------------------------------------------------+
| Delivery Dispatch Subsystem                                                                        |
|                                                                                                   |
|  +-----------------------------+     +-----------------------------+     +----------------------+ |
|  | DeliveryClaimService        | --> | EndpointCircuitBreaker      | --> | OutboundHttpClient   | |
|  | (Claims batch of PENDING)   |     | (State: Closed/Open)        |     | (Java 21 Virtual Threads) |
|  +-----------------------------+     +-----------------------------+     +----------------------+ |
|                                                                                     |             |
|                                                                                     v             |
|  +-----------------------------+     +-----------------------------+     +----------------------+ |
|  | DeliveryAttempt Audit Log   | <-- | Exponential Backoff Calculator| <-- | HMAC-SHA256 Payload  | |
|  | & DLQ Transition Logic      |     | (Base * 2^(n-1) + Jitter)   |     | Signature Engine     | |
|  +-----------------------------+     +-----------------------------+     +----------------------+ |
+---------------------------------------------------------------------------------|-----------------+
                                                                                  |
                                                                                  v HTTP POST
                                                                    +----------------------------+
                                                                    | Receiver Webhook Endpoint  |
                                                                    +----------------------------+
```

---

## 2. Subsystem Breakdown

### 2.1 Tenant Isolation & Security Interceptor
- Inbound API requests are validated by `TenantInterceptor` checking the mandatory `X-Tenant-Id` header against registered tenants.
- Valid tenant IDs are stored in a `ThreadLocal` context (`TenantContext`), ensuring operations remain scoped to the requesting tenant.
- Unauthorized attempts to access foreign tenant resources return `404 Not Found` or `401 Unauthorized`.

### 2.2 Ingestion & Deduplication Pipeline
- Event producers provide an `eventId` (`event_id_external`).
- A composite unique constraint on `(tenant_id, event_id_external)` in PostgreSQL prevents duplicate inserts.
- If a duplicate event arrives, `EventIngestionService` returns `202 Accepted` with the existing status without duplicating delivery jobs.

### 2.3 DB-Level Claim & Concurrency Engine
- Job claiming is performed directly in PostgreSQL using:
  ```sql
  SELECT * FROM deliveries
  WHERE status = 'PENDING'
    AND next_attempt_at <= :now
    AND locked_until < :now
  ORDER BY next_attempt_at ASC
  LIMIT :batchSize
  FOR UPDATE SKIP LOCKED;
  ```
- Concurrent worker threads execute this query safely. `FOR UPDATE SKIP LOCKED` ensures workers never claim the same row or block each other.
- Outbound HTTP deliveries are dispatched asynchronously using Java 21 Virtual Threads (`Executors.newVirtualThreadPerTaskExecutor()`).

### 2.4 Security & SSRF Protection
- **HMAC-SHA256 Payload Signing**: Webhook payloads are signed using a secret hex key assigned to each endpoint:
  ```
  X-Webhook-Signature: t=1771182300,v1=a8f9c2...
  X-Webhook-Timestamp: 1771182300
  X-Webhook-Event-Type: order.created
  X-Webhook-Delivery-Id: del_881920
  ```
- **SSRF Guard**: Endpoint URLs are checked during registration and dispatch. Requests targeting loopback (`127.0.0.1`, `localhost`), private subnets (`10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`), link-local (`169.254.0.0/16`), or multicast IPs are blocked.

### 2.5 Resilience & Self-Healing Architecture
1. **Endpoint Circuit Breaker**:
   - `EndpointCircuitBreakerService` monitors delivery failures.
   - Trips to `OPEN` after 5 consecutive failures per endpoint, pausing attempts for a 60-second cooldown window before testing recovery in `HALF_OPEN`.
2. **Exponential Backoff with Equal Jitter**:
   - Standard delay formula:
     `delay = base_delay * 2^(attempt - 1)`
   - With 50% Equal Jitter:
     `actual_delay = (delay / 2) + random(0, delay / 2)`
   - Prevents thundering herd retries when recovering target servers.
3. **Dead-Letter Queue (DLQ) & Redrive**:
   - Deliveries reaching max attempts (default: 8) transition to `DEAD_LETTERED`.
   - Tenants can manually trigger a redrive via `POST /api/v1/deliveries/{id}/redrive`, resetting status to `PENDING` and attempt count to `0`.
4. **Crash Recovery**:
   - When a delivery is claimed, `locked_until` is set to 30 seconds in the future.
   - If a worker crashes mid-attempt, `CrashRecoveryService` clears expired locks (`locked_until < NOW()`), making items available for re-claiming.

---

## 3. Database Schema & Indexing Strategy

```
+------------------+         +--------------------------+
|     tenants      |         |        endpoints         |
+------------------+         +--------------------------+
| id (PK)          | <-----+ | id (PK)                  |
| name             |         | tenant_id (FK)           |
| created_at       |         | url                      |
+------------------+         | secret                   |
                             | subscribed_event_types[] |
                             | status (ACTIVE/DISABLED) |
                             | created_at               |
                             +--------------------------+
                                         ^
                                         |
+------------------+         +-----------+--------------+
|      events      |         |        deliveries        |
+------------------+         +--------------------------+
| id (PK)          | <-----+ | id (PK)                  |
| tenant_id (FK)   |         | event_id (FK)            |
| event_id_ext (UQ)|         | endpoint_id (FK)         |
| type             |         | tenant_id (FK)           |
| payload (JSONB)  |         | status (PENDING/DELIV..) |
| created_at       |         | attempt_count            |
+------------------+         | next_attempt_at          |
                             | locked_by / locked_until |
                             +--------------------------+
                                         ^
                                         |
                             +-----------+--------------+
                             |    delivery_attempts     |
                             +--------------------------+
                             | id (PK)                  |
                             | delivery_id (FK)         |
                             | attempt_number           |
                             | response_code            |
                             | latency_ms               |
                             | error / snippet          |
                             | created_at               |
                             +--------------------------+
```

### Partial Index for Pending Delivery Claiming
```sql
CREATE INDEX idx_deliveries_pending_claim
ON deliveries (status, next_attempt_at)
WHERE status = 'PENDING';
```
This partial index enables PostgreSQL to locate pending deliveries instantly without scanning completed or dead-lettered records.
