# Reliable Webhook Delivery Service — Engineering Assignment

**Level:** Mid-level backend engineer
**Stack:** Java 21+ · Spring Boot 4.x · PostgreSQL
**Effort:** ~5 focused days full-time, or 10 days part-time
**Deliverable:** running service + Git repo + README + 5-minute demo
**Version:** 1.0

---

## 1. Why we're asking you to build this

This is a real problem, not a puzzle.

Every SaaS platform we build eventually needs to notify other systems when something happens — an order is paid, a subscription is cancelled, a document finishes processing. The receiving endpoint is somebody else's server: it will be slow, it will time out, it will return 500s during a deploy, and it will occasionally disappear entirely. Customers still expect the event to arrive, exactly once, in order, with proof it arrived.

You are building the backend service that accepts events from internal producers and reliably delivers them as HTTP webhooks to tenant-configured endpoints — with retries, backoff, signing, and full delivery visibility.

Backend only. A minimal HTML page or Swagger UI is enough to demo it.

## 2. What we're actually evaluating

Calling `HttpClient.send()` in a loop is an afternoon's work. That is not what this assignment is about.

We are looking at four things:

1. **Does it actually guarantee at-least-once delivery, not "usually"?** A webhook that silently drops on a server restart is worse than no webhook system at all.
2. **Does tenant isolation actually hold?** One tenant's endpoint, secret, or delivery history must never be reachable from another tenant's credentials.
3. **Is due-work selection done at the database, not in Java?** Loading every pending delivery into memory and filtering with `.stream()` is the most common shortcut and we will look for it.
4. **Does it survive contact with reality?** Slow endpoint, endpoint that hangs forever, endpoint that returns 200 after reading half the body, duplicate producer events, process restart mid-delivery.

Everything else — polish, breadth of features, stretch goals — matters far less than these four.

## 3. Priority order (read this before you plan your time)

If you run out of time, cut from the bottom. We would much rather see a small, correct, well-tested system than a wide, half-working one.

| Tier | Scope | If missing |
|---|---|---|
| 1 — Must work | Runs from a clean clone. Ingest event → persist → deliver → record outcome. Migrations. Tenant scoping. Signature verification path. | Not evaluable |
| 2 — Expected | Async delivery workers, idempotency on producer events, exponential backoff, dead-letter after max attempts, delivery log queryable per tenant, DB-level due-job selection with row locking | Significant point loss |
| 3 — Valued | Circuit breaker per endpoint, manual redrive of failed deliveries, correlation IDs, delivery latency metrics, configurable per-tenant retry policy | Moderate point loss |
| 4 — Bonus | Stretch goals in §11 | No penalty |

An honest gap costs you almost nothing. A silent one costs a lot. If you skip something or take a shortcut, say so in the README under **Known Limitations**, and say what you'd do instead with more time. That reads as engineering judgment. Discovering it ourselves reads as something else.

## 4. Ground rules

Ambiguity is deliberate in places. Where the spec doesn't decide something for you, make a call, write down the assumption in the README, and move on. Don't stall waiting for an answer. If something is genuinely blocking, ask — questions are not held against you.

On AI tools: use them. We do. But there will be a walkthrough where you explain your retry/backoff math, your locking strategy, your idempotency key design, and your test design. You need to be able to defend every decision in the repo as your own. Code you can't explain is worse than code you didn't write.

Time-box it. If you're well past the estimate, stop, write the README honestly, and submit what works. We'd rather see where you chose to stop than a rushed everything.

## 5. Tech constraints (non-negotiable)

| Item | Requirement |
|---|---|
| Language | Java 21 or 25 (LTS only) |
| Framework | Spring Boot 4.x |
| Datastore | PostgreSQL 16+ |
| Migrations | Flyway or Liquibase — no `ddl-auto=update` |
| Build | Maven or Gradle, wrapper committed |
| Run | `docker compose up` must start Postgres + the app with no manual steps |
| HTTP client | Any (Java `HttpClient`, WebClient, OkHttp) — must have connect/read timeouts configured explicitly |
| Signing | HMAC-SHA256 over the raw request body, secret per tenant, read from environment/DB — never hardcoded |

A committed secret or API key is an automatic fail.

## 6. Functional requirements

### FR-1 — Register a webhook endpoint
- `POST /api/v1/endpoints` registers a target URL, event types subscribed to, and generates/stores a per-endpoint signing secret.
- `GET /api/v1/endpoints`, `GET /api/v1/endpoints/{id}`, `DELETE /api/v1/endpoints/{id}` (soft-disable, stop delivering, keep history).
- URL must be validated (reject non-http(s), reject localhost/private-IP ranges unless a `allow_internal` dev flag is set).

