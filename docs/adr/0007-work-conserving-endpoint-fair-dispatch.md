# ADR-007: Work-Conserving Endpoint-Fair Dispatch

- Status: Accepted
- Date: 2026-08-31
- Decision owners: RelayForge project
- Supersedes: None; extends ADR-002 claim selection

## Context

ADR-002 gives every worker a bounded claim capacity and prevents paused rows
from occupying that capacity. Its oldest-due-first candidate order does not stop
one endpoint with a large old backlog from occupying every worker permit while
another endpoint waits behind it.

A fixed per-endpoint cap would prevent that noisy-neighbor case, but it would
also leave capacity idle. With eight free permits, endpoint A must be allowed to
use all eight when no other endpoint has due work. If endpoint B later becomes
backlogged, active work for A is not cancelled; B receives the next available
claim opportunity and then competes fairly for later capacity.

The decision must preserve PostgreSQL as the delivery source of truth, the
existing lease/token fencing, `SKIP LOCKED` competition, endpoint-disable
serialization, and the rule that outbound HTTP never runs in a transaction.

## Decision

RelayForge will use non-preemptive, work-conserving endpoint-fair claim
selection.

- The scheduling unit is `endpoint_id`, not project, event type, or worker.
- Current allocation is the number of `CLAIMED` deliveries for an endpoint
  visible to the claim transaction. Expired claims still count until recovery
  changes their state.
- Due `PENDING` deliveries inside one endpoint retain `(due_at, id)` order.
- For selection, each pending row receives an allocation level equal to the
  endpoint's current allocation plus that row's one-based pending ordinal.
- The claim transaction prefers the lowest allocation level. Equal levels use
  oldest `due_at`, then stable endpoint and delivery identifiers.
- Selection remains work-conserving: it returns `min(C, E)` claims for positive
  requested capacity `C` and eligible due row count `E`, even when only one
  endpoint has work.
- Active claims and HTTP calls are never preempted. Fairness affects only new
  claims made when capacity becomes available.
- Disabled endpoints are excluded before ranking and remain subject to the
  final row-locked eligibility recheck from ADR-002.
- Retry and replay deliveries use the same rule as original deliveries once
  they are due. The rule provides no delivery-completion ordering guarantee.

This is a least-allocated fair selection policy, not a per-endpoint concurrency
limit or rate limiter. Fairness is based on committed PostgreSQL claim state;
brief skew remains possible between concurrent transaction snapshots and while
expired or stale local work is being recovered.

## Required examples

Assume one claim opportunity has eight free permits:

| Starting state | Required new allocation |
| --- | --- |
| A and B both have deep due backlog; neither has a current claim | A gets 4, B gets 4. |
| A has deep backlog; B has no due work | A may get all 8. |
| A and B are empty initially; B has only two due rows | B gets 2 and A may use the remaining 6. |
| A currently holds 7 claims; B holds 0; both have due work; one permit becomes free | B gets the next claim. Existing A work continues. |
| B drains after a prior 4/4 allocation while A stays backlogged | A progressively reuses freed permits up to the worker-wide limit. |
| B becomes backlogged while A occupies all permits | No work is cancelled; B is preferred when the next permit becomes available. |

The exact split across several concurrent claim transactions is allowed a brief
snapshot skew, but repeated claim opportunities must not starve a continuously
eligible endpoint.

## Invariants

1. Fairness never reduces the number of claims returned below `min(C, E)`.
2. An endpoint with due work cannot be skipped indefinitely while another
   endpoint repeatedly receives new claims.
3. A fair-selection change cannot bypass attempt budget, due-time, endpoint
   enabled state, local permits, claim tokens, or leases.
4. Claim commit still precedes attempt start and outbound HTTP.
5. Fairness state is derived from delivery state; this phase adds no second
   queue or distributed lock.

## Failure modes and trade-offs

- Computing per-endpoint ordinals and current allocations makes the claim query
  more expensive than global FIFO and may require another evidence-backed index.
- Oldest global delivery is no longer always selected first; bounded endpoint
  fairness intentionally trades strict global age order for noisy-neighbor
  isolation.
- PostgreSQL snapshots plus `SKIP LOCKED` provide progress across workers but do
  not create mathematically exact real-time fairness.
- A crashed worker's expired claim can temporarily make its endpoint appear
  busier until recovery clears or reschedules it.
- Fairness controls RelayForge dispatch allocation, not receiver request rate.
  Per-endpoint rate limiting remains a separate future decision.

## Alternatives considered

### Global oldest-due-first

This is simple and maximizes age order, but one endpoint's old backlog can fill
every permit and delay a newly backlogged endpoint.

### Fixed per-endpoint concurrency cap

This prevents domination but wastes permits whenever fewer endpoints are
backlogged. It does not satisfy the required A-bursts-to-eight behavior.

### Strict round robin

Strict rounds are awkward across concurrent stateless workers and can also
leave capacity idle when an endpoint has no due row. The selected allocation
level expresses the intended behavior without requiring a coordinator process.

### Broker partitioning or Redis counters

Neither removes the need for an atomic PostgreSQL delivery transition. Adding
one now creates another consistency and operations boundary without evidence
that the PostgreSQL claim path is the bottleneck.

## Verification gate

Implementation is accepted only after focused PostgreSQL tests prove:

1. the six required examples above;
2. two concurrent workers never claim one delivery twice and a continuously
   backlogged endpoint makes progress under contention;
3. paused rows consume no capacity and concurrent disable remains serialized;
4. burst behavior still returns the full requested capacity;
5. crash recovery and stale-token rejection retain their existing behavior;
6. a representative noisy-neighbor workload records endpoint claim
   distribution, time-to-first-attempt for the small backlog, oldest-due age,
   claim p50/p95/p99, rows examined, lock waits, and connection-pool pressure;
7. the chosen query and index are inspected with
   `EXPLAIN (ANALYZE, BUFFERS)` before any performance claim is made.

## Consequences

The next implementation slice must first capture a global-FIFO baseline using
the same two-endpoint workload. It may then change claim SQL and indexes, but it
must not add a broker, Redis, a per-endpoint permit pool, or a new runtime
service. ADR-002 remains authoritative for claim ownership and failure
semantics.

## References

- [ADR-002: PostgreSQL-Backed Delivery Jobs with Leases and Claim Tokens](0002-postgresql-backed-delivery-jobs.md)
- [RelayForge Delivery Model](../DELIVERY_MODEL.md)
- [RelayForge Delivery Runtime Defaults](../DELIVERY_RUNTIME_DEFAULTS.md)

