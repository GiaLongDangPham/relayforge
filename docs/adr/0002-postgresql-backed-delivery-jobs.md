# ADR-002: PostgreSQL-Backed Delivery Jobs with Leases and Claim Tokens

- Status: Accepted
- Date: 2026-08-09
- Decision owners: RelayForge project
- Supersedes: None

## Context

RelayForge acknowledges a publish request only after the immutable event and its complete routing-snapshot delivery set are durable. Delivery then continues asynchronously, survives process restarts, retries transient failures, and exposes attempt history.

The asynchronous transport must support:

- atomic event acceptance and delivery creation;
- multiple competing worker processes;
- scheduled retries;
- worker-crash recovery;
- endpoint pause behavior;
- bounded attempts and terminal history;
- at-least-once delivery with explicit ambiguous outcomes.

A broker could move ready work between processes, but PostgreSQL would still own projects, endpoints, events, delivery history, replay idempotency, and inspection queries. Publishing to both PostgreSQL and a broker would introduce a dual-write failure unless RelayForge also implemented an outbox or equivalent durable relay state. Portfolio v1 has no measured throughput or organizational requirement that justifies that additional stateful system.

The queue decision must therefore keep the correctness model simple without pretending database polling is free or exactly-once.

## Decision

Portfolio v1 will use PostgreSQL-backed delivery jobs. The persisted delivery state is the durable work record; RelayForge will not add a second generic job record that can drift from the delivery lifecycle.

Worker processes poll for due `PENDING` deliveries and claim a bounded batch in one short transaction. A candidate becomes `CLAIMED` only when it is still due, has remaining attempt budget, belongs to an enabled endpoint, and is otherwise eligible under the reviewed delivery model.

Every successful claim creates:

- a globally unique opaque claim token;
- a finite lease expiry based on PostgreSQL time;
- a committed transition from `PENDING` to `CLAIMED`.

The claim token is a fencing value for RelayForge's database state. Normal attempt start and finalization require the delivery to remain `CLAIMED`, the submitted token to remain current, and the lease to remain unexpired. Every transition out of `CLAIMED` invalidates its token and lease atomically.

Claiming, attempt start, outbound HTTP, and finalization remain separate steps:

1. claim eligible work and commit;
2. atomically start one attempt, snapshot endpoint configuration, consume attempt budget, and extend the lease once;
3. resolve, validate, sign, and send outside any database transaction;
4. conditionally finalize attempt and delivery state in a short transaction.

Portfolio v1 uses no periodic lease heartbeat. Exact batch size, polling interval, initial lease, attempt-execution lease, retry schedule, SQL, lock mode, index, and isolation level are follow-up decisions.

Lease recovery returns an expired pre-attempt claim to `PENDING` without consuming attempt budget. Recovery of an unfinished started attempt records immutable `UNKNOWN`, then schedules retry or exhaustion according to the five-attempt limit. A late result may be diagnostic evidence but cannot rewrite `UNKNOWN` or current delivery state.

This transport provides at-least-once processing. A claim token prevents stale database updates; it does not cancel an HTTP request already in progress or prevent duplicate receiver side effects. Portfolio v1 provides no delivery-order guarantee.

## Decision details

### Delivery state is the queue state

- `PENDING` plus due-time represents ready or scheduled work.
- `CLAIMED` plus token and lease represents temporary worker ownership.
- `SUCCEEDED`, `FAILED_PERMANENT`, and `EXHAUSTED` are terminal delivery states.
- Retry scheduling changes the existing delivery back to `PENDING` and creates a new attempt only when that attempt starts.
- Manual replay creates a new linked delivery rather than mutating a terminal delivery.
- `EXHAUSTED` is an operator-visible terminal state, not a broker dead-letter queue.

This avoids two competing sources of truth such as “delivery says pending but generic job says completed.”

### Batch claim contract

The future claim implementation must provide these behaviors without committing to SQL in this ADR:

1. select a bounded set of due nonterminal candidates without waiting behind work another worker is currently selecting;
2. check endpoint enabled state for candidate endpoint identities in one batch inside the same short transaction;
3. serialize a concurrent endpoint disable across eligibility check and claim commit;
4. change only eligible candidates to `CLAIMED` with distinct tokens and database-time leases;
5. leave disabled candidates `PENDING` and exclude them from claim capacity: for a static snapshot with `E` eligible due candidates and requested positive capacity `C`, return `min(C, E)` claims regardless of the count or ordering of paused candidates;
6. reserve `C` local dispatch permits before entering the claim transaction and never return more claims than those reserved permits;
7. release all `C` permits if the claim transaction fails, or immediately release `C - n` unused permits when the transaction returns `n` claims;
8. bind one remaining permit to each returned claim until that local claim-handling task has irrevocably stopped all HTTP and state-transition work for its token; lease expiry or recovery alone never releases a permit while the old local task is still running;
9. release every bound permit in a local `finally` path when its claim-handling task ends, whether finalization succeeds, becomes stale, or is abandoned to lease recovery;
10. commit before destination validation, signing, or network I/O.

