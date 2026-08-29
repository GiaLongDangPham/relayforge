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

## References

- [Performance Runbook](PERFORMANCE_RUNBOOK.md)
- [Delivery Runtime Defaults](DELIVERY_RUNTIME_DEFAULTS.md)
- [Operations Runbook](OPERATIONS_RUNBOOK.md)
