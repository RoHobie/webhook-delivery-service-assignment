# REST API & Webhook Signature Reference

Complete API specification and receiver verification reference for the **Multi-Tenant Webhook Delivery Service**.

---

## 1. Authentication & Tenant Identification

All API endpoints require tenant scoping. Tenants are identified via the mandatory HTTP request header:

```http
X-Tenant-Id: <tenant-identifier>
```

- Requests missing `X-Tenant-Id` will be rejected with `HTTP 401 Unauthorized`.
- Requests with an unrecognized or inactive tenant ID will be rejected with `HTTP 401 Unauthorized`.

---

## 2. API Endpoints Reference

### 2.1 Endpoint Management (`/api/v1/endpoints`)

#### Register a Webhook Endpoint
`POST /api/v1/endpoints`

Registers a new target URL for webhook payload deliveries.

**Request Headers:**
- `Content-Type: application/json`
- `X-Tenant-Id: default-tenant`

**Request Body:**
```json
{
  "url": "https://api.example.com/webhooks/receiver",
  "subscribedEventTypes": [
    "order.created",
    "user.signup",
    "invoice.paid"
  ]
}
```

**Response (`HTTP 201 Created`):**
```json
{
  "id": "end_9f81a2b3",
  "tenantId": "default-tenant",
  "url": "https://api.example.com/webhooks/receiver",
  "secret": "whsec_7f9a2b4c6e8d1f3a5b7c9e0d2f4a6b8c0e2f4a6b8c0e2f4a6b8c0e2f4a6b8c0e",
  "subscribedEventTypes": [
    "order.created",
    "user.signup",
    "invoice.paid"
  ],
  "status": "ACTIVE",
  "createdAt": "2026-08-18T05:10:00Z"
}
```

> [!IMPORTANT]
> Save the returned `secret`. Outbound webhook requests sent to your URL will be signed using this secret via HMAC-SHA256.

---

#### List Endpoints
`GET /api/v1/endpoints`

Retrieves all active and disabled endpoints belonging to the tenant.

**Response (`HTTP 200 OK`):**
```json
[
  {
    "id": "end_9f81a2b3",
    "tenantId": "default-tenant",
    "url": "https://api.example.com/webhooks/receiver",
    "subscribedEventTypes": ["order.created", "user.signup"],
    "status": "ACTIVE",
    "createdAt": "2026-08-18T05:10:00Z"
  }
]
```

---

#### Get Endpoint by ID
`GET /api/v1/endpoints/{id}`

**Response (`HTTP 200 OK`):**
```json
{
  "id": "end_9f81a2b3",
  "tenantId": "default-tenant",
  "url": "https://api.example.com/webhooks/receiver",
  "subscribedEventTypes": ["order.created", "user.signup"],
  "status": "ACTIVE",
  "createdAt": "2026-08-18T05:10:00Z"
}
```

---

#### Soft-Disable Endpoint
`DELETE /api/v1/endpoints/{id}`

Soft-disables an endpoint. Deliveries to this endpoint will cease immediately, but historical delivery records are preserved for auditing.

**Response (`HTTP 200 OK`):**
```json
{
  "id": "end_9f81a2b3",
  "tenantId": "default-tenant",
  "url": "https://api.example.com/webhooks/receiver",
  "status": "DISABLED",
  "createdAt": "2026-08-18T05:10:00Z"
}
```

---

#### Test Endpoint Connectivity (Ping)
`POST /api/v1/endpoints/{id}/test`

Sends a synchronous synthetic ping event payload to the target endpoint to verify connectivity and signature validation without queuing a real event.

**Response (`HTTP 200 OK`):**
```json
{
  "endpointId": "end_9f81a2b3",
  "success": true,
  "statusCode": 200,
  "latencyMs": 142,
  "message": "Ping payload successfully received and acknowledged by target server."
}
```

---

### 2.2 Event Ingestion (`/api/v1/events`)

#### Ingest Event
`POST /api/v1/events`

Ingests an event from a producer service. The event is stored idempotently and fan-out delivery is queued asynchronously.

**Request Body:**
```json
{
  "eventId": "evt_99182301",
  "type": "order.created",
  "payload": {
    "orderId": "ord_55102",
    "amount": 149.99,
    "currency": "USD",
    "customerEmail": "user@example.com"
  }
}
```

**Response (`HTTP 202 Accepted`):**
```json
{
  "id": "evt_int_1029384",
  "eventIdExternal": "evt_99182301",
  "tenantId": "default-tenant",
  "type": "order.created",
  "status": "ACCEPTED",
  "deliveryCount": 2,
  "createdAt": "2026-08-18T05:12:00Z"
}
```

If the producer re-submits the exact same `eventId` for the same tenant, the service returns `HTTP 202 Accepted` with the previously ingested event details without inserting duplicate deliveries.

---

### 2.3 Delivery Visibility & Diagnostics (`/api/v1/deliveries`)

#### List Deliveries
`GET /api/v1/deliveries`

Retrieves a paginated list of deliveries scoped to the requesting tenant.

**Query Parameters:**
- `status` *(optional)*: Filter by `PENDING`, `DELIVERED`, `FAILED`, or `DEAD_LETTERED`.
- `endpointId` *(optional)*: Filter by specific endpoint.
- `eventId` *(optional)*: Filter by specific ingested event ID.
- `page` *(optional, default 0)*: Zero-indexed page number.
- `size` *(optional, default 20, max 100)*: Items per page.

