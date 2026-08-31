# ADR-009: PostgreSQL-Authoritative Endpoint Circuit Breaker

- Status: Accepted
- Date: 2026-08-31
- Decision owners: RelayForge project
- Supersedes: None; extends ADR-002, ADR-007, and ADR-008

## Context

Retry limits one delivery's repeated work. It does not stop a large backlog
from continuing to send independent deliveries to one receiver that is clearly
unavailable or overloaded. `Retry-After` can defer only the delivery that
observed the response. RelayForge therefore needs a bounded endpoint-wide
backpressure decision.

The decision must not add a coordinator, Redis lock, broker, or worker-local
state as a correctness dependency. Multiple worker processes already compete
through PostgreSQL, and endpoint-fair claim selection must remain
work-conserving for healthy endpoints. No outbound HTTP may occur in a
database transaction.

## Decision

RelayForge will use a durable, PostgreSQL-authoritative circuit breaker for
each endpoint. It is delivery-runtime state, separate from the endpoint's
owner-managed configuration and optimistic version. An absent circuit row is
equivalent to `CLOSED` with zero consecutive qualifying failures.

### State and defaults

| State | Meaning | Claim eligibility |
| --- | --- | --- |
| `CLOSED` | The endpoint accepts normal delivery work. | All due eligible deliveries participate in endpoint-fair claim selection. |
| `OPEN` | RelayForge has observed enough qualifying failures and temporarily protects the receiver. | No normal delivery claim is allowed. After `open_until` has passed, one due delivery may compete as a probe. |
| `HALF_OPEN` | One claimed delivery is the durable recovery probe. | The probe continues normally; all other deliveries for that endpoint are excluded. |

The initial fixed values are three consecutive qualifying failures and a
30-second PostgreSQL-time cooldown. They are bounded demonstration defaults,
not a receiver rate limit or SLA.

A qualifying failure is one of: HTTP `408`, `429`, or `5xx`; a dispatch
timeout; or a network/connect failure. A success or a definitive
nonqualifying result, including a permanent HTTP failure, resets a closed
streak because it proves the receiver path responded. `UNKNOWN` does not
increment or reset a closed streak: it can represent worker, lease, or
database failure rather than a receiver failure.

### Transitions and ownership

1. In `CLOSED`, finalization of a qualifying attempt atomically increments
   the endpoint's consecutive qualifying-failure count. The third such result
   opens the breaker until `CURRENT_TIMESTAMP + 30 seconds`. A nonqualifying
   finalized attempt resets the count to zero.
2. While `OPEN` and the cooldown has not expired, delivery rows remain
   durable `PENDING` work but are excluded from normal claims. RelayForge does
   not rewrite each delivery's `due_at` merely because the endpoint is open.
3. After cooldown, a worker can atomically claim exactly one due delivery and
   change the breaker from `OPEN` to `HALF_OPEN`. The breaker records that
   delivery ID and claim token as its probe fence. The winner is selected by
   PostgreSQL locking/conditional update; losers make no probe claim.
4. A successful or definitive nonqualifying half-open probe closes the
   breaker and clears the failure/probe fields in the same finalization
   transaction as the attempt and delivery result. A qualifying probe reopens
   it for a fresh cooldown in that same transaction.
5. If recovery turns a started half-open probe into `UNKNOWN`, it reopens the
   breaker for a fresh cooldown in the recovery transaction. If a half-open
   claim ends before attempt start (for example endpoint disablement or an
   expired pre-start lease), it releases the probe fence back to `OPEN` with
   an immediately eligible cooldown timestamp; no receiver failure is
   invented.

Every transition locks or conditionally matches the same durable circuit row
and relevant delivery/claim token in one short PostgreSQL transaction. HTTP,
DNS, and signature work remain outside that transaction. PostgreSQL supplies
all cooldown comparisons and timestamps.

### Interaction with existing delivery rules

- Circuit state never changes accepted events, routing snapshots, delivery
  identifiers, attempt budget, HMAC material, or per-delivery retry schedule.
- The triggering retryable delivery still uses ADR-008's effective retry
  delay. If it becomes due before the circuit cooldown ends, it remains
  pending until the breaker permits a claim.
