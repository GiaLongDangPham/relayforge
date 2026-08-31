# ADR-011: Durable Project Daily Publish Quota

- Status: Accepted
- Date: 2026-09-01
- Decision owners: RelayForge project
- Supersedes: None

## Context

ADR-010 limits short publisher bursts in one API process but resets on restart
and is independent at each API instance. The owner has approved a deliberately
small Portfolio v1 scope expansion: protect the public demo from one project
creating an unlimited number of *new* accepted events in a UTC day. This is not
billing, a customer plan, or a distributed counter.

## Decision

Each project has one durable daily quota for newly accepted events. The global
runtime default is 10,000 events per UTC day, configured through
`relayforge.publisher.quota.daily-accepted-events` and validated from 1 through
1,000,000 at startup.

The quota runs inside the existing `delivery` publish transaction after a new
`events` row wins project/idempotency-key insertion and before endpoint routing
or delivery creation. PostgreSQL `CURRENT_TIMESTAMP` determines the UTC day.
The one-row-per-project usage relation atomically resets its count when that
day changes; no reset scheduler or historical usage ledger is introduced.

- An equivalent idempotent replay returns its original result and consumes no
  durable quota; a conflicting replay remains `409` and consumes none.
- A newly inserted event consumes exactly one quota unit, independent of its
  delivery fan-out. Replays, owner commands, worker behavior, and malformed
  requests are outside this quota.
- The conditional PostgreSQL UPSERT admits at most the configured number of
  concurrent new events for one project/day. Quota rejection rolls back the
  tentative event, so it creates no event or delivery.
- Rejection is `429 PUBLISH_QUOTA_EXCEEDED` with a positive `Retry-After` to
  the next UTC-day boundary. It exposes neither used count nor another
  project's state.

The local token bucket remains first: it still counts every authenticated,
path-authorized request before body work. This durable quota is a separate
post-parse, new-event acceptance control.

## Consequences

PostgreSQL remains the durable source of truth and the one atomic publish
transaction remains intact. The per-project row bounds durable state without
Redis or a background reset job, but the fixed global limit is not a tenant
plan and owners cannot yet view/change individual usage. Billing, plans,
monthly quotas, retained usage analytics, and cluster-wide rate limiting remain
out of scope.

## Verification gate

Focused PostgreSQL/API tests must prove a second project is independent,
equivalent/conflicting retries consume no quota, quota rejection persists no
event/delivery, and concurrent unique publishes cannot exceed the configured
limit. Configuration validation and existing local-rate-limit regressions must
remain green.

## References

- [Requirements](../REQUIREMENTS.md)
- [Database Model Part 2](../DATABASE_MODEL_PART2.md)
- [API Contract](../API_CONTRACT.md)
- [ADR-010](0010-local-publisher-rate-limiting.md)
