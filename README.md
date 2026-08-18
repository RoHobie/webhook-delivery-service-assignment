# Multi-Tenant Webhook Delivery Service

A reliable, production-ready, high-performance webhook delivery service built with **Java 21 Virtual Threads**, **Spring Boot 4.x** (`4.0.0-M1`), and **PostgreSQL 16**, complete with an interactive web-based **Control Dashboard**.

This service enables multi-tenant applications to register webhook endpoints, ingest events idempotently, and deliver payloads reliably. It supports automatic retries with exponential backoff and jitter, per-tenant rate limiting, HMAC-SHA256 signature signing, circuit breaking for unstable endpoints, and crash recovery.

---

## Interactive Control Dashboard

The application includes a modern, dark-themed **Vanilla JS Single Page Application (SPA)** statically served directly by Spring Boot:

- **Web Dashboard URL**: **`http://localhost:8080`**
- **Features**:
  - **Endpoints Management**: Register targets, view HMAC signing secrets, toggle endpoint status, and send synchronous ping self-tests.
  - **Event Ingestion & Idempotency**: Publish event payloads, generate UUIDs, and test duplicate submission handling (`HTTP 202` without duplicate delivery creation).
  - **Delivery Diagnostics & Audit Trail**: Real-time delivery logs, status filters, 3s auto-polling toggle, expandable retry attempt logs, and dead-letter manual redrive.
  - **Tenant Isolation Security Suite**: Interactive cross-tenant security runner verifying that Tenant B cannot inspect or redrive Tenant A's resources (`HTTP 404`).

---

## Project Documentation

For detailed technical design documents, API specifications, and architectural rationale, refer to the documentation in [`docs/`](docs/):

- **[System Architecture](docs/ARCHITECTURE.md)**: Covers the ingestion pipeline, fan-out matching, database-level claim concurrency engine (`FOR UPDATE SKIP LOCKED`), virtual thread dispatching, and crash recovery.
- **[REST API Reference & Webhook Verification Guide](docs/API_DOCUMENTATION.md)**: Contains complete API endpoint specifications, JSON request/response examples, and consumer signature verification code snippets in Java, Node.js, and Python.
- **[Design Decisions & Rationale](docs/DESIGN_DECISIONS.md)**: Explains the architectural trade-offs, engineering choices, and alignment with non-functional requirements.
- **[Testing & Verification Guide](docs/TESTING.md)**: Details the test suite architecture, Testcontainers PostgreSQL setup, WireMock HTTP stubs, concurrency testing, and scenario evaluation matrix.

---

## Quick Start Guide

### Prerequisites
- **Java 21** JDK (LTS)
- **Docker** and **Docker Compose** (V2 recommended)
- **Git**

---

### Step 1: Clone & Navigate

```bash
git clone https://github.com/RoHobie/webhook-delivery-service-assignment.git
cd webhook-delivery-service-assignment
```

---

### Step 2: Run with Docker Compose (Recommended)

Start PostgreSQL 16 and the Spring Boot 4.x backend service together using Docker Compose.
```bash
cd backend
docker compose up --build
```

#### Services & Container Ports Configuration
The `docker-compose.yml` file configures two services:
- **`postgres`** (`webhook_postgres`): PostgreSQL 16 Alpine
  - **Database**: `webhook_db` | **User**: `webhook_user` | **Password**: `webhook_pass`
  - **Internal Port**: `5432` | **Host Port Mapping**: `5435:5432` (accessible on host at `localhost:5435`)
- **`app`** (`webhook_app`): Spring Boot 4.x Application (built via multi-stage `Dockerfile`)
  - **Internal & Host Port**: `8080:8080`
  - **Environment**: `DB_URL=jdbc:postgresql://postgres:5432/webhook_db`, `DB_USER=webhook_user`, `DB_PASSWORD=webhook_pass`, `ALLOW_INTERNAL_URLS=true`

#### Service Access
- **Control Dashboard**: Access **`http://localhost:8080`** in your browser.
- **REST API Base**: `http://localhost:8080/api/v1`
- **Health Check Endpoint**: `curl http://localhost:8080/actuator/health`

To stop the containers:
```bash
# From repository root:
docker compose -f backend/docker-compose.yml down

# Or inside backend/:
docker compose down
```

---

### Step 3: Standalone Docker Build & Execution

To build and run the backend Docker container individually:

1. **Build the Docker Image** (from the `backend/` directory):
```bash
cd backend
docker build -t webhook-delivery-service .
```

2. **Run the Container** (connecting to a running PostgreSQL instance):
```bash
docker run -p 8080:8080 \
  -e DB_URL=jdbc:postgresql://host.docker.internal:5432/webhook_db \
  -e DB_USER=webhook_user \
  -e DB_PASSWORD=webhook_pass \
  -e ALLOW_INTERNAL_URLS=true \
  webhook-delivery-service
```

---

### Step 4: Run Locally for Development (Maven + Local DB)

To run the Spring Boot application using Maven while running PostgreSQL in Docker:

#### Option A: Connect to Docker Compose PostgreSQL
1. Start the PostgreSQL service only from the `backend/` directory:
```bash
cd backend
docker compose up postgres -d
```
2. Launch the Spring Boot application connecting to PostgreSQL on host port `5435`:
```bash
DB_URL=jdbc:postgresql://localhost:5435/webhook_db DB_USER=webhook_user DB_PASSWORD=webhook_pass ./mvnw spring-boot:run
```

