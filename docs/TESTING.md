# Webhook Delivery Service — Testing & Verification Guide

This document provides a comprehensive, developer-friendly guide to the automated test suite of the Webhook Delivery Service (`backend/src/test`). It covers the testing philosophy, setup instructions, test suite classification, scenario mapping, and code coverage metrics.

---

## 1. Overview & Testing Philosophy

The test suite is designed to ensure **production readiness**, **tenant isolation**, and **fault tolerance** without relying on external network dependencies or mock databases.

### Core Principles
- **Real Database Integration**: Tests run against a real PostgreSQL 16 instance via **Testcontainers**. In-memory databases like H2 are explicitly avoided to guarantee compatibility with PostgreSQL-specific features (such as `SELECT ... FOR UPDATE SKIP LOCKED` and JSONB queries).
- **Hermetic & Offline Outbound Calls**: Outbound HTTP requests to client webhooks are stubbed using **WireMock**. No actual external network calls are performed during testing.
- **Concurrency & Race Condition Validation**: Tests use multi-threaded execution to simulate parallel worker processes claiming deliveries simultaneously.
- **High Code Coverage**: Automated code coverage tracking with **JaCoCo** ensures key business logic maintains high test coverage (exceeding the project floor of 60%).

---

## 2. Test Tech Stack

| Component | Technology | Purpose |
|---|---|---|
| **Test Runner & Framework** | JUnit 5 (Jupiter) | Core test structure and lifecycle management |
| **Assertions** | AssertJ | Fluent, readable assertions |
| **Application Context** | Spring Boot Test (`@SpringBootTest`) | Full context loading with `RANDOM_PORT` web environment |
| **Database Containerization** | Testcontainers (`PostgreSQLContainer`) | Production-identical PostgreSQL 16 container lifecycle |
| **HTTP Webhook Mocking** | WireMock (`WireMockServer`) | Mocking downstream webhook destination servers |
| **Code Coverage** | JaCoCo Maven Plugin | Code coverage analysis and HTML reporting |

---

## 3. How to Run the Tests

### Execute the Full Test Suite

Navigate to the `backend` directory and run:

```bash
./mvnw test
```

### Run a Specific Test Class

```bash
./mvnw test -Dtest=ConcurrencyClaimTest
```

### Run a Specific Test Method

```bash
./mvnw test -Dtest=DeliveryRetryIntegrationTest#testDeadLetteringAndManualRedrive
```

### Generate and View Code Coverage Reports

JaCoCo generates code coverage reports automatically during the `./mvnw test` execution.

- **Report Path**: `backend/target/site/jacoco/index.html`
- **View in Browser**: Open `backend/target/site/jacoco/index.html` in your web browser.

---

## 4. Test Suite Structure & Breakdown

The test suite consists of **13 test classes** containing **21 test methods** organized into logical functional areas:

```
backend/src/test/java/com/webhook/delivery/
├── BackoffCalculatorTest.java
├── CircuitBreakerAndRecoveryIntegrationTest.java
├── ConcurrencyClaimTest.java
├── DeliveryRetryIntegrationTest.java
├── DeliveryVisibilityIntegrationTest.java
├── DeliveryWorkerIntegrationTest.java
├── EndpointIntegrationTest.java
├── EndpointSelfTestIntegrationTest.java
├── EventIngestionIntegrationTest.java
├── ObservabilityIntegrationTest.java
├── SignatureServiceTest.java
├── TenantHardeningIntegrationTest.java
└── WebhookDeliveryApplicationTests.java
```

### Category 1: Unit Tests (Fast & Isolated)

1. **`SignatureServiceTest`**
   - **Target**: `SignatureService`
   - **Description**: Verifies HMAC-SHA256 signature calculation for webhook payloads.
   - **Key Assertions**: Checks header structure (`t={timestamp},v1={hash}`), timestamp inclusion, and signature length.

2. **`BackoffCalculatorTest`**
   - **Target**: `BackoffCalculator`
   - **Description**: Tests exponential backoff calculation logic with jitter.
   - **Key Assertions**: Ensures delay increases exponentially per attempt and respects maximum retry bounds.

---

### Category 2: API & Ingestion Tests

3. **`EndpointIntegrationTest`**
   - **Target**: `EndpointController`, `EndpointService`
   - **Description**: Validates endpoint registration, retrieval, SSRF protection, and tenant validation.
   - **Key Assertions**: Rejects internal/private IP URLs (`127.0.0.1`, `10.x.x.x`), verifies `X-Tenant-Id` requirement, and checks cross-tenant isolation.

4. **`EventIngestionIntegrationTest`**
   - **Target**: `EventIngestionController`, `EventIngestionService`
   - **Description**: Tests event ingestion and fan-out delivery record creation.
   - **Key Assertions**: Confirms duplicate event submissions (`eventId`) are safely ignored (idempotency) and delivery records are created for matching endpoints.

5. **`EndpointSelfTestIntegrationTest`**
   - **Target**: Endpoint Ping API (`/api/v1/endpoints/{id}/test`)
   - **Description**: Verifies the self-test ping functionality against responsive and failing downstream servers (via WireMock).
   - **Key Assertions**: Validates ping status responses for `200 OK`, timeouts, and disabled endpoints.

---

### Category 3: Worker, Retries & Concurrency Tests

6. **`DeliveryWorkerIntegrationTest`**
   - **Target**: `DeliveryWorker`, `DeliveryDispatchService`
   - **Description**: Tests end-to-end background delivery execution.
   - **Key Assertions**: Confirms worker claims pending deliveries, dispatches HTTP requests, signs payloads, and marks status as `DELIVERED`.