The database-design slice must choose SQL, locks, isolation, indexes, and batch sizing that prove these behaviors under concurrency.

### Polling and backpressure

Polling is an operational cost, not a correctness mechanism by itself. The worker must use bounded batches and bounded concurrency. When no work is available, it must avoid a tight query loop. When backlog grows, it must not claim unbounded work into process memory or occupy a database connection while waiting on a receiver.

API and worker processes have separate connection pools even though they use the same PostgreSQL database. Pool sizing must leave capacity for API acceptance, finalization, and recovery under slow-receiver load. Exact values require load evidence.

Metrics must eventually expose due backlog, oldest due age, claim rate, attempt outcomes, recovery count, database-pool pressure, and outbound latency. These signals determine whether the transport is healthy and whether a broker discussion is evidence-based.

## Alternatives considered

### 1. Broker-backed jobs from the beginning

Kafka, RabbitMQ, or SQS could decouple ready-work transport from PostgreSQL polling and provide their own consumer coordination features.

This is rejected for Portfolio v1 because the business transaction still begins in PostgreSQL. Reliable publication would require an outbox or equivalent durable relay, duplicate-safe consumers, broker retry/dead-letter semantics, additional observability, and another local/cloud dependency. Different brokers also have materially different ordering, delay, acknowledgement, retention, and redelivery behavior; choosing one without workload evidence would hide rather than remove delivery complexity.

### 2. A generic PostgreSQL jobs table separate from delivery state

A reusable job framework could schedule many background task types.

This is rejected for the core delivery flow because job and delivery lifecycle could diverge, requiring cross-record reconciliation and duplicate terminal-state rules. Portfolio v1 has one correctness-critical background capability, so the delivery itself is the job. A generic job abstraction may be reconsidered only after multiple real job types demonstrate common semantics.

### 3. In-memory executor or scheduler

An in-process queue is easy to implement and has low polling overhead.

It is rejected because acknowledged work could disappear on API or worker restart, API and worker processes would not share work safely, and horizontal worker scaling would require another coordination mechanism.

### 4. Hold a row lock or database transaction during outbound HTTP

The locked row would visibly identify the active worker and simplify some completion races.

It is rejected because slow or failed receivers would retain database connections and locks for network-duration time, increase contention, interfere with unrelated work, and make connection-pool exhaustion a direct receiver-controlled failure mode.

### 5. Boolean claimed flag without a lease and token

This is simpler than a time-bounded claim.

It is rejected because a crashed worker could strand work indefinitely, and a stale worker could overwrite recovery or a newer worker's result. A lease provides recovery; the token condition fences stale database updates.

## Consequences

### Positive

- Event and complete delivery creation remain in one PostgreSQL transaction without broker dual-write.
- Delivery lifecycle has one durable source of truth.
- Scheduled retries, replay links, inspection history, and worker claims use one consistency boundary.
- Multiple workers can compete without a distributed lock service.
- Crash recovery is explicit and testable through lease expiry.
- Local development and temporary cloud operation require one stateful dependency instead of two.
- The design creates concrete learning opportunities in transactions, locking, indexes, query plans, connection pools, and failure recovery.

### Negative

- PostgreSQL handles both transactional application data and queue polling load.
- Polling adds idle queries and a latency-versus-load trade-off.
- Poor indexes, oversized batches, long transactions, or synchronized polling can cause contention and unfairness.
- API acceptance and worker progress share a database availability boundary.
- RelayForge must implement retry scheduling, lease recovery, backlog metrics, and terminal cleanup itself.
- Large retained history may interfere with hot-work queries unless retention and physical design are handled carefully.
- Claim tokens protect database transitions but cannot provide exactly-once HTTP side effects.

## Failure and operational implications

| Failure or race | Expected behavior under this decision |
| --- | --- |
| API transaction rolls back | Neither an acknowledged event nor a partial delivery set is required to exist. |
| API commit succeeds but response is lost | Publisher retry uses the same idempotency key and returns the original logical result. |
| Worker crashes before claim commit | Candidate remains `PENDING`; another worker may claim it. |
| Worker crashes after claim but before attempt start | Lease recovery clears the claim and returns it to `PENDING` without consuming an attempt. |
| Worker crashes after attempt start | Recovery records `UNKNOWN`; retry may produce an at-least-once duplicate. |
| Old worker finishes after lease recovery | Its stale or expired token cannot change delivery state; its HTTP side effect may already exist. |
| PostgreSQL fails before claim | Worker claims nothing and retries database access with bounded operational backoff. |
| PostgreSQL fails after outbound HTTP | Finalization may be retried only while the claim remains valid; otherwise lease recovery owns the ambiguous outcome. The same attempt does not resend HTTP. |
| Receiver blocks | HTTP timeout bounds worker occupancy; no claim transaction or database connection waits for the receiver. |
| Endpoint is disabled around claim | Claim eligibility is serialized with disablement; disabled work remains effectively paused. Attempt start revalidates enabled state. |
| Many workers poll together | Database locking and bounded batches must avoid duplicate current claims and limit contention; exact mechanism requires measured tests. |
| Backlog exceeds worker capacity | Due backlog and oldest-due age grow visibly; work is not loaded unboundedly into worker memory. |

