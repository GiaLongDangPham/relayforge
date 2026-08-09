# RelayForge Portfolio v1 API Contract

Status: Phase 0 baseline
Last updated: 2026-08-09

## 1. Purpose and boundary

This document defines the minimum HTTP contract required to implement and demo Portfolio v1. It covers owner authentication, project configuration, publisher event acceptance, history inspection, and replay.

It is a behavioral baseline, not an OpenAPI file or controller implementation. Exact validation annotations, Java DTOs, generated clients, and frontend components are deferred.

## 2. Global conventions

- Business API base path: `/api/v1`.
- JSON property names use `camelCase`.
- UUIDs are lowercase canonical strings.
- Instants are UTC RFC 3339 strings, for example `2026-08-09T12:34:56.789Z`.
- Request and response content type is `application/json` except Problem Details errors.
- Unknown request fields are rejected in write commands, preventing silent client mistakes.
- Server-managed fields such as owner ID, project ID, state, attempt count, timestamps, claim token, and signing-secret ciphertext are never accepted from clients unless explicitly listed.
- Claim tokens and internal leases are not exposed by the business API.

## 3. Authentication contexts

### 3.1 Owner dashboard

Owner endpoints use a server-side PostgreSQL-backed session cookie:

- cookie name: `RF_SESSION`;
- `HttpOnly`, `Secure` in production, `SameSite=Lax`, path `/`;
- mutating requests require the configured CSRF header;
- session inactivity timeout: 30 minutes.

The browser obtains a CSRF token before login and sends it on every owner mutation. The security details live in [Security Baseline](SECURITY_BASELINE.md).

### 3.2 Publisher client

The publish endpoint requires:

```http
Authorization: Bearer <project-api-key>
Idempotency-Key: <opaque-client-key>
```

The API key authenticates exactly one project. The project ID in the path must match that credential. Cookie authentication is not accepted as a substitute for publisher authentication.

### 3.3 Operator endpoints

Liveness, readiness, and metrics are management endpoints, not owner business APIs. Cloud networking restricts them; their final paths and management port are deployment decisions.

## 4. Error contract

Errors use `application/problem+json` with these fields:

```json
{
  "type": "urn:relayforge:problem:idempotency-conflict",
  "title": "Idempotency key conflict",
  "status": 409,
  "detail": "The key is already associated with a different command.",
  "instance": "/api/v1/projects/.../events",
  "code": "IDEMPOTENCY_CONFLICT",
  "traceId": "...",
  "fieldErrors": [
    {"field": "eventType", "code": "INVALID_FORMAT", "message": "..."}
  ]
}
```

- `detail` never contains secrets, raw credentials, payload contents, SQL, or stack traces.
- `fieldErrors` is present only for field validation.
- `traceId` correlates sanitized logs and tracing.
- Cross-owner resource lookup returns 404 rather than confirming that another owner's resource exists.

Core status/code mapping:

| HTTP status | Codes |
| --- | --- |
| 400 | `MALFORMED_JSON`, `VALIDATION_FAILED`, `MISSING_IDEMPOTENCY_KEY` |
| 401 | `OWNER_AUTHENTICATION_REQUIRED`, `INVALID_OWNER_CREDENTIALS`, `INVALID_API_KEY` |
| 403 | `CSRF_REJECTED`, `PROJECT_KEY_MISMATCH` |
| 404 | `RESOURCE_NOT_FOUND` |
| 409 | `IDEMPOTENCY_CONFLICT`, `OPTIMISTIC_LOCK_CONFLICT`, `INVALID_STATE_TRANSITION` |
| 413 | `PAYLOAD_TOO_LARGE` |
| 429 | `RATE_LIMITED` when a later bounded policy is active |
| 503 | `DEPENDENCY_UNAVAILABLE` when durable processing cannot proceed |

## 5. Pagination

Owner list endpoints use cursor pagination:

- `limit` defaults to 20 and is bounded from 1 through 100;
- `cursor` is opaque and base64url-safe;
- response contains `items` and nullable `nextCursor`;
- stable order is documented per resource and always includes UUID as a tie-breaker;
- malformed, expired-version, or filter-mismatched cursor returns 400;
- total count is omitted by default because an exact count is not required for workflow navigation.

A cursor is bound to its resource type, project, sort direction, and normalized filters. Clients must not parse it.

## 6. Owner authentication endpoints

### `GET /api/v1/auth/csrf`

Creates or resumes an anonymous session as needed and returns the CSRF header name/token required for login and later mutations.

```json
{"headerName": "X-CSRF-TOKEN", "token": "..."}
```

### `POST /api/v1/auth/session`

CSRF protected. Request:

```json
{"loginName": "owner", "password": "..."}
```

On success, rotates the session identifier, authenticates the session, and returns 200:

```json
{"ownerId": "uuid", "loginName": "owner"}
```

Invalid credentials return the same generic 401 response regardless of whether the login exists.

### `GET /api/v1/auth/me`

Returns the authenticated owner identity or 401.

### `DELETE /api/v1/auth/session`

CSRF protected. Invalidates server session and clears the cookie. Returns 204 and is idempotent for an existing authenticated session.