- A circuit probe consumes one already-reserved worker permit and competes as
  a one-delivery endpoint candidate under ADR-007's fair allocation rule; it
  cannot bypass global capacity or preempt active HTTP work.
- Disabled endpoints still prohibit all new attempts regardless of circuit
  state. Disabling, URL changes, or re-enabling do not silently erase breaker
  evidence; the next permitted half-open probe determines recovery.
- A circuit breaker limits concurrent dispatch pressure, not request rate. It
  does not promise strict delivery ordering, exactly-once delivery, or an
  endpoint-specific concurrency quota.

## Rationale and trade-offs

- Three consecutive failures avoids opening on one transient failure while
  making a sustained outage visible within a small demonstration window.
- A fixed 30-second cooldown is simple to observe and bounds the time that
  overdue deliveries are held. It can be tuned later from measured receiver
  failure patterns; exponential open intervals are deliberately deferred.
- A fenced durable half-open probe prevents every worker from retrying the
  endpoint simultaneously when a cooldown expires. A process-local flag would
  fail as soon as a second worker runs.
- Leaving deliveries `PENDING` preserves their individual retry due-times and
  avoids a large write fan-out. The trade-off is that an open endpoint may
  have overdue rows which return gradually under existing fair capacity.
- `UNKNOWN` is intentionally not treated as receiver evidence. Reopening an
  already-half-open unknown probe is conservative because otherwise the breaker
  could remain stuck and no later probe could establish receiver health.

## Alternatives considered

### Only retry and `Retry-After`

This controls an individual delivery but cannot stop another large endpoint
backlog from continuing outbound work during receiver outage.

### Worker-local breaker

This is simple in one process but several workers would independently reopen
and probe the same receiver. It is rejected because the deployment already
uses separate worker processes.

### Redis distributed lock/state

Redis would add another source of correctness state and failure mode without
solving the PostgreSQL delivery transition. PostgreSQL row fencing is already
required and sufficient at the current scale.

### Open the breaker for every retryable or `UNKNOWN` attempt

This would react quickly but turns a single timeout, worker crash, or
PostgreSQL incident into endpoint-wide suppression. The selected threshold and
classification keep receiver evidence distinct from internal ambiguity.

### Multiple half-open probes

Several probes shorten recovery sampling but reintroduce a burst precisely
when a receiver is recovering. One durable probe makes the invariant and test
case explicit.

## Verification gate

Implementation is accepted only after focused PostgreSQL Testcontainers tests
prove all of the following:

1. two workers finalizing qualifying failures cannot skip or double the
   three-failure opening threshold;
2. an `OPEN` endpoint produces no normal claim while healthy endpoints still
   use all otherwise free capacity;
3. a cooldown-expired endpoint has at most one durable `HALF_OPEN` probe
   delivery/token across concurrent workers;
4. probe success and definitive nonqualifying failure close/reset; qualifying
   failure and recovered `UNKNOWN` reopen; and every probe-fence cleanup is
   token-safe;
5. disabled endpoints, stale finalization, lease recovery, fifth-attempt
   exhaustion, and ADR-008 retry scheduling preserve their existing behavior;
6. `EXPLAIN (ANALYZE, BUFFERS)` covers the breaker-aware claim and probe query
   before any performance claim or index is accepted; and
7. metrics use bounded state/outcome labels only, never endpoint IDs, URLs, or
   receiver header values.

## Consequences

The next slice may add the delivery-owned circuit-state table, migration,
configuration defaults, and application/persistence contracts. It must not
add a dashboard control, Redis, a broker, an endpoint rate limiter, custom
retry policies, or rolling/error-percentage windows. Those require separate
evidence and a reviewed product need.

## References

- [ADR-002: PostgreSQL-Backed Delivery Jobs with Leases and Claim Tokens](0002-postgresql-backed-delivery-jobs.md)
- [ADR-007: Work-Conserving Endpoint-Fair Dispatch](0007-work-conserving-endpoint-fair-dispatch.md)
- [ADR-008: Bounded Receiver Retry-After Scheduling](0008-bounded-retry-after-scheduling.md)
- [RelayForge Delivery Model](../DELIVERY_MODEL.md)
- [RelayForge Delivery Runtime Defaults](../DELIVERY_RUNTIME_DEFAULTS.md)
