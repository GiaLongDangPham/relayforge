# RelayForge Delivery Runtime Defaults

Status: Phase 0 baseline
Last updated: 2026-08-09

## 1. Purpose and boundary

This document chooses bounded initial runtime values for RelayForge Portfolio v1:

- outbound HTTP deadlines;
- claim and attempt-execution leases;
- retry delays and jitter;
- worker polling, claim capacity, and local concurrency;
- lease recovery and graceful shutdown.

These are conservative starting values for a small portfolio deployment. They are configuration defaults, not an SLA or a performance claim. Load tests and production-like failure exercises may justify changing them while preserving the reviewed delivery invariants.

This slice does not define database tables, SQL, indexes, isolation levels, Java thread type, HTTP-client implementation, API contracts, or cloud service configuration.

## 2. Timing model

RelayForge uses two kinds of time deliberately:

- PostgreSQL time computes persisted due-times and lease expiries;
- a monotonic process timer enforces the local wall-clock dispatch deadline.

A worker never converts its local wall clock into an authoritative database timestamp. It supplies bounded durations; PostgreSQL applies those durations to PostgreSQL time.

The attempt timing relationship is:

```text
10 s dispatch deadline
+ 5 s finalization margin
+ 5 s safety cushion
= 20 s attempt-execution lease
```

The safety cushion covers local scheduling, destination validation, transaction acquisition, and small runtime pauses. It does not promise that finalization will survive a PostgreSQL outage. If the current token or lease becomes invalid, recovery owns the outcome.

## 3. HTTP, lease, recovery, and shutdown defaults

| Setting | Initial default | Reason | Failure risk and tuning signal |
| --- | --- | --- | --- |
| Connection-establishment timeout | 2 seconds | Fails an unreachable destination quickly while leaving most of the dispatch budget for request/response work. | Too low causes connect failures on valid high-latency paths; too high consumes most of the dispatch deadline. Inspect connect latency and connect-timeout count. |
| Total dispatch deadline | 10 seconds | Bounds DNS resolution, destination validation, connect, request write, response headers, and the bounded response preview under one local deadline. | Too low creates avoidable retry duplicates; too high occupies worker permits and delays recovery. Inspect outbound p95/p99 latency, timeout rate, and receiver test behavior. |
| Finalization margin | 5 seconds | Allows short PostgreSQL finalization retries after HTTP ends without resending the request. | Too low increases `UNKNOWN` recovery after observed responses; too high requires a longer lease. Inspect finalization latency, database errors after HTTP, and late diagnostics. |
| Attempt-lease safety cushion | 5 seconds | Separates the normal dispatch-plus-finalization budget from lease expiry during small scheduling or runtime pauses. | Too low creates stale completions during normal pauses; too high delays recovery after a crash. Inspect lease-expired attempts, JVM pauses, and claim-token rejection counts. |
| Initial claim lease | 15 seconds | A permit is reserved before claim, so attempt start should happen quickly; 15 seconds leaves recovery room without hiding a stalled local task for long. | Too low causes pre-attempt recovery during normal scheduling; too high delays recovery of a crashed worker. Inspect claim-to-attempt-start latency and expired pre-attempt claims. |
| Attempt-execution lease | 20 seconds from attempt start | Satisfies the 10 + 5 + 5 timing relationship and replaces the initial lease once when `STARTED` is committed. | Too low rejects valid finalization; too high delays ambiguous-outcome recovery. Inspect stale finalization, `UNKNOWN`, late diagnostic, and recovery latency. |
| Expired-lease recovery interval | 5 seconds | Bounds how long expired claims normally wait for discovery without creating a hot recovery loop. | Too low adds idle database load and lock competition; too high increases oldest-expired-claim age. Inspect recovery query rate, rows recovered, lock waits, and oldest expired age. |
| Graceful-shutdown deadline | 20 seconds | Stops new claims immediately and gives a just-started dispatch its normal 10 + 5 + 5 budget before process exit. | Too low creates avoidable recovery and duplicate risk during deploys; too high slows rollout. Inspect unfinished local tasks and recovered claims after shutdown. |

The connect timeout is a sub-deadline. Hitting either connect timeout or the total dispatch deadline produces a retryable outcome if the worker still owns a valid claim.

The 10-second dispatch deadline must be enforced around the entire dispatch operation. An HTTP-client request timeout alone is insufficient if DNS resolution or pre-connect work can escape it.

## 4. Finalization retry inside the lease

After observing an HTTP result, RelayForge may retry only the PostgreSQL finalization operation while the same token remains current and unexpired. It never resends HTTP inside that attempt.

The initial finalization-retry delays are:

```text
100 ms, 250 ms, 500 ms, then 1 second capped
```