Public registration, password reset, email verification, refresh tokens, and OAuth login do not exist in v1.

## 7. Project endpoints

### `POST /api/v1/projects`

Owner session + CSRF. Request:

```json
{"name": "Payments Demo"}
```

Returns 201 with `Location` and:

```json
{
  "id": "uuid",
  "name": "Payments Demo",
  "version": 0,
  "createdAt": "instant",
  "updatedAt": "instant"
}
```

### `GET /api/v1/projects?limit=&cursor=`

Lists only owned projects ordered by `(createdAt desc, id desc)`.

### `GET /api/v1/projects/{projectId}`

Returns one owned project.

### `PATCH /api/v1/projects/{projectId}`

Owner session + CSRF. Request:

```json
{"name": "Renamed Project", "version": 0}
```

Returns updated project with incremented version. Stale version returns 409.

Project deletion/deactivation does not exist in v1.

## 8. Publisher API-key endpoints

### `POST /api/v1/projects/{projectId}/api-keys`

Owner session + CSRF. Request:

```json
{"displayName": "Checkout Publisher"}
```

Returns 201. `rawKey` appears only in this response:

```json
{
  "id": "uuid",
  "displayName": "Checkout Publisher",
  "keyHint": "rf_live_ab12...",
  "rawKey": "rf_live_<public-id>.<secret>",
  "createdAt": "instant",
  "revokedAt": null
}
```

If the response is lost, the raw key cannot be fetched. The owner revokes this record and creates another key.

### `GET /api/v1/projects/{projectId}/api-keys?limit=&cursor=`

Returns metadata only, ordered by `(createdAt desc, id desc)`. It never returns digest or raw key.

### `POST /api/v1/projects/{projectId}/api-keys/{keyId}/revoke`

Owner session + CSRF. Returns 200 key metadata. Repeating the command returns the same revoked representation; it never reactivates a key.

## 9. Endpoint configuration endpoints

### `POST /api/v1/projects/{projectId}/endpoints`

Owner session + CSRF. Request:

```json
{
  "name": "Billing Receiver",
  "destinationUrl": "https://receiver.example/webhooks",
  "eventTypes": ["invoice.paid", "invoice.failed"],
  "enabled": true
}
```

Returns 201. `signingSecret` appears only in this response:

```json
{
  "id": "uuid",
  "projectId": "uuid",
  "name": "Billing Receiver",
  "destinationUrl": "https://receiver.example/webhooks",
  "eventTypes": ["invoice.failed", "invoice.paid"],
  "enabled": true,
  "version": 0,
  "signingSecret": "whsec_...",
  "createdAt": "instant",
  "updatedAt": "instant"
}
```

If this response is lost, the secret is not recoverable through the API. The owner disables the endpoint and creates a replacement because v1 has no signing-secret rotation.

### `GET /api/v1/projects/{projectId}/endpoints?limit=&cursor=`

Returns endpoint metadata without signing material, ordered by `(createdAt desc, id desc)`.

### `GET /api/v1/projects/{projectId}/endpoints/{endpointId}`

Returns one endpoint without signing material.

### `PUT /api/v1/projects/{projectId}/endpoints/{endpointId}`

Owner session + CSRF. Replaces name, destination URL, and the complete exact subscription set in one optimistic transaction. It does not change enabled state or signing secret.

```json
{
  "name": "Billing Receiver",
  "destinationUrl": "https://new.example/webhooks",
  "eventTypes": ["invoice.paid"],
  "version": 0
}
```

Returns the endpoint with incremented version. Empty subscriptions are rejected.

### `POST /api/v1/projects/{projectId}/endpoints/{endpointId}/enable`

### `POST /api/v1/projects/{projectId}/endpoints/{endpointId}/disable`

Both require owner session + CSRF and body `{"version": 1}`. A real state change must match the current version and returns metadata with incremented version. If the endpoint is already in the requested state, the command is an idempotent no-op that returns 200 current metadata without incrementing version; its submitted version cannot cause another mutation. A stale version attempting an actual state change returns 409.

No endpoint deletion or secret-rotation endpoint exists in v1.

## 10. Publisher event acceptance

### `POST /api/v1/projects/{projectId}/events`

Requires project API key and `Idempotency-Key`. Request body is limited to 64 KiB before parsing:

```json
{
  "eventType": "invoice.paid",
  "payload": {"invoiceId": "inv_123", "amount": 4200}
}
```

First acceptance and an equivalent retry both return 202 with the same logical result:

```json
{
  "eventId": "uuid",
  "projectId": "uuid",
  "eventType": "invoice.paid",
  "acceptedAt": "instant",
  "deliveryCount": 2,
  "idempotentReplay": false
}
```

An equivalent retry sets only `idempotentReplay` to true; event ID, acceptance time, and delivery count remain original. Same key with different event type or payload returns 409. Zero matching endpoints is valid and returns `deliveryCount: 0`.

## 11. Event and delivery inspection

### `GET /api/v1/projects/{projectId}/events`

Owner session. Filters: optional exact `eventType`; pagination order `(acceptedAt desc, id desc)`. List item excludes full payload by default.