## Guardrails and verification

The decision is not considered implemented until automated evidence demonstrates:

1. publish commit contains the event and complete routing-snapshot delivery set atomically;
2. concurrent workers cannot create two valid current claim tokens for one delivery;
3. each successful claim uses a unique token and PostgreSQL-based lease time;
4. disabled endpoints are not changed to `CLAIMED`, concurrent disable is serialized, and paused work does not starve enabled due work;
5. a stale token or expired lease changes no current delivery state;
6. claim and attempt-start transactions commit before outbound HTTP begins;
7. blocked receiver I/O holds no delivery row lock or database connection;
8. crash before attempt start consumes no attempt, while crash after attempt start becomes immutable `UNKNOWN`;
9. lease recovery cannot overwrite a newer claim and never assumes an unknown receiver side effect did not occur;
10. a fifth retryable or unknown attempt becomes `EXHAUSTED` without a sixth attempt;
11. a live worker has at most one local claim-handling task per bound permit; failed or short claims release unused permits immediately, and a bound permit is not released until its old local task has stopped even if the database lease expires;
12. a claim-selection fixture containing `P` paused candidates and `E` eligible due candidates returns `min(C, E)` claims for positive requested capacity `C`, independent of `P` and row ordering. A multi-cycle variant drains the original `E` candidates in `ceil(E / C)` cycles only under explicit conditions: no concurrent mutation, retry, or new arrival; each prior batch becomes terminal and returns its permits before the next cycle; and every cycle begins with `C` free permits;
13. failure tests cover PostgreSQL loss before claim and after outbound HTTP.

No performance number is claimed until the workload, dataset, environment, and before/after measurement are recorded.

### Required performance evidence

Before making a queue-performance claim, the benchmark document must record exact values for total rows, state distribution, paused-to-enabled ratio, due-time distribution, payload-size distribution, worker count, claim capacity, polling interval, connection-pool sizes, PostgreSQL resources, and test duration. The run must report claim throughput, p50/p95/p99 claim latency, lock waits, rows examined versus claimed, database resource usage, and connection-pool pressure.

This ADR sets no pass/fail performance threshold before a baseline exists. After the baseline workload is recorded, a focused decision must define any target used as a release gate.

## Revisit triggers

PostgreSQL-backed jobs should be reconsidered when measured evidence shows one or more of the following after reasonable query, index, batching, retention, and pool tuning:

- claim polling or lock contention materially harms API transaction latency;
- oldest-due age or claim latency remains unacceptable at the required workload while PostgreSQL has become the limiting resource;
- queue polling consumes a disproportionate share of database CPU, I/O, connections, or cost;
- hot delivery work and retained history cannot be isolated adequately with practical PostgreSQL physical design;
- multiple independent consumers need a durable event stream rather than one internal delivery-work owner;
- a required topology needs transport-level regional decoupling or independent service ownership;
- a broker capability such as independently retained event replay solves a documented product requirement that PostgreSQL delivery state cannot meet reasonably.

If a broker is proposed, a superseding ADR must specify:

- how database commit and message publication become atomic or recoverable;
- producer and consumer idempotency boundaries;
- acknowledgement, redelivery, retry, delay, and dead-letter semantics;
- ordering guarantees or explicit lack of ordering;
- cutover, replay, rollback, and reconciliation procedures;
- measured performance and operational-cost comparison;
- which system becomes authoritative for delivery state.

Technology familiarity, resume keywords, or an unmeasured belief that a broker is more scalable are not sufficient triggers.

## Follow-up decisions

- Delivery, attempt, idempotency, and diagnostic persistence model.
- Claim and recovery SQL, constraints, indexes, lock modes, and isolation evidence.
- Batch size, polling interval, worker concurrency, and shutdown behavior.
- Retry delays, jitter, HTTP timeout, initial lease, and attempt-execution lease.
- Connection-pool budgets for API and worker processes.
- Backlog, claim, recovery, and database-pressure metrics.
- Terminal-history cleanup and physical-data-management strategy.

## References

- [ADR-001: Modular Monolith with Separate API and Worker Runtime Modes](0001-modular-monolith-api-worker-runtime.md)
- [RelayForge Delivery Model](../DELIVERY_MODEL.md)
- [RelayForge Architecture Boundaries](../ARCHITECTURE_BOUNDARIES.md)
- [RelayForge Portfolio v1 Requirements](../REQUIREMENTS.md)