Each delay uses equal jitter between half the listed value and the listed cap, avoiding a zero-delay hot loop. A retry is not started unless a PostgreSQL-time check shows at least 1 second remains before lease expiry. The local claim-handling task keeps its dispatch permit until it stops finalization work or becomes stale.

This retry loop is bounded by the current lease rather than an attempt count. PostgreSQL time remains authoritative when deciding whether a conditional finalization may commit.

## 5. Delivery retry schedule

A delivery permits at most five started attempts. A retryable or `UNKNOWN` outcome uses capped exponential backoff with a 5-second base, multiplier 4, and 300-second cap:

| Completed attempt | Next attempt | Base delay | Equal-jitter range |
| --- | --- | --- | --- |
| 1 | 2 | 5 seconds | 2.5 to 5 seconds |
| 2 | 3 | 20 seconds | 10 to 20 seconds |
| 3 | 4 | 80 seconds | 40 to 80 seconds |
| 4 | 5 | 300 seconds | 150 to 300 seconds |
| 5 | None | None | Delivery becomes `EXHAUSTED`; no sixth attempt is scheduled. |

Equal jitter is calculated as:

```text
effectiveDelay = baseDelay / 2 + uniform(0, baseDelay / 2)
```

The worker supplies the selected duration to persistence; the absolute due-time is PostgreSQL time plus that duration. Randomness must be injectable so tests can prove the lower and upper bounds deterministically.

Portfolio v1 uses the same schedule for network errors, timeout, HTTP 408, 429, 5xx, and recovered `UNKNOWN` attempts. It does not honor `Retry-After` initially. Supporting that header later requires a bounded maximum, parsing rules, and tests showing it improves receiver interoperability without creating unbounded delays.

The schedule is intentionally short enough for a portfolio demonstration but long enough to expose persisted scheduling, jitter, exhaustion, and backlog behavior. It is not claimed to be suitable for every webhook product.

## 6. Polling, claim capacity, and local concurrency

| Setting | Initial default | Reason | Failure risk and tuning signal |
| --- | --- | --- | --- |
| Maximum local in-flight claim tasks | 8 per worker process | Conservative for a small cloud container while still demonstrating concurrency and multiple workers. | Too low limits throughput; too high increases sockets, memory, receiver pressure, and completion contention. Inspect throughput, outbound latency, CPU, memory, and pool pressure. |
| Maximum claim capacity | `min(8, free dispatch permits)` | Creates no local claimed-work queue beyond actual execution capacity. | Larger prefetch risks lease expiry before attempt start; smaller batches add claim overhead. Inspect claim-to-start latency and permits in use. |
| Normal polling interval | 500 milliseconds | Keeps demo queue latency responsive without an aggressive idle query loop. | Too low increases empty polls and database load; too high increases oldest-due age. Inspect empty-poll ratio, query rate, database CPU, and oldest-due age. |
| Poll jitter | Uniform 0 to 100 milliseconds added to each interval | Reduces synchronized polling when multiple worker processes start together. | Too little can create periodic contention; too much adds queue latency. Inspect claim lock waits and poll-time clustering. |
| Poll behavior with no free permit | Skip the claim query | Prevents claiming work that cannot enter the local dispatch pipeline. | Incorrect permit accounting can idle capacity or over-claim. Inspect free permits, active tasks, and claimed-but-not-started count. |

The permit limit is the concurrency contract regardless of whether implementation later uses platform threads, virtual threads, `CompletableFuture`, or an asynchronous HTTP client. Choosing a Java execution mechanism is a separate implementation decision that must not bypass the permit limit.

A failed claim transaction releases every permit reserved for that call. A short claim returning `n` rows releases unused permits immediately and binds exactly one permit to each of the `n` returned claims. A bound permit is released in a local `finally` path only after that task has stopped all HTTP and state-transition work for its token; lease expiry alone does not release it while stale local work is still running.

Claim capacity is shared by a work-conserving endpoint-fair selection policy;
it is not divided into fixed endpoint reservations. With eight free permits and
deep backlog on endpoints A and B, a fresh allocation targets 4/4. If B has no
due work, A may use all eight. If B later becomes due while A occupies the
permits, A is not preempted; B is preferred when capacity next becomes free.
See [ADR-007](adr/0007-work-conserving-endpoint-fair-dispatch.md).

## 7. Graceful shutdown behavior

On shutdown signal, a worker:

1. stops scheduling polls and recovery scans;
2. reserves no new permits and starts no new claim transaction;
3. allows already-bound local tasks to continue within the 20-second shutdown deadline;
4. keeps the normal token, lease, dispatch deadline, and finalization rules;
5. exits when all local tasks finish or the deadline expires;
6. performs no bulk “unlock” update during process termination.

After a forced deadline exit, PostgreSQL lease recovery handles remaining claims. Shutdown never marks an uncertain started attempt successful, failed, or retryable merely because the process is exiting.