### `GET /api/v1/projects/{projectId}/events/{eventId}`

Returns event metadata, payload, and delivery summary counts after ownership validation.

### `GET /api/v1/projects/{projectId}/events/{eventId}/deliveries`

Cursor-paginated by `(createdAt asc, id asc)`. Returns delivery summaries for that event.

### `GET /api/v1/projects/{projectId}/deliveries`

Optional filters: `eventId`, `endpointId`, and effective `status`. Stable order `(createdAt desc, id desc)`.

Delivery summary exposes:

```json
{
  "id": "uuid",
  "eventId": "uuid",
  "endpointId": "uuid",
  "replayOfDeliveryId": null,
  "state": "PENDING",
  "displayStatus": "RETRY_SCHEDULED",
  "attemptCount": 2,
  "nextAttemptAt": "instant",
  "createdAt": "instant",
  "terminalAt": null
}
```

`displayStatus` may be `PENDING`, `CLAIMED`, `RETRY_SCHEDULED`, `PAUSED`, `SUCCEEDED`, `FAILED_PERMANENT`, or `EXHAUSTED`. It is derived and never accepted as persisted state.

### `GET /api/v1/projects/{projectId}/deliveries/{deliveryId}`

Returns summary plus event type, endpoint metadata, replay parent/children links, and latest attempt summary. It excludes claim token and secret material.

### `GET /api/v1/projects/{projectId}/deliveries/{deliveryId}/attempts`

Returns at most five attempt summaries ordered by `attemptNumber asc`; no cursor is needed.

### `GET /api/v1/projects/{projectId}/deliveries/{deliveryId}/attempts/{attemptId}`

Returns one attempt detail including bounded, escaped response preview, truncation flag, destination fingerprint, and optional late diagnostic. It never returns the exact destination URL snapshot, claim token, secrets, request/response headers, or unbounded receiver body.

## 12. Replay

### `POST /api/v1/projects/{projectId}/deliveries/{deliveryId}/replays`

Owner session + CSRF + `Idempotency-Key`. Source must be owned and currently `EXHAUSTED`.

First command and equivalent retry return 202 with the same result:

```json
{
  "sourceDeliveryId": "uuid",
  "replayDeliveryId": "uuid",
  "eventId": "uuid",
  "endpointId": "uuid",
  "createdAt": "instant",
  "idempotentReplay": false
}
```

An equivalent retry changes only `idempotentReplay` to true. Reusing the key for another source returns 409. The new delivery begins pending with zero attempts and uses normal attempt-start endpoint URL snapshotting.

## 13. Outbound webhook contract

Each attempt sends JSON:

```json
{
  "eventId": "uuid",
  "eventType": "invoice.paid",
  "acceptedAt": "instant",
  "data": {"invoiceId": "inv_123", "amount": 4200}
}
```

Required headers:

- `Content-Type: application/json`;
- `User-Agent: RelayForge/1`;
- `X-RelayForge-Event-Id`;
- `X-RelayForge-Delivery-Id`;
- `X-RelayForge-Attempt-Id`;
- `X-RelayForge-Attempt-Number`;
- `X-RelayForge-Timestamp`;
- `X-RelayForge-Signature`.

Automatic retry keeps event/delivery IDs and creates another attempt ID/number. Replay keeps event ID and creates another delivery and attempt identity. Signature canonicalization is defined by the security baseline.

Any HTTP 2xx is transport success. Redirects are not followed. Response body is diagnostic only and does not change the HTTP-status classification.

## 14. Contract verification

Future API-level tests must prove:

1. login rotates session and every owner mutation enforces CSRF;
2. cross-owner project/key/endpoint/event/delivery/attempt access returns 404;
3. publisher key cannot publish to another path project;
4. raw API key and endpoint signing secret appear only in successful creation responses;
5. stale optimistic versions return 409 without partial changes;
6. endpoint subscription replacement is atomic;
7. publish request size is rejected before persistence when over 64 KiB;
8. publish idempotency returns the original result and conflicting content returns 409;
9. event with zero routes returns 202 and remains queryable;
10. list cursors remain stable when timestamps tie and reject filter reuse;
11. attempt lists contain at most five ordered records;
12. replay is allowed only from exhausted delivery and is idempotent;
13. error responses contain no secret or payload detail;
14. outbound receiver fixture verifies identity headers and HMAC signature;
15. response preview is bounded, escaped, owner-scoped, and omitted from list APIs.

## 15. Explicitly absent from v1

- Public registration, password reset, OAuth login, or organization RBAC.
- Project/endpoint deletion or project deactivation.
- Signing-secret retrieval or rotation.
- Event mutation or cancellation.
- Manual retry of success/permanent failure.
- Replay of a non-exhausted delivery.
- Bulk publish, bulk replay, or arbitrary filtering language.
- WebSocket/SSE status stream; the dashboard polls bounded REST endpoints.
- Ordering or exactly-once guarantees.

The implementation may refine field naming through an OpenAPI review, but it must not weaken authentication separation, idempotency, ownership scoping, concurrency conflicts, secret one-time visibility, cursor stability, or delivery semantics.
