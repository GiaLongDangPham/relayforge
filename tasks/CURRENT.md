# Current Task

Status: Complete

## Goal

Complete Phase 3 Slice 4.6: prove the accepted SSE bridge's lifecycle,
observability, polling recovery, and authenticated dashboard behavior.

## Decisions

- PostgreSQL/REST remain authoritative: listener, API, and browser reconnects
  only cause invalidation/refetch and can never alter delivery correctness.
- API listener loss must reconnect with its bounded backoff; stream close and
  API shutdown must release local resources while delivery processing remains
  unaffected.
- The authenticated browser test uses the existing owner session and dashboard
  only; it neither exposes credentials nor creates a new SSE contract.

## Out of scope

Migrations, Redis/broker, delivery-state machine changes, WebSocket, ordering,
RBAC, a connection-status UI, production/EC2 measurements, and
performance/capacity claims.

## Evidence required

- PostgreSQL Testcontainers proves a forcibly terminated dedicated listener
  reconnects and resumes same-project SSE fan-out; stream close records its
  bounded cleanup metric.
- Existing committed-only, owner authorization, worker isolation, and history
  redaction evidence remains green.
- An authenticated browser opens the Delivery tab, observes a delivery update
  through refetched REST history, then closes its stream when leaving the tab
  or signing out; no console errors occur and polling remains enabled.

## Completion evidence

- PostgreSQL Testcontainers forcibly terminated the dedicated `LISTEN`
  backend. The API recorded `reconnect`, re-established its listener, and
  delivered the next committed update to the same project SSE stream.
- The API-local registry test proves a 15-minute stream timeout and exactly
  one bounded `closed` metric when API lifecycle shutdown releases a stream.
- The authenticated local dashboard opened the Delivery stream, received a
  fixture `NOTIFY` as a best-effort hint (`listener=received`, `stream=sent`),
  retained REST-rendered history with no browser warnings/errors, closed after
  leaving Deliveries (`heartbeat_failed` cleanup), and opened a fresh stream
  when returning. Five-second REST polling remained enabled.
- Focused backend regression passed 2/2; frontend lint and production build
  passed; rebuilt Compose API/worker reached readiness.

## Next action

Choose the next evidence-gated Phase 3 parent initiative; do not add another
distributed component by default.
