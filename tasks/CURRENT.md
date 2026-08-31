# Current Task

Status: Complete

## Goal

Implement ADR-010's bounded local publisher rate limiter in the API process.

## Decisions

- Apply only to `POST /api/v1/projects/{projectId}/events` after publisher API
  key authentication and path-project authorization, and before request-body
  reading or the publish transaction.
- Keep PostgreSQL as the source of truth for event acceptance and idempotency.
- Limit state is local, bounded, and reset by API-process restart; it is not a
  cluster-wide quota or a durability guarantee.
- ADR-010 accepts a project-wide token bucket with capacity 60, refill 30
  requests/second, 15-minute idle expiry, and 10,000 retained-bucket maximum.
- Each request reaching the limiter consumes one token, including a malformed
  request or equivalent idempotent retry; no token is consumed before API-key
  authentication and path-project authorization.
- Expose only bounded outcome metrics and sanitized logs; never put project,
  API-key, idempotency-key, or payload values into metric labels or log fields.

## Out of scope

Redis, a broker, database migrations, durable quotas/billing, invalid-
credential abuse controls, custom retry policy, SSE, ordering, RBAC, or
Kubernetes.

## Evidence required

- Atomic admission preserves a maximum 60-request burst and per-project
  isolation; refill, idle expiry, and the 10,000-bucket bound are covered.
- Rejection happens before publisher work, returns the specified Problem
  Details code and positive `Retry-After`, and records no event/delivery.
- Runtime rebuild, API health, and dashboard availability succeed.

## Completion evidence

- `PublisherEventRateLimiterTests`, `PublisherEventRateLimitControllerTests`,
  and `PublisherEventHttpIntegrationTests` passed 4/4 with PostgreSQL
  Testcontainers.
- `docker compose up --build -d api worker frontend` rebuilt/reloaded the
  local stack; API health returned `UP` and `http://localhost:5173/` returned
  HTTP 200. Codex browser automation could not initialize locally, so no
  interactive console check was available.

## Next action

Owner review/commit. The next Phase 3 initiative should start only from a
measured product or scale need; split it first if its proof burden is material.