**Response (`HTTP 200 OK`):**
```json
{
  "content": [
    {
      "id": "del_77182901",
      "tenantId": "default-tenant",
      "eventId": "evt_int_1029384",
      "endpointId": "end_9f81a2b3",
      "status": "DELIVERED",
      "attemptCount": 1,
      "nextAttemptAt": "2026-08-18T05:12:01Z",
      "lastResponseCode": 200,
      "createdAt": "2026-08-18T05:12:00Z",
      "updatedAt": "2026-08-18T05:12:01Z"
    }
  ],
  "pageNumber": 0,
  "pageSize": 20,
  "totalElements": 1,
  "totalPages": 1,
  "last": true
}
```

---

#### Get Delivery Details
`GET /api/v1/deliveries/{id}`

**Response (`HTTP 200 OK`):**
```json
{
  "id": "del_77182901",
  "tenantId": "default-tenant",
  "eventId": "evt_int_1029384",
  "endpointId": "end_9f81a2b3",
  "status": "DELIVERED",
  "attemptCount": 1,
  "nextAttemptAt": "2026-08-18T05:12:01Z",
  "lastResponseCode": 200,
  "lastResponseSnippet": "{\"status\":\"success\",\"receivedAt\":\"2026-08-18T05:12:01Z\"}",
  "createdAt": "2026-08-18T05:12:00Z",
  "updatedAt": "2026-08-18T05:12:01Z"
}
```

---

#### Get Delivery Attempt History
`GET /api/v1/deliveries/{id}/attempts`

Returns full audit trail for every attempt made for the delivery.

**Response (`HTTP 200 OK`):**
```json
[
  {
    "id": "att_1001",
    "deliveryId": "del_77182901",
    "attemptNumber": 1,
    "responseCode": 500,
    "latencyMs": 312,
    "errorSnippet": "HTTP 500 Internal Server Error: Internal processing failure",
    "createdAt": "2026-08-18T05:12:01Z"
  },
  {
    "id": "att_1002",
    "deliveryId": "del_77182901",
    "attemptNumber": 2,
    "responseCode": 200,
    "latencyMs": 95,
    "errorSnippet": null,
    "createdAt": "2026-08-18T05:12:05Z"
  }
]
```

---

#### Redrive Dead-Lettered Delivery
`POST /api/v1/deliveries/{id}/redrive`

Manually resets a `DEAD_LETTERED` delivery back to `PENDING` with `attemptCount = 0`, forcing an immediate retry attempt.

**Response (`HTTP 200 OK`):**
```json
{
  "id": "del_77182901",
  "tenantId": "default-tenant",
  "status": "PENDING",
  "attemptCount": 0,
  "nextAttemptAt": "2026-08-18T05:15:00Z"
}
```

---

### 2.4 Observability & Health

- `GET /actuator/health`: Returns service health status and PostgreSQL database connection state.
- `GET /actuator/prometheus`: Returns Prometheus format metrics including event ingestion counters, rate limit drops, delivery attempt histograms, and circuit breaker states.

---

## 3. Webhook Consumer Signature Verification

Outbound HTTP requests sent to your webhook URL contain the following security headers:

- `X-Webhook-Signature`: Format `t=<timestamp>,v1=<hex_digest>`
- `X-Webhook-Timestamp`: Unix timestamp (seconds)
- `X-Webhook-Event-Type`: Subscribed event type (e.g., `order.created`)
- `X-Webhook-Delivery-Id`: Unique identifier for the delivery

### Verification Algorithm
To verify the payload signature:
1. Extract timestamp `t` and signature digest `v1` from the `X-Webhook-Signature` header.
2. Prepare the signed content string by concatenating timestamp `t`, a period `.`, and the raw request body:
   $$\text{signedContent} = t + "." + \text{rawRequestBody}$$
3. Compute the HMAC-SHA256 hash using your endpoint's `secret` key.
4. Compare your hex digest with `v1` using constant-time comparison.

### Consumer Code Verification Snippets

#### Node.js (TypeScript / JavaScript)
```javascript
const crypto = require('crypto');

function verifyWebhookSignature(payloadRaw, signatureHeader, secretKey) {
  const parts = signatureHeader.split(',');
  const timestamp = parts.find(p => p.startsWith('t=')).split('=')[1];
  const expectedDigest = parts.find(p => p.startsWith('v1=')).split('=')[1];

  const signedContent = `${timestamp}.${payloadRaw}`;
  const actualDigest = crypto
    .createHmac('sha256', secretKey)
    .update(signedContent, 'utf8')
    .digest('hex');

  return crypto.timingSafeEqual(
    Buffer.from(expectedDigest, 'hex'),
    Buffer.from(actualDigest, 'hex')
  );
}
```

#### Python 3
```python
import hmac
import hashlib

def verify_webhook_signature(payload_raw: str, signature_header: str, secret_key: str) -> bool:
    header_parts = dict(part.split('=', 1) for part in signature_header.split(','))
    timestamp = header_parts.get('t')
    expected_digest = header_parts.get('v1')

    signed_content = f"{timestamp}.{payload_raw}".encode('utf-8')
    actual_digest = hmac.new(secret_key.encode('utf-8'), signed_content, hashlib.sha256).hexdigest()

    return hmac.compare_digest(expected_digest, actual_digest)
```

#### Java 21
```java
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public class WebhookVerifier {
    public static boolean verify(String payloadRaw, String signatureHeader, String secret) throws Exception {
        String timestamp = null;
        String expectedDigest = null;
        
        for (String part : signatureHeader.split(",")) {
            if (part.startsWith("t=")) timestamp = part.substring(2);
            else if (part.startsWith("v1=")) expectedDigest = part.substring(3);
        }

        String signedContent = timestamp + "." + payloadRaw;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] bytes = mac.doFinal(signedContent.getBytes(StandardCharsets.UTF_8));
        
        StringBuilder hexString = new StringBuilder();
        for (byte b : bytes) hexString.append(String.format("%02x", b));
        
        return MessageDigest.isEqual(hexString.toString().getBytes(), expectedDigest.getBytes());
    }
}
```
