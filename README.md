# Reliable Multi-Tenant Webhook Delivery Service

A high-throughput, fault-tolerant, multi-tenant webhook delivery engine built with **Java 21 Virtual Threads**, **Spring Boot 3.4**, and **PostgreSQL 16**.

Designed for enterprise reliability, strict tenant isolation, scalable concurrency, exponential retry backoff with jitter, dead-lettering, circuit breaking, and comprehensive audit visibility.

---

## Key Architecture & Features

```
                   +---------------------------------------+
                   |           API Clients / Tenants       |
                   +---------------------------------------+
                                       |
                                       v  (X-Tenant-Id, Headers)
+-----------------------------------------------------------------------------------+
|                            Webhook Delivery Service                               |
|                                                                                   |
|  +--------------------+     +---------------------+     +----------------------+  |
|  | TenantInterceptor  | --> | EventIngestionService| -->|  RateLimiterService  |  |
|  | (Header & DB check)|     | (Idempotency check) |     |  (Token Bucket)      |  |
|  +--------------------+     +---------------------+     +----------------------+  |
|                                        |                                          |
|                                        v (Fan-out to Active Endpoints)            |
|                             +--------------------+                                |
|                             | PostgreSQL DB      |                                |
|                             | (deliveries table) |                                |
|                             +--------------------+                                |
|                                   ^        ^                                      |
|            (SELECT FOR UPDATE     |        |   (Release Stale Locks)              |
|             SKIP LOCKED)          |        |                                      |
|                                   v        v                                      |
|  +--------------------+     +---------------------+     +----------------------+  |
|  | DeliveryClaimService| --> | DeliveryDispatch    | -->| CrashRecoveryService |  |
|  | (Virtual Threads)  |     | Service (HMAC-SHA)  |     | (30s Lease Cleanup)  |  |
|  +--------------------+     +---------------------+     +----------------------+  |
|                                        |                                          |
|                                        v                                          |
|                             +---------------------+                               |
|                             | CircuitBreaker      |                               |
|                             | Service             |                               |
|                             +---------------------+                               |
+----------------------------------------|------------------------------------------+
                                         v
                     +---------------------------------------+
                     |     Target Webhook Receivers          |
                     +---------------------------------------+
```

### 1. High Throughput & Concurrency Model
- **Java 21 Virtual Threads**: Handles thousands of concurrent outbound HTTP dispatches with negligible OS thread overhead.
- **Lock-Free DB Claiming**: Uses `SELECT ... FOR UPDATE SKIP LOCKED` natively in PostgreSQL. Multiple worker nodes claim non-overlapping batches of pending webhooks concurrently without database contention or race conditions.

### 2. Multi-Tenancy & Hardening (FR-1, FR-5)
- **Tenant Context Interceptor**: Resolves and validates `X-Tenant-Id` header against the `tenants` table.
- **Per-Tenant Token Bucket Rate Limiting**: Prevents noisy neighbors from overwhelming system resources. Excess requests return `429 Too Many Requests` with a `Retry-After` header.
- **SSRF Protection**: `UrlValidationService` blocks registration of internal/private IPs (e.g. `127.0.0.1`, `10.0.0.0/8`, `169.254.169.254`) and non-HTTP/HTTPS schemes.

### 3. Event Ingestion & Fan-out (FR-2)
- **Idempotent Ingestion**: PostgreSQL unique constraint on `(tenant_id, event_id_external)` guarantees duplicate events return `202 Accepted` with existing delivery metadata.
- **Automatic Fan-Out**: Single event automatically creates individual `PENDING` delivery rows for all active tenant endpoints subscribed to the event type (supports wildcard `*`).

### 4. Security & Cryptographic Signing (FR-3, FR-7)
- **HMAC-SHA256 Signatures**: Each payload is signed using the endpoint's unique secret (`whsec_...`).
- **Signature Headers**:
  - `X-Webhook-Signature: t=<timestamp>,v1=<hmac_sha256_hex>`
  - `X-Webhook-Timestamp: <timestamp>`
  - `X-Webhook-Event-Type: <event_type>`
  - `X-Webhook-Delivery-Id: <delivery_id>`

### 5. Resilience & Fault Tolerance (FR-3, FR-7)
- **Exponential Backoff with Jitter**: Next retry delay calculated as `base * 2^(attempt-1)` with 50% equal jitter to prevent thundering herds.
- **Dead-Letter Queue (DLQ)**: Deliveries exceeding max attempts (default 8) automatically transition to `DEAD_LETTERED`.
- **Manual Redrive API**: `POST /api/v1/deliveries/{id}/redrive` resets `DEAD_LETTERED` or `FAILED` deliveries to `PENDING` for immediate retry.
- **Endpoint Circuit Breaker**: Tracks consecutive failures. After threshold (default 5), circuit transitions to `OPEN` for a 60-second cooldown period, avoiding unnecessary outbound traffic to unhealthy receivers.
- **Crash Recovery & Stale Lock Cleanup**: Periodic background cleanup service (`CrashRecoveryService`) clears abandoned worker leases (`locked_until < NOW()`), ensuring zero lost deliveries if a worker crashes mid-flight.

### 6. Endpoint Self-Test (FR-6)
- **Ping Test API**: `POST /api/v1/endpoints/{id}/test` dispatches a synchronous signed ping event to verify target endpoint connectivity.

### 7. Delivery Visibility & Auditing (FR-4)
- **Paginated Filtering**: `GET /api/v1/deliveries` supports pagination and dynamic specification filtering by `status`, `endpointId`, and `eventId`.
- **Attempt History Logs**: `GET /api/v1/deliveries/{id}/attempts` returns execution history including response status codes, latency in ms, and error snippets.

