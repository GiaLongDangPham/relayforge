# ADR-013: Best-effort Dashboard SSE Invalidation

- Status: Accepted
- Date: 2026-09-01
- Decision owners: RelayForge project
- Supersedes: the SSE exclusion in the Phase 0 API contract

## Context

The Delivery dashboard currently polls five owner-scoped REST history queries
every five seconds. Slice 4.1 measured one local dashboard-equivalent session
at 20 expected/observed requests over four cycles, with one phase-offset
delivery transition becoming visible after 2.809 seconds. That is not a
performance justification for a new live-update transport.

The owner has nevertheless approved a bounded SSE extension as a learning goal.
It must teach a useful API-to-worker-process integration without changing the
accepted delivery correctness model: API and worker stay separate processes;
PostgreSQL and REST remain the source of truth; and no broker, Redis, schema,
or durable event log is introduced.

## Decision

### Stream role and scope

API mode will expose one owner-session SSE endpoint per authorized project:

```text
GET /api/v1/projects/{projectId}/delivery-updates
Accept: text/event-stream
```

The endpoint is read-only, requires the existing `RF_SESSION`, and performs
the same owner/project authorization as delivery history before opening a
stream. It requires no CSRF header. It returns `404` for a project that the
authenticated owner does not own, including guessed project identifiers.

Each event has type `delivery.changed` and only this bounded JSON data:

```json
{
  "projectId": "uuid",
  "deliveryId": "uuid",
  "observedAt": "instant"
}
```

It contains no state, attempt outcome, endpoint URL, response preview,
payload, claim token, secret, session value, or receiver detail. The dashboard
treats it solely as an invalidation hint and reuses the existing REST queries
to read the current safe representation.

The first implementation emits hints for worker-owned finalization and
`UNKNOWN` recovery state transitions only. Publish, replay, endpoint
configuration, and local owner mutations already invalidate their own query
cache and remain outside this stream's first scope.

### PostgreSQL bridge and delivery semantics

In the same short PostgreSQL transaction that commits one eligible finalization
or recovery state transition, the delivery module asks a narrow technical
notification port to issue `pg_notify` on the fixed internal channel
`relayforge_delivery_updates`. PostgreSQL releases the notification only after
that transaction commits. A rolled-back or stale-token transition therefore
cannot produce a public SSE message.

API mode owns one reconnecting dedicated PostgreSQL `LISTEN` connection; it is
not borrowed from the HTTP-request Hikari pool. It converts a received internal
project/delivery identity into `delivery.changed` only for streams already
authorized for that project. The notification payload is an internal bounded
identity envelope; it is never logged verbatim or returned directly.

`LISTEN`/`NOTIFY` is deliberately non-durable and may coalesce, be duplicated,
or be missed while the API listener/client is disconnected. SSE similarly has
no replay contract, no `Last-Event-ID` guarantee, no ordering guarantee, and
no promise that every delivery mutation produces exactly one event. PostgreSQL
state and REST queries remain authoritative. On stream open, reconnect, error,
or API restart, the dashboard invalidates its active history query keys and
continues the existing five-second polling; either path reconciles the visible
state.

### Lifecycle, security, and operational bounds

- The frontend opens at most one stream for the visible Delivery workspace and
  selected project; it closes it when that workspace/project is no longer
  active and on logout.
- API sends a comment heartbeat every 15 seconds and closes a stream after 15
  minutes. Browser `EventSource` reconnects through the ordinary session
  authentication boundary.
- Responses use `text/event-stream`, `Cache-Control: no-store`, and disable
  intermediary buffering. Production CORS remains the explicit dashboard
  origin with credentials; no wildcard origin is allowed.
- Connection, open/close, heartbeat failure, listener reconnect, received
  notification, and fan-out counts must be bounded-cardinality metrics. Logs
  record only outcome/reason and safe aggregate counts, never notification
  payloads or owner/session identifiers.
- API startup must degrade only this optional live-update bridge when its
  listener connection is temporarily unavailable. It must reconnect with a
  bounded backoff; publisher acceptance, history REST, and worker delivery
  correctness continue without it.

## Consequences

This adds a deliberately best-effort, one-way operational signal while keeping
the modular-monolith runtime and delivery transactions intact. It teaches
Spring MVC SSE lifecycle, session authorization for a long-lived GET,
PostgreSQL transaction-delivered notifications, process-bound listener
reconnection, UI cache invalidation, and why a push channel is not automatically
a reliable event source.

The trade-off is extra API connection and cleanup complexity with no current
performance claim. It does not add a migration, Redis, a broker, WebSocket,
an outbox, delivery-state change, background durable consumer, or another
runtime mode. Retaining five-second REST polling during the first
implementation makes lost hints safe at the cost of not eliminating all stale
time.

## Verification gate

Slice 4.3 is accepted only after focused evidence proves:

1. API mode opens the stream only for an authenticated owner and returns 404
   before any stream body for a cross-owner project; worker mode exposes no
   stream/controller/listener;
2. a committed eligible worker finalization/recovery produces a bounded
   same-project invalidation, while rollback and stale-token paths produce
   none;
3. a stream never contains secret/payload/URL/claim/attempt diagnostic data,
   and logs/metrics retain only bounded safe dimensions;
4. listener loss, API restart, client disconnect, and session expiry neither
   affect delivery processing nor leak/retain stream resources indefinitely;
5. reconnect/open/error causes the dashboard to refetch REST state, and the
   five-second polling fallback still works if no SSE event arrives; and
6. existing security, API/worker isolation, delivery-finalization, recovery,
   retry, and history-redaction regressions remain green.

## References

- [Architecture Boundaries](../ARCHITECTURE_BOUNDARIES.md)
- [API Contract](../API_CONTRACT.md)
- [Security Baseline](../SECURITY_BASELINE.md)
- [Performance Baseline](../PERFORMANCE_BASELINE.md)
- [ADR-001](0001-modular-monolith-api-worker-runtime.md)
- [ADR-002](0002-postgresql-backed-delivery-jobs.md)
