# RelayForge Local Performance Baseline

Status: measured local baseline, not a production capacity claim  
Date: 2026-08-29

## Scope

This record captures one controlled Group 17 run of the real local
publisher-to-PostgreSQL-to-worker-to-success-receiver path. It is evidence for
the current implementation and a comparison point for a later, identical run.
It is **not** a benchmark of `https://gialong.duckdns.org` and must not be used
as an EC2 capacity or SLA claim.

## Environment

| Item | Value |
| --- | --- |
| Host CPU | 12th Gen Intel Core i5-12450H |
| Host RAM | 15.7 GiB |
| Docker Desktop | 29.6.2; 12 CPUs and 7.6 GiB available to containers |
| Data path | Local Compose PostgreSQL 17.10, API, worker, and bounded Java success receiver |
| Observability | Local Prometheus 3.5.0 and Grafana 12.0.2 only |
| Receiver behavior | One enabled local endpoint returns success; no intentional delay or failure |

## Workload

The k6 `publish-events.js` scenario used a fresh local project and raw
publisher key held only in ignored `performance/.loadtest.env`. It ramped to
two virtual users for 15 seconds, to five for 30 seconds, and down for 15
seconds. Every request used a unique idempotency key, so each accepted request
created new delivery work instead of exercising an idempotency replay.

The test used `docker compose ... run --no-deps k6`. That flag is part of the
measurement contract: the load-generator container must not recreate the API
or any dependency while measuring it.

## Results

| Observation | Measured value |
| --- | ---: |
| Accepted publish requests | 1,275 |
| HTTP request error rate | 0% |
| k6 checks | 100% passed |
| k6 thresholds | all passed |
| Publish p50 | 10.71 ms |
| Publish p95 | 16.49 ms |
| Worker delivery attempts | 1,275 `succeeded` |
| Ready backlog after drain | 0 |
| Available worker permits after drain | 8 of 8 |
| Dispatch p95 over the dashboard one-minute window | 3.34 ms |
| Pending Hikari connections | 0 |

The delivery-attempt count matches the accepted publish count in this run
because the fixture creates exactly one enabled success endpoint and the
worker counters were reset before the workload. The dashboard also showed only
minor-GC pause activity; it did not demonstrate a memory or connection-pool
bottleneck under this bounded workload.

## JVM diagnostics

A 90-second API Java Flight Recorder artifact and an API thread dump were
captured under `performance/results/` (ignored by Git). Sending `SIGQUIT` to
the JVM printed a full thread dump while the API container stayed running.
These artifacts are for local investigation in Java Mission Control; they are
not interpreted as proof of a bottleneck without a specific symptom.

## Interpretation and limitation

No application tuning change was accepted. The workload stayed below the
current local worker and database capacity, so increasing permits, Hikari
limits, heap, or thread pools would be unmeasured speculation.

An early harness attempt did reveal a measurement defect: a normal Compose
`run` with service dependencies recreated the API and produced connection
errors during startup. The final run removed that interference with
`--no-deps`; this is a test-harness correction, not a RelayForge performance
optimization. Repeat this exact workload before and after any future tuning
change, then compare the same metrics and diagnostic context.

## Phase 2A claim-order baseline: noisy neighbor

This focused PostgreSQL integration fixture establishes the behavioral baseline
for the pre-fairness global-FIFO claim query. It is deliberately not a k6 run,
an elapsed-time benchmark, or a production-capacity claim.

| Item | Recorded value |
| --- | --- |
| Fixture | Endpoint A: 32 due deliveries at `now - 60 s`; endpoint B: 2 due deliveries at `now - 30 s` |
| Claim capacity | 8 free permits per wave |
| Completion simulation | Normal claim, attempt-start, and conditional finalization using a synthetic HTTP 204 observation |
| Current selection | Global `ORDER BY due_at, id` FIFO |
| Waves 1–4 | 8 A deliveries each; 32 A deliveries total |
| First B claim | Wave 5, after the earlier A backlog was drained |
| Verification | `DeliveryClaimIntegrationTests`: 4 tests passed with PostgreSQL 17.10 Testcontainers on 2026-08-31 |

The fixture makes the noisy-neighbor problem concrete: if A's attempts are
slow, B receives no new claim until the earlier A backlog has consumed four
complete eight-permit waves. It does not measure that waiting time in
milliseconds because no real receiver is blocked in this test. The next slice
will replace the candidate-selection rule under the same fixture, then compare
claim distribution before considering query-plan or load evidence.

### Slice 3 comparison: endpoint-fair selection

ADR-007 replaced the candidate order with an endpoint allocation level:
committed endpoint `CLAIMED` count plus one-based pending ordinal inside that
endpoint. This was verified with the same PostgreSQL 17.10 Testcontainers
fixture, not with a new timing benchmark.

| Scenario | FIFO baseline | Endpoint-fair result |
| --- | --- | --- |
| A: 32 earlier due; B: 2 later due; capacity 8 | First wave: A 8 / B 0; B first in wave 5 | First wave: A 6 / B 2 |
| A and B both have deep due backlog; capacity 8 | Global age order can favor older A rows | A 4 / B 4 |
| Only A has due work; capacity 8 | A 8 | A 8 |
| A owns 7 active claims and one A row is pending; B receives one due row | Not a fairness guarantee | The next claim selects B without cancelling A work |

`DeliveryClaimIntegrationTests` passed 7/7 on 2026-08-31. The fixture keeps
the normal claim, attempt-start, and finalization boundaries, and clears its
test data after every scenario because the scheduler is intentionally global.
It proves allocation behavior, not exact fairness under simultaneous claim
snapshots, elapsed response latency, query cost, or production capacity.

### Slice 5: fair candidate-query plan

`FairClaimQueryPlanIntegrationTests` runs the same SQL template used by
`JdbcDeliveryStore` under `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)`. Its
PostgreSQL 17.10 Testcontainers fixture has two enabled endpoints and 128 due
pending deliveries (64 per endpoint); it requests eight candidates.

| Observation | Recorded value |
| --- | ---: |
| Returned candidate rows | 8 |
| Planning time | 0.464 ms |
| Execution time | 2.703 ms |
| Root shared-hit blocks | 3,987 |
| Pending-row scans | `Index Scan` via `ix_deliveries_pending_due_at_id` |
| Current-claim allocation | `Bitmap Index Scan` via `ix_deliveries_claimed_lease_expires_at_id` |

The plan also includes windowing, sorting, a materialized due-row CTE, and a
lock node: those are the expected costs of endpoint-fair ranking and
`FOR UPDATE SKIP LOCKED`, not proof of a defect. The existing V8 indexes are
therefore retained. This is a warm, local, 128-row fixture with no receiver
traffic, database history volume, or lock contention; it is not a performance
baseline, capacity claim, or justification to tune worker permits/pool sizes.

## References

- [Performance Runbook](PERFORMANCE_RUNBOOK.md)
- [Delivery Runtime Defaults](DELIVERY_RUNTIME_DEFAULTS.md)
- [Operations Runbook](OPERATIONS_RUNBOOK.md)
