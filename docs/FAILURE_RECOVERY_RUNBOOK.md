# RelayForge Local Failure and Recovery Runbook

## Purpose and safety boundary

This runbook proves the existing at-least-once delivery behavior using only the
local Docker Compose stack. It changes neither EC2, Docker Hub, nor the
production database. The exercise uses the local receiver's fixed endpoints:

| Endpoint | Behavior | Expected RelayForge result |
| --- | --- | --- |
| `/webhooks/fail` | Returns HTTP 500 | Retryable attempt and persisted backoff |
| `/webhooks/slow` | Responds after 12 seconds | `DISPATCH_TIMEOUT` at the 10-second dispatch deadline |
| `/webhooks/success` | Returns HTTP 200 | Successful finalization |

The receiver deliberately has one in-memory signing-secret slot. Do not run
this harness at the same time as another local demo that has outstanding
receiver deliveries.

## Preconditions

Start the local stack plus observability and wait for API, worker, receiver,
and Prometheus readiness:

```powershell
docker compose --profile observability up -d
```

The ignored root `.env` must have the local owner credentials. The harness
creates its own project, publisher key, and endpoint for every scenario. Raw
keys and signing secrets stay in process memory and are never printed or
committed.

## Run all controlled scenarios

```powershell
./scripts/group18-failure-recovery.ps1 -Scenario all
```

The script records a non-secret artifact at the ignored path
`performance/results/group18-failure-recovery.json`.

It proves these transitions:

1. `500` creates `RETRYABLE_FAILURE`; after the owner corrects the endpoint,
   the next ordinary retry is `SUCCEEDED`.
2. A response slower than the dispatch deadline becomes
   `RETRYABLE_FAILURE` with failure code `DISPATCH_TIMEOUT`; correcting the
   endpoint permits the next ordinary retry to succeed.
3. After the slow receiver has observed a started request, the script kills
   the **local** worker process and starts it again. Lease recovery records the
   first attempt as `UNKNOWN`; it never rewrites that history when the second
   attempt succeeds.

The script explicitly starts worker after `docker compose kill`. A Compose
`kill` is not itself a reliable local restart mechanism, so treating it as one
would accidentally test an indefinitely stopped worker rather than recovery.

## Run bounded-attempt exhaustion separately

```powershell
./scripts/group18-failure-recovery.ps1 -Scenario exhaustion
```

This intentionally takes several minutes. It preserves the real equal-jitter
backoff values: 2.5–5, 10–20, 40–80, then 150–300 seconds. Do not shorten those
values merely to make a demo finish quickly. The result must contain exactly
five `RETRYABLE_FAILURE` attempts and terminal delivery status `EXHAUSTED`; no
sixth automatic attempt exists. The ignored result is
`performance/results/group18-exhaustion.json`.

An owner may then use the ordinary dashboard replay flow for the exhausted
delivery. Replay creates a distinct child delivery; it does not mutate the
five-attempt original.

## Observe while the exercise runs

Use Grafana at `http://localhost:3000/d/relayforge-performance` or query
Prometheus directly. Read signals together:

- `relayforge_delivery_backlog{state="ready"}`: due enabled work; a delivery
  waiting for a future retry may correctly leave this at zero.
- `relayforge_worker_permits_available`: should return to eight after one
  attempt completes or times out.
- `hikaricp_connections_pending`: should remain zero in this bounded test;
  do not infer a pool problem from one slow receiver.
- `relayforge_delivery_attempts_total`: inspect outcome counts, not unbounded
  event/delivery identifiers.

## What this proves and does not prove

The exercise proves persisted state transitions, deadline handling, bounded
attempts, lease recovery, and the at-least-once duplicate trade-off using the
real local API/worker/PostgreSQL path. It does not prove a remote receiver's
idempotency, a production capacity figure, automatic recovery from a database
outage, or exactly-once delivery.

## References

- [Measured resilience evidence](RESILIENCE_EVIDENCE.md)
- [Delivery Model](DELIVERY_MODEL.md)
- [Runtime Defaults](DELIVERY_RUNTIME_DEFAULTS.md)
- [Operations Runbook](OPERATIONS_RUNBOOK.md)
- [Performance Runbook](PERFORMANCE_RUNBOOK.md)