#### Option B: Connect to Standalone PostgreSQL Container (Default Port 5432)
1. Start a standalone PostgreSQL container with default credentials:
```bash
docker run -d --name postgres-webhook \
  -e POSTGRES_DB=webhook_db \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=postgres \
  -p 5432:5432 postgres:16-alpine
```

2. Launch the application (uses default `application.yml` database settings):
```bash
cd backend
./mvnw spring-boot:run
```

---

### Step 5: Run the Integration Test Suite

Execute the automated test suite (uses **Testcontainers** for PostgreSQL integration and **WireMock** for outbound HTTP stubs). Docker daemon must be running.

```bash
cd backend
./mvnw test
```

> **Code Coverage Report**: Automatically generated during `./mvnw test` at `backend/target/site/jacoco/index.html`.

---

## API Quick Reference

All API requests require the `X-Tenant-Id` header for tenant context.

### 1. Register a Webhook Endpoint
```bash
curl -X POST http://localhost:8080/api/v1/endpoints \
  -H "X-Tenant-Id: default-tenant" \
  -H "Content-Type: application/json" \
  -d '{
    "url": "https://httpbin.org/post",
    "subscribedEventTypes": ["order.created", "user.signup"]
  }'
```

### 2. Ingest an Event (Idempotent)
```bash
curl -X POST http://localhost:8080/api/v1/events \
  -H "X-Tenant-Id: default-tenant" \
  -H "Content-Type: application/json" \
  -d '{
    "eventId": "evt_12345",
    "type": "order.created",
    "payload": {
      "orderId": "ord_99",
      "amount": 49.99
    }
  }'
```

### 3. Query Delivery Status
```bash
curl -X GET "http://localhost:8080/api/v1/deliveries?status=DELIVERED" \
  -H "X-Tenant-Id: default-tenant"
```

### 4. Inspect Delivery Attempts
```bash
curl -X GET http://localhost:8080/api/v1/deliveries/{deliveryId}/attempts \
  -H "X-Tenant-Id: default-tenant"
```

### 5. Redrive a Dead-Lettered Delivery
```bash
curl -X POST http://localhost:8080/api/v1/deliveries/{deliveryId}/redrive \
  -H "X-Tenant-Id: default-tenant"
```

### 6. Test Endpoint Connectivity (Ping)
```bash
curl -X POST http://localhost:8080/api/v1/endpoints/{endpointId}/test \
  -H "X-Tenant-Id: default-tenant"
```

---

## Architecture & Verification Matrix

### Code Coverage
- **Service Layer Line Coverage**: **86.9%** (Exceeds required **60%** minimum floor).
- **Service Class Breakdown**: `DeliveryDispatchService` (88.3%), `EventIngestionService` (81.0%), `EndpointService` (96.2%), `TenantRateLimiterService` (96.4%), `DeliveryClaimService` (100%), `CrashRecoveryService` (100%).

### Evaluation Scenario Coverage

| Scenario | Test Class & Method | Verified Behavior |
|---|---|---|
| 1. Normal delivery | `DeliveryWorkerIntegrationTest.testSuccessfulDeliveryWorkerFlow` | Event ingested, payload signed, HTTP 200 received, status set to `DELIVERED`. |
| 2. Duplicate event submission | `EventIngestionIntegrationTest.eventIngestionAndIdempotency` | Unique constraint on `(tenant_id, event_id_external)` prevents duplicate delivery records. |
| 3. Continuous HTTP 500 endpoint | `DeliveryRetryIntegrationTest.testDeadLetteringAndManualRedrive` | Retries with exponential backoff + jitter, transitions to `DEAD_LETTERED` after 8 attempts. |
| 4. Slow / hanging endpoint | `DeliveryWorkerIntegrationTest` & `OutboundHttpClientTest` | Enforces 3s connect / 5s read timeouts; Virtual Threads prevent thread starvation. |
| 5. Concurrent workers racing claim | `ConcurrencyClaimTest.concurrentWorkersNeverClaimSameDeliveryTwice` | 10 concurrent threads claiming 50 deliveries under `SKIP LOCKED` yield zero overlap. |
| 6. Worker crash mid-lease | `CircuitBreakerAndRecoveryIntegrationTest.testCrashRecoveryStaleLockRelease` | `CrashRecoveryService` clears expired leases (`locked_until < now`), enabling reclaim. |
| 7. Cross-tenant access attempts | `EndpointIntegrationTest.tenantIsolationOnEndpoint` & `TenantHardeningIntegrationTest` | Cross-tenant access to endpoints, redrives, or logs returns `404` or `401`. |

---

## Known Limitations

1. **Database Polling vs Distributed Event Streaming**:
   - Deliveries are polled from PostgreSQL using `SELECT ... FOR UPDATE SKIP LOCKED`.
   - *Impact*: Suitable for tens of thousands of deliveries per minute. High event volumes may increase DB CPU utilization.
   - *Mitigation*: Introduce a message streaming engine (e.g. Apache Kafka or AWS SQS) for event dispatch while using PostgreSQL for audit logging and DLQ storage.

2. **Fixed Dispatch Polling Interval**:
   - The worker pool polls for pending deliveries at a fixed 1-second interval (`fixedDelay = 1000ms`).

---

## License
Apache License 2.0