## 8. Configuration validation rules

Startup must reject configuration that violates any of these rules:

1. every duration is positive;
2. connect timeout is less than the total dispatch deadline;
3. attempt-execution lease is at least dispatch deadline + finalization margin + safety cushion;
4. initial claim lease is greater than the 5-second claim-to-start warning threshold;
5. recovery interval is less than both lease durations;
6. graceful-shutdown deadline is at least dispatch deadline + finalization margin;
7. worker concurrency and maximum claim capacity are positive;
8. claim capacity never exceeds the permits reserved for that claim call;
9. retry base delays are positive and strictly increasing;
10. jitter never produces a negative delay or a delay above its base delay.

The 5-second claim-to-start threshold is an operational warning, not permission to wait five seconds deliberately. Because a permit is reserved before claim, normal processing should cross the attempt-start boundary immediately after claim commit.

## 9. Metrics and tuning triggers

| Area | Minimum evidence | Reconsider the initial value when |
| --- | --- | --- |
| Connect and dispatch deadlines | Connect latency, outbound p50/p95/p99, timeout count, attempt outcome | Valid receivers frequently time out, or slow receivers hold permits long enough to harm backlog age. |
| Initial claim lease | Claim-to-start p50/p95/p99, expired pre-attempt claim count | Normal scheduling approaches the lease, or crash recovery is unnecessarily slow. |
| Attempt-execution lease and margin | Finalization latency, database error after HTTP, `UNKNOWN`, stale-token rejection, late diagnostic | Valid completions become stale during healthy operation, or crash recovery waits materially longer than needed. |
| Retry schedule | Retry count by attempt, time-to-success, exhausted count, receiver recovery pattern | Retries amplify outages, demo timing is impractical, or measured receivers need longer recovery windows. |
| Poll interval and jitter | Empty-poll ratio, claim query rate/latency, lock waits, oldest-due age | Idle database cost is high, polls synchronize, or due work waits too long. |
| Concurrency and claim capacity | Active permits, throughput, CPU, memory, sockets, database-pool pressure | Capacity is unused, backlog grows, or downstream/database pressure rises. |
| Recovery interval | Recovery query rate, recovered count, oldest expired age | Recovery load is wasteful or abandoned work remains expired too long. |
| Shutdown deadline | Shutdown duration, forced exits, claims recovered after deploy, duplicate attempts after deploy | Deploys are too slow or routinely abandon otherwise finishable work. |

Every tuning change must record the workload, old value, new value, reason, and before/after observation. A changed number is not automatically a performance improvement.

## 10. Required future test evidence

Implementation must eventually prove:

1. startup accepts the baseline values and rejects every invalid timing relationship;
2. connect timeout and total dispatch deadline are enforced independently;
3. the worker stops waiting at the outer dispatch deadline across DNS, validation, connect, request, and bounded response read; client-specific tests contain any underlying operation that does not cancel promptly;
4. attempt start changes the lease to 20 seconds using PostgreSQL time;
5. finalization retries database work only and never resends HTTP;
6. finalization stops when the token is stale, the lease is expired, or less than the configured remaining-time guard exists;
7. retry jitter stays within each documented range under deterministic randomness;
8. attempts 1 through 4 schedule the documented ranges and attempt 5 schedules nothing;
9. no claim query runs when the worker has zero free permits;
10. a worker never owns more local claim-handling tasks than eight bound permits;
11. unused permits are released after failed or short claim transactions;
12. lease expiry does not release a permit while the stale local task still runs;
13. shutdown prevents new claims and either finishes tasks within 20 seconds or leaves them recoverable by lease expiry;
14. a controlled workload records the metrics required to tune polling, concurrency, timeouts, and leases;
15. endpoint-fair selection proves equal-backlog sharing, single-endpoint burst,
    and next-free-slot progress for a newly backlogged endpoint.

Tests should use short overridden durations where waiting on the real baseline would make the suite slow. The production defaults and the relationships between them still require configuration-binding tests.

## 11. Decisions still deferred

- Exact configuration property names and Spring binding mechanism.
- Java concurrency mechanism and HTTP-client implementation.
- Database schema, claim/recovery SQL, locks, isolation, indexes, and query plan.
- API and worker connection-pool sizes.
- Circuit breaker; none is added without a demonstrated failure it improves beyond timeout, concurrency limit, and retry backoff.
- Per-endpoint hard concurrency or request-rate limits; endpoint fairness shares
  new claim opportunities but does not enforce either limit.
- `Retry-After` support and maximum accepted delay.
- Environment-specific overrides after local and cloud measurements exist.

These defaults may be revised through measured evidence, but revisions must preserve at-least-once semantics, token fencing, bounded attempts, PostgreSQL time authority, and the no-transaction-during-HTTP invariant.
