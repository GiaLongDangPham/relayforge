# Current Task

Status: Complete

## Goal

Implement the owner-approved minimum durable per-project UTC-day publish quota.

## Decisions

- ADR-011 permits one global, validated 10,000-new-event default per project
  per UTC day; it is not billing or a plan.
- PostgreSQL owns one current usage row per project. A conditional UPSERT
  increments it, resets it online on the next UTC day, or rejects atomically.
- An equivalent idempotent replay and a conflict consume no durable quota;
  only a new event consumes one unit, irrespective of its delivery fan-out.

## Out of scope

Redis, billing/plans, owner-configurable quota tiers, retained usage analytics,
distributed counters, custom retry policy, SSE, ordering, RBAC, or Kubernetes.

## Evidence required

- Concurrent unique publishes cannot exceed the configured quota; other
  projects retain independent capacity.
- A rejection returns `429 PUBLISH_QUOTA_EXCEEDED` with positive `Retry-After`
  and rolls back the tentative event/delivery set.

## Completion evidence

- `PublisherQuotaPropertiesTests` passed 2/2 and focused publisher
  rate-limiter/controller regressions passed 5/5.
- PostgreSQL Testcontainers publisher HTTP tests passed 4/4: quota exhaustion,
  idempotent replay, rollback, project isolation, and 20 concurrent publishes.
- Compose rebuilt API/worker/frontend, local Flyway reached V16, API health was
  `UP`, and the dashboard sign-in screen loaded without browser console errors.

## Next action

Owner review/commit. The next Phase 3 initiative is bounded retry-policy
customization only if a concrete receiver/product need justifies it.