7. **`ConcurrencyClaimTest`**
   - **Target**: `DeliveryClaimService` (`SELECT ... FOR UPDATE SKIP LOCKED`)
   - **Description**: Simulates high-concurrency delivery claiming across 10 parallel threads processing 50 deliveries.
   - **Key Assertions**: Guarantees zero duplicate claims across concurrent workers (each delivery is claimed by exactly one thread).

8. **`DeliveryRetryIntegrationTest`**
   - **Target**: Retry Engine & Redrive API
   - **Description**: Validates failed delivery retries, backoff scheduling, dead-lettering (`DEAD_LETTERED`), and manual redrive requests.
   - **Key Assertions**: Verifies state transition to `DEAD_LETTERED` after maximum attempt limit and successful redrive resets retry counters.

---

### Category 4: Reliability & Fault Tolerance Tests

9. **`CircuitBreakerAndRecoveryIntegrationTest`**
   - **Target**: `CircuitBreakerService`, `CrashRecoveryService`
   - **Description**: Tests circuit breaking for failing endpoints and recovery of stale delivery leases.
   - **Key Assertions**:
     - Endpoint circuit opens (`OPEN`) after repeated 5xx errors, pausing further dispatches.
     - Stale locks from crashed workers (`locked_until < now`) are released by `CrashRecoveryService`.

10. **`DeliveryVisibilityIntegrationTest`**
    - **Target**: `DeliveryQueryController`
    - **Description**: Tests delivery log inspection, status filtering, and attempt detail pagination.
    - **Key Assertions**: Verifies filtering by status (`DELIVERED`, `FAILED`, `DEAD_LETTERED`) and correct attempt history records.

---

### Category 5: Security & Observability Tests

11. **`TenantHardeningIntegrationTest`**
    - **Target**: `TenantFilter`, `TenantRateLimiterService`
    - **Description**: Tests security controls, missing header rejection, invalid tenant lookup, and rate limiting.
    - **Key Assertions**: Returns `401 Unauthorized` for invalid tenants and `429 Too Many Requests` when tenant rate limits are exceeded.

12. **`ObservabilityIntegrationTest`**
    - **Target**: Actuator & Micrometer Metrics
    - **Description**: Checks health endpoints and Prometheus metrics exposition.
    - **Key Assertions**: `/actuator/health` returns `200 OK` UP status, and `/actuator/prometheus` exposes webhook delivery counters.

13. **`WebhookDeliveryApplicationTests`**
    - **Target**: Spring Boot Context
    - **Description**: Verifies that the Spring Boot application context loads cleanly with Testcontainers PostgreSQL connection.

---

## 5. Scenario Verification Matrix

The test suite explicitly validates key non-functional and evaluation requirements:

| Scenario / Requirement | Verified By Test Class | Key Mechanics Validated |
|---|---|---|
| **1. Normal Webhook Delivery** | `DeliveryWorkerIntegrationTest` | Ingestion $\rightarrow$ WireMock HTTP 200 $\rightarrow$ `DELIVERED` status update |
| **2. Duplicate Event Submission** | `EventIngestionIntegrationTest` | Unique DB constraint on `(tenant_id, event_id_external)` enforces idempotency |
| **3. Retry & Dead-Lettering** | `DeliveryRetryIntegrationTest` | Exponential backoff scheduling $\rightarrow$ `DEAD_LETTERED` state $\rightarrow$ Manual redrive |
| **4. Concurrency Safety** | `ConcurrencyClaimTest` | 10 concurrent threads claiming 50 records via `SKIP LOCKED` yield 0 overlap |
| **5. Circuit Breaker** | `CircuitBreakerAndRecoveryIntegrationTest` | Consecutive failure threshold opens circuit, preventing request overload |
| **6. Crash Recovery** | `CircuitBreakerAndRecoveryIntegrationTest` | `CrashRecoveryService` resets expired worker locks (`locked_until < now`) |
| **7. SSRF Security Protection** | `EndpointIntegrationTest` | Requests targeting private IPs (`127.0.0.1`, `10.0.0.1`) are rejected |
| **8. Multi-Tenant Isolation** | `TenantHardeningIntegrationTest` & `EndpointIntegrationTest` | Cross-tenant record access yields `404 Not Found`; invalid tenants yield `401` |
| **9. Per-Tenant Rate Limiting** | `TenantHardeningIntegrationTest` | In-memory Bucket4j rate limiter yields `429 Too Many Requests` on bucket exhaustion |

---

## 6. Code Coverage Summary

The project maintains comprehensive test coverage across all core service classes:

- **Overall Service Line Coverage**: **86.9%** (Exceeds 60% requirement floor)
- **`DeliveryClaimService`**: 100%
- **`CrashRecoveryService`**: 100%
- **`EndpointService`**: 96.2%
- **`TenantRateLimiterService`**: 96.4%
- **`DeliveryDispatchService`**: 88.3%
- **`EventIngestionService`**: 81.0%

---

## 7. Guidelines for Adding New Tests

When adding new feature tests to `backend/src/test`:

1. **Use Testcontainers for DB Integration**: Annotate integration test classes with `@SpringBootTest` and `@Testcontainers`, using `@ServiceConnection static PostgreSQLContainer<?> postgres`.
2. **Use WireMock for HTTP Endpoints**: Always mock downstream endpoint responses using `WireMockServer` rather than making outbound calls.
3. **Include Tenant Context**: Ensure HTTP requests in tests set the `X-Tenant-Id` header (e.g. `headers.set("X-Tenant-Id", "tenant-123")`).
4. **Assert Database Cleanliness**: Clean up created records in `@BeforeEach` or `@AfterEach` if tests are interdependent.
