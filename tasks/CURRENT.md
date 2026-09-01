# Current Task

Status: Complete

## Goal

Complete Phase 3 Slice 4.3: implement the smallest API-only owner SSE stream
and post-commit PostgreSQL notification bridge defined by ADR-013.

## Decisions

- PostgreSQL and existing owner-scoped REST history remain the source of truth;
  SSE is a lossy invalidation hint only.
- The delivery finalization/recovery transaction calls `pg_notify` only after
  its durable state/circuit updates succeed; PostgreSQL releases it at commit.
- API mode owns the dedicated reconnecting `LISTEN` connection and only fans a
  received project/delivery identity to streams authorized for that project.

## Out of scope

Frontend `EventSource` integration, migrations, Redis/broker, delivery-state
machine changes, WebSocket, ordering, RBAC, production/EC2 measurements, and
performance/capacity claims.

## Evidence required

- API-only authenticated stream returns 404 for a cross-owner project and is
  absent from worker composition.
- A committed finalization/recovery reaches same-project SSE with the bounded
  payload; stale/rolled-back paths emit no notification.
- Listener reconnect, client close, bounded heartbeat/lifetime, safe metrics,
  and REST fallback boundaries are represented without any correctness impact.

## Completion evidence

- PostgreSQL Testcontainers proves that committed worker finalization and
  `UNKNOWN` recovery emit the exact bounded project/delivery identity, while a
  stale finalization emits nothing.
- API HTTP integration proves owner authorization, cross-owner `404`, stream
  headers, committed `LISTEN`/`NOTIFY` fan-out, and the absence of event body
  or outcome data from the SSE payload.
- Focused API/worker composition, finalization, and ArchUnit boundary checks
  passed. The local Compose API/worker reload and dashboard browser smoke are
  recorded in the project status ledger.

## Next action

Start Slice 4.4: integrate one visible-dashboard `EventSource` as a lossy
TanStack Query invalidation hint while retaining the existing five-second REST
polling recovery path.