### FR-2 — Ingest an event
- `POST /api/v1/events` accepts `{ "eventId": "...", "type": "invoice.paid", "payload": {...} }`.
- `eventId` is producer-supplied. Re-submitting the same `eventId` for the same tenant must **not** create a duplicate delivery — return the existing event's status instead of re-ingesting.
- Returns `202 Accepted` immediately with an internal event ID. Fan-out to matching endpoint subscriptions and delivery happen asynchronously.
- Unknown/malformed payload types are rejected with `400`, not silently accepted.

### FR-3 — Delivery pipeline
- For each matching endpoint subscription, create a `delivery` record in `PENDING` state.
- A worker pool (virtual threads or a bounded executor) claims due deliveries **at the database** using row-level locking (e.g. `SELECT ... FOR UPDATE SKIP LOCKED`) — no polling all rows into Java and filtering.
- POST the signed payload with `X-Webhook-Signature` and `X-Webhook-Timestamp` headers.
- Success = 2xx within the configured timeout. Anything else (4xx, 5xx, timeout, connection refused) is a failure.
- On failure: schedule a retry with exponential backoff + jitter, up to a configurable max attempt count (default 8, ~ over 24h).
- After max attempts, mark the delivery `DEAD_LETTERED` and stop retrying automatically.
- `POST /api/v1/deliveries/{id}/redrive` manually re-queues a dead-lettered delivery.

### FR-4 — Delivery visibility
- `GET /api/v1/events/{id}/deliveries` — every delivery attempt for an event: endpoint, status, HTTP status code returned, attempt number, timestamp, response snippet (truncated, no full body logging of potentially sensitive payloads).
- `GET /api/v1/endpoints/{id}/deliveries` — paginated, filterable by status and date range, **filtered at the database**, not in application code.

### FR-5 — Multi-tenancy
- Every request carries a tenant identifier (header `X-Tenant-Id` is acceptable for this exercise).
- Endpoints, events, deliveries, and secrets are scoped by tenant.
- Tenant A must never be able to read tenant B's endpoint secret, trigger a redrive on tenant B's delivery, or see tenant B's delivery log — including via a crafted ID in the path. You will be tested on this specifically.

### FR-6 — Signature verification (inbound self-test)
- Provide `POST /api/v1/endpoints/{id}/test` that sends a synthetic test event to the endpoint and reports whether the receiver could be reached and returned 2xx — used to validate an endpoint at registration time without waiting for a real event.

### FR-7 — Resilience
- A single slow or hung endpoint must never block delivery to other endpoints or tenants — enforce per-attempt timeouts and a bounded worker pool.
- Repeated failures against the same endpoint should trip a circuit breaker that pauses new attempts to that endpoint for a cool-down window, rather than burning through retry budget instantly.
- Process restart mid-delivery must not lose or duplicate deliveries — a delivery claimed by a worker that crashes must become claimable again after a lease timeout.

### FR-8 — Observability
- Structured logs with a correlation ID that flows from event ingestion through every delivery attempt for that event.
- Per-delivery metrics: attempt count, latency, final outcome.
- `GET /actuator/health` reports DB connectivity and worker pool status.

## 7. Non-functional requirements

| # | Requirement |
|---|---|
| NFR-1 | Event ingestion (`POST /api/v1/events`) responds in under 100 ms regardless of how many endpoints subscribe to that event type. |
| NFR-2 | Due-delivery claiming scales to 100k pending deliveries without a full table scan — an index must exist supporting the claim query. |
| NFR-3 | No HTTP request thread blocks on an outbound webhook call. |
| NFR-4 | A dead or extremely slow tenant endpoint returns a clean failure state; it must not degrade delivery to any other tenant. |
| NFR-5 | No signing secrets, full payload bodies, or PII in log output. |
| NFR-6 | Retry backoff must actually increase between attempts (verified in tests, not just configured). |

## 8. Suggested schema (adapt as needed)

```
tenants(id, name, created_at)
endpoints(id, tenant_id, url, secret, subscribed_event_types[], status, created_at)
events(id, tenant_id, event_id_external, type, payload, created_at)
  -- unique (tenant_id, event_id_external)
deliveries(id, event_id, endpoint_id, tenant_id, status, attempt_count,
           next_attempt_at, locked_by, locked_until, last_response_code,
           last_response_snippet, created_at, updated_at)
delivery_attempts(id, delivery_id, attempt_number, response_code,
                   latency_ms, error, created_at)
```