### 8. Observability & Monitoring (FR-8)
- **Micrometer & Prometheus Metrics**: Custom metrics available at `/actuator/prometheus`:
  - `webhook.events.ingested.total` (Counter tagged by `tenant_id`, `event_type`)
  - `webhook.events.duplicate.total` (Counter tagged by `tenant_id`)
  - `webhook.deliveries.dispatched.total` (Counter tagged by `tenant_id`, `status`)
  - `webhook.delivery.latency` (Timer tagged by `tenant_id`, `status_code`)
- **Health Check**: `/actuator/health`

---

## Project Structure

```
webhook-delivery-service-assignment/
├── backend/
│   ├── src/main/java/com/webhook/delivery/
│   │   ├── config/             # Spring & Web MVC Configuration
│   │   ├── controller/         # REST Controllers (Endpoints, Events, Deliveries)
│   │   ├── domain/             # JPA Entities (Tenant, Endpoint, WebhookEvent, Delivery, DeliveryAttempt)
│   │   ├── dto/                # Request/Response DTOs
│   │   ├── repository/         # JPA & Native Query Repositories
│   │   ├── security/           # TenantContext & TenantInterceptor
│   │   └── service/            # Core Business Services (Ingestion, Dispatch, Claim, CircuitBreaker, Backoff, Metrics)
│   ├── src/main/resources/
│   │   ├── db/migration/       # Flyway Database Migrations (V1..V4)
│   │   └── application.yml     # Service Configuration Properties
│   ├── src/test/java/com/webhook/delivery/  # Integration Tests (Testcontainers + WireMock)
│   ├── Dockerfile
│   ├── docker-compose.yml
│   └── pom.xml
└── README.md
```

---

## Getting Started

### Prerequisites
- **Java 21** JDK installed
- **Docker** & **Docker Compose** installed

---

### Running via Docker Compose

To start the complete environment (PostgreSQL + Webhook Service):

```bash
cd backend
docker-compose up --build
```

The service will start on port `8080`.

Verify health check:
```bash
curl http://localhost:8080/actuator/health
```

---

### Running Locally with Maven

1. Start PostgreSQL:
```bash
docker run -d --name postgres-webhook \
  -e POSTGRES_DB=webhook_db \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=postgres \
  -p 5432:5432 postgres:16-alpine
```

2. Run Spring Boot Application:
```bash
cd backend
./mvnw spring-boot:run
```

---

### Running the Integration Test Suite

The test suite utilizes **Testcontainers** (PostgreSQL 16) and **WireMock** for end-to-end verification without requiring external services.

Run all tests:
```bash
cd backend
./mvnw test
```

---

## API Endpoints Reference

All `/api/v1/*` endpoints require the `X-Tenant-Id` HTTP header.

### 1. Endpoint Management

#### Register Endpoint
```bash
curl -X POST http://localhost:8080/api/v1/endpoints \
  -H "X-Tenant-Id: default-tenant" \
  -H "Content-Type: application/json" \
  -d '{
    "url": "https://httpbin.org/post",
    "subscribedEventTypes": ["order.created", "invoice.paid"]
  }'
```

#### List Endpoints
```bash
curl -X GET http://localhost:8080/api/v1/endpoints \
  -H "X-Tenant-Id: default-tenant"
```

#### Ping Test Endpoint
```bash
curl -X POST http://localhost:8080/api/v1/endpoints/{endpointId}/test \
  -H "X-Tenant-Id: default-tenant"
```

---

### 2. Event Ingestion

#### Ingest Webhook Event
```bash
curl -X POST http://localhost:8080/api/v1/events \
  -H "X-Tenant-Id: default-tenant" \
  -H "Content-Type: application/json" \
  -d '{
    "eventId": "evt_987654",
    "type": "order.created",
    "payload": {
      "orderId": "ord_1001",
      "amount": 99.99,
      "currency": "USD"
    }
  }'
```

---

### 3. Delivery Visibility & Manual Operations

#### List Deliveries (Paginated & Filtered)
```bash
curl -X GET "http://localhost:8080/api/v1/deliveries?status=DELIVERED&page=0&size=20" \
  -H "X-Tenant-Id: default-tenant"
```

#### Get Delivery Attempt Logs
```bash
curl -X GET http://localhost:8080/api/v1/deliveries/{deliveryId}/attempts \
  -H "X-Tenant-Id: default-tenant"
```

#### Redrive Dead-Lettered Delivery
```bash
curl -X POST http://localhost:8080/api/v1/deliveries/{deliveryId}/redrive \
  -H "X-Tenant-Id: default-tenant"
```

---

### 4. Observability Metrics

#### Prometheus Metrics Endpoint
```bash
curl http://localhost:8080/actuator/prometheus
```

---

## Verification & Test Results

All **21 unit & integration tests** are fully passing:
- `WebhookDeliveryApplicationTests`
- `EndpointIntegrationTest`
- `EventIngestionIntegrationTest`
- `DeliveryWorkerConcurrencyIntegrationTest`
- `BackoffCalculatorTest`
- `DeliveryRetryIntegrationTest`
- `DeliveryVisibilityIntegrationTest`
- `TenantHardeningIntegrationTest`
- `EndpointSelfTestIntegrationTest`
- `CircuitBreakerAndRecoveryIntegrationTest`
- `ObservabilityIntegrationTest`

---

## License
Apache License 2.0
