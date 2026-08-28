# Current Task

Status: Completed

## Goal

Complete Group 14 terminal-history retention: safely remove only expired, complete terminal delivery graphs in bounded transactions without changing event publishing, delivery semantics, or configuration retention.

## Decisions

- Use PostgreSQL time and a 30-day default retention period.
- One application-owned `TransactionTemplate` cleans one candidate event graph per short transaction; it locks, rechecks, then deletes dependent operational records before deliveries and the event.
- Coordinate cleanup with replay through source-delivery locking, so a new replay cannot produce a partially retained graph.
- Schedule worker-only cleanup at a fixed delay with bounded per-run work and metrics; do not expose an owner API for deletion.

## Out of scope

Manual record deletion, configurable per-project retention, archival/object storage, cross-region cleanup, and changes to delivery semantics or configuration retention.

## Evidence required

- A PostgreSQL integration test proves an old all-terminal replay graph and a no-route event are removed atomically with attempts, diagnostics, and replay idempotency records, while configuration remains.
- The same test proves nonterminal work and a graph with a pending replay child are retained.
- Worker composition exposes the retention metrics, and rebuilt Compose preserves the Group 12 smoke flow and dashboard sign-in page.

## Verification evidence

- Focused PostgreSQL Testcontainers tests passed 4/4: the retention integration suite proved complete graph/no-route cleanup, nonterminal/pending-child preservation, and concurrent replay/retention all-or-nothing outcomes; worker composition proved retention metrics are exposed. The concurrency test initially exposed an event-to-delivery versus delivery-to-event deadlock, then passed after both flows were aligned to delivery-then-event locking.
- Compose rebuild succeeded. API and worker readiness are `UP`, worker Prometheus contains `relayforge_retention_runs`, and local Flyway history is V12.
- `group12-smoke.ps1 -VerifyRestart` passed with idempotent publish, valid HMAC, one successful delivery attempt, and restart persistence.
- Browser acceptance passed: `http://localhost:5173/` rendered the authenticated workspace with no console errors.