Unique constraint on `(tenant_id, event_id_external)`. Index supporting `WHERE status = 'PENDING' AND next_attempt_at <= now()` claim queries. Cascade delete from `events` to `deliveries` to `delivery_attempts`.

## 9. Testing requirements

- Unit tests on backoff calculation — boundary cases: attempt 0, attempt at max, jitter bounds.
- Integration tests using Testcontainers with a real Postgres image — not H2, not mocks.
- The outbound HTTP call is mocked or stubbed (e.g. WireMock) in tests. Tests must pass with no network access.
- At least one test asserting tenant isolation (endpoint secrets, delivery logs, redrive).
- At least one test asserting concurrent workers never claim the same delivery twice (`FOR UPDATE SKIP LOCKED` correctness).
- At least one test asserting duplicate `eventId` submission does not duplicate deliveries.
- Minimum 60% line coverage on service classes.

## 10. README requirements

The README is graded. Write it for the next engineer who has to extend this, not for us. It must contain:

1. How to run it in under five minutes from a clean clone.
2. Architecture diagram or clear description of the ingestion and delivery paths.
3. Your locking/claiming strategy and why.
4. Your backoff formula and retry limits, and how you chose them.
5. How you guarantee at-least-once (and where duplicates could still theoretically happen, if anywhere).
6. Known limitations and what you would do with two more weeks.
7. One thing that surprised you.

## 11. Stretch goals (bonus, max +10)

Only after Tiers 1–3 are solid.

- Per-tenant configurable retry policy (max attempts, backoff curve) stored in DB, not code.
- Delivery ordering guarantee per endpoint (strict FIFO) as an opt-in mode.
- Webhook payload versioning / schema evolution support.
- A replay tool: redeliver every event of a given type within a date range to a newly registered endpoint.
- Admin dashboard endpoint summarizing delivery success rate per tenant per endpoint over time.

## 12. How you'll be scored (100 points)

| Area | Points | What earns full marks |
|---|---|---|
| It runs | 10 | Clean clone → `docker compose up` → working demo, no hand-holding |
| Ingestion correctness | 15 | Idempotent on `eventId`, fast response, correct fan-out to subscribed endpoints |
| Delivery correctness | 20 | DB-level claim with locking, no double-delivery under concurrency, backoff actually increases, dead-letter after max attempts |
| Resilience | 15 | Timeouts enforced, circuit breaker works, slow endpoint doesn't block others, survives simulated crash mid-delivery |
| Multi-tenancy | 10 | No cross-tenant read/write reachable via any endpoint, enforced in schema and queries |
| Data model & migrations | 10 | Versioned migrations, sane constraints, cascade behaviour correct |
| Testing | 10 | Testcontainers integration tests, concurrency + isolation tests present, green on a fresh machine |
| Observability | 5 | Correlation IDs traceable end-to-end, useful health check |
| README & code clarity | 5 | Someone else can extend this without asking questions |

Your submission will be run against a fixed scenario set covering: normal delivery, duplicate event submission, endpoint that always 500s, endpoint that hangs, concurrent workers racing the same delivery, worker crash mid-lease, and cross-tenant access attempts. The same set is used for everyone. Build for correctness across that shape of scenario, not for any one demo path.

### Automatic fails, regardless of score
- Committed secret or credential.
- Tenant isolation breach reproducible in the evaluation.
- `ddl-auto=update` in place of migrations.
- Due-delivery selection done by loading all rows into Java and filtering there instead of at the database.
- Duplicate delivery of the same event to the same endpoint reproducible under normal (non-crash) operation.

## 13. Suggested milestones

Rough guide, not a contract. Reorder if it suits you.

| Day | Outcome |
|---|---|
| 1–2 | Project skeleton, Docker Compose with Postgres, migrations, health check |
| 3–4 | Endpoint registration, event ingestion with idempotency, fan-out to deliveries |
| 5–6 | Delivery worker with DB-level claiming, signing, timeouts |
| 7 | Backoff, dead-lettering, redrive |
| 8 | Multi-tenancy hardening, circuit breaker |
| 9 | Tests, metrics, error handling |
| 10 | README, demo, cleanup |

## 14. Submission

- Git repository with meaningful commit history. A single "initial commit" loses points under code clarity — we want to see how the thing was built, including the parts you reworked.
- `.env.example` listing every required variable.
- A 5-minute demo, recorded or live, covering: register an endpoint, send an event, show a successful delivery, simulate a failing endpoint and show retries, show a cross-tenant access attempt being rejected.
- Expect a follow-up walkthrough where we go through your claiming/locking logic and test design together.

**Submit to:** [add contact] **Due:** [add date] **Questions to:** [add contact]
