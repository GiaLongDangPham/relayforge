# Current Task

Status: Complete

## Goal

Complete Group 10: expose owner-scoped event, delivery, and attempt history, then create idempotent manual replay deliveries from exhausted history.

## Decisions

- Owner history is delivery-owned: its public query contract scopes every event, delivery, and attempt read through an owned project and returns immutable secret-free DTOs. Exact URLs, signing material, claim tokens, headers, and unbounded response data never cross that boundary.
- List cursors bind owner, project, requested filters, ordering position, and query kind so a cursor cannot be reused for a different owned/history query. PostgreSQL remains the order/time authority.
- Replay is a short `READ COMMITTED` delivery transaction. It resolves a project-scoped idempotency key, returns the existing equivalent command, conflicts on a different source, and otherwise creates a linked `PENDING` delivery only when its owned source is currently `EXHAUSTED`.
- A replay row has a new delivery identifier and a fresh zero-attempt budget while preserving its source event/endpoint/project identity. Existing worker polling and attempt-start logic process it normally; API requests never send outbound HTTP.

## Out of scope

Retention, metrics/health, full graceful-shutdown policy, cloud, and frontend work.

## Evidence required

- PostgreSQL/Testcontainers evidence proves V11 lineage and integrity, owner isolation, stable cursor pagination, safe bounded attempt data, replay source preservation, idempotent convergence, and key/source conflict behavior.
- API evidence proves session ownership, `404` cross-owner behavior, safe history projection, CSRF-protected replay, validation/error mapping, and the absence of Group 10 controllers in worker mode.
- Existing worker evidence is extended only as needed to prove a replay starts as ordinary `PENDING` work; no new delivery execution path is introduced.

## Verification evidence

- JDK 25 focused Docker/Testcontainers regression passed 34/34: PostgreSQL foundation (22), delivery history/replay integration (2), history/replay HTTP (1), API and worker runtime composition (2), and module boundaries (7).
- The evidence covers V11 migration integrity, history owner isolation and cursor binding, bounded/redacted attempt inspection, CSRF-protected replay, replay idempotency convergence/conflict, and ordinary worker claim of the newly pending replay.
- `git diff --check` passed. No full suite was run under the focused-test policy.
