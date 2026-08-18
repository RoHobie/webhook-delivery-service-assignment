# Frontend Implementation Plan — Webhook Delivery Service Client

## Purpose

A modern, responsive Vanilla HTML/CSS/JS client (no external framework or build step required) that demonstrates and exercises the Webhook Delivery Service backend: registering endpoints, sending events, watching deliveries succeed or retry, viewing dead-letters, redriving them, testing endpoints, and proving tenant isolation.

This client lives in `project-root/frontend/` and is a separate deliverable from the backend service in `project-root/backend/`. It talks to the backend purely over HTTP — no shared code, no shared build tooling.

## Tech constraints

- Vanilla HTML5, CSS3, and JavaScript (ES Modules). No React/Vue/build pipeline needed.
- Modern CSS with custom properties (CSS variables), glassmorphism styling, dark theme, smooth micro-animations, and responsive layout.
- No inline secrets, no hardcoded production URLs — backend base URL and tenant IDs are configurable directly in the UI connection bar.
- Servable via any simple static file server (e.g., `python3 -m http.server`, `npx serve`, or direct file/http access).

## 1. API contract alignment (Synced with Backend Implementation)

All API requests (except `/actuator/health`) require the mandatory tenant header:
`X-Tenant-Id: <tenant-id>`

| Action | Method & Path | Details & Request/Response Shape |
|---|---|---|
| Register endpoint | `POST /api/v1/endpoints` | Body: `{ "url": "...", "subscribedEventTypes": ["order.created", ...] }`.<br>Response (`201`): `{ id, tenantId, url, secret, subscribedEventTypes, status, createdAt }`. (Secret shown once). |
| List endpoints | `GET /api/v1/endpoints` | Scoped to active tenant. Response: `[ EndpointResponse ]`. |
| Get endpoint | `GET /api/v1/endpoints/{id}` | Response: `EndpointResponse`. |
| Soft-delete endpoint | `DELETE /api/v1/endpoints/{id}` | Disables endpoint (`status: "DISABLED"`). Response: `EndpointResponse`. |
| Self-test endpoint | `POST /api/v1/endpoints/{id}/test` | Sends synthetic ping event. Response: `{ endpointId, success, statusCode, latencyMs, responseSnippet, error, testedAt }`. |
| Ingest event | `POST /api/v1/events` | Body: `{ "eventId": "evt_123", "type": "order.created", "payload": {...} }`.<br>Response (`202`): `{ id, eventId, status, deliveriesCreated, createdAt }`.<br>Resending identical `eventId` returns existing status (idempotency).<br>Returns `429` if tenant rate limit is exceeded. |
| List deliveries (Paginated) | `GET /api/v1/deliveries` | Query params: `endpointId`, `eventId`, `status`, `page` (default 0), `size` (default 20).<br>Response: `PageResponse<DeliveryResponse>` (`content`, `page`, `size`, `totalElements`, `totalPages`, `last`). |
| Get delivery details | `GET /api/v1/deliveries/{id}` | Response: `DeliveryResponse` (`id`, `eventId`, `endpointId`, `tenantId`, `status`, `attemptCount`, `lastResponseCode`, `lastResponseSnippet`, `nextAttemptAt`, `createdAt`, `updatedAt`). |
| Delivery attempt history | `GET /api/v1/deliveries/{id}/attempts` | Response: `List<DeliveryAttemptResponse>` (`id`, `deliveryId`, `attemptNumber`, `responseCode`, `latencyMs`, `error`, `createdAt`). |
| Redrive delivery | `POST /api/v1/deliveries/{id}/redrive` | Valid on `DEAD_LETTERED` deliveries. Resets status to `PENDING` with `attemptCount = 0`. |
| Health Check | `GET /actuator/health` | Public endpoint (no tenant header required). |

Delivery statuses: `PENDING`, `DELIVERED`, `DEAD_LETTERED`.

## 2. Interactive UI Sections

1. **Connection Bar & Header** (Persistent)
   - Backend Base URL input (defaults to `http://localhost:8080`).
   - Active Tenant ID selector/input (e.g. `tenant-alpha`, `tenant-beta`).
   - Live backend health status badge with auto-ping & manual ping button.
   - Quick action: Switch active tenant.

2. **Endpoints Panel**
   - Registration form: Target URL, tag-based or multi-select event type input, submit button.
   - Prominent modal/banner displaying the generated signing secret (`whsec_...`) with a 1-click copy button and warning.
   - Registered endpoints grid/table: URL, status badge (`ACTIVE`/`DISABLED`), subscribed events.
   - Row actions: "Test Ping" (synchronous self-test with latency & status output), "Disable", "View Deliveries" (filters Deliveries panel).

3. **Send Event & Idempotency Demo Panel**
   - Form fields: `eventId` (auto-generates UUID, editable), `type`, JSON payload editor with real-time format validation.
   - "Send Event" button: calls `/api/v1/events`, displays returned event ID and `deliveriesCreated` count.
   - "Send Duplicate" button: resubmits identical `eventId` to demonstrate idempotency without creating duplicate deliveries.
   - Rate limit warning handler for HTTP `429` responses.

4. **Deliveries & Diagnostics Panel**
   - Filters bar: Filter by Endpoint ID, Event ID, and Delivery Status (`PENDING`, `DELIVERED`, `DEAD_LETTERED`).
   - Live Delivery Log Table: ID, Event ID, Endpoint, Status badge, Attempts count, Last Code, Last Snippet, Created At.
   - Auto-refresh toggle (polls every 3s to track live delivery retries and status changes).
   - "Attempt History" expander: fetches `/api/v1/deliveries/{id}/attempts` to view timing and errors for each attempt.
   - "Redrive" action button on `DEAD_LETTERED` rows to re-trigger delivery attempt.

5. **Tenant Isolation Verification Suite**
   - Interactive cross-tenant security test panel:
   - Form allowing tester to attempt fetching endpoint or delivery details belonging to Tenant A while authenticated as Tenant B.
   - Live raw HTTP response viewer (status code + JSON body) demonstrating 404 / 401 isolation enforcement.

6. **Integrated Receiver Test Utility / Mock Webhook Endpoint**
   - Instructions & embedded receiver helper allowing users to test webhooks locally (using webhook.site, local http echo servers, or browser mock listeners).

## 3. Project Structure

```
frontend/
├── index.html          -- Single page structure & layout
├── css/
│   └── styles.css      -- Design system, variables, dark mode aesthetics & glassmorphism
└── js/
    ├── api.js          -- Standardized fetch client with header injection & error handling
    ├── endpoints.js    -- Endpoints management UI & ping tester
    ├── events.js       -- Event ingestion & idempotency UI
    ├── deliveries.js   -- Delivery monitoring, polling, attempt history & redrive
    ├── isolation.js    -- Tenant isolation test runner
    └── app.js          -- Navigation, state management & initialization
```

## 4. Backend Synchronization & CORS Requirements

- Ensure CORS configuration is active on the backend (`WebMvcConfig` allowing origins, methods, and `X-Tenant-Id` header, and `TenantInterceptor` bypassing HTTP `OPTIONS` preflight requests).
- Error responses are parsed and surfaced directly to the user in toast notifications and log views.

