# RelayForge Performance Runbook

## Scope and safety boundary

This runbook creates reproducible **local** performance evidence. It measures
the real publisher-to-PostgreSQL-to-worker-to-receiver path with a bounded
workload. It does not benchmark `https://gialong.duckdns.org`, publish a
Prometheus management endpoint, change worker concurrency, or assert a
production capacity figure.

The environment contains two observability roles:

- Spring Boot/Micrometer exposes measurements at each process's
  `/actuator/prometheus` endpoint.
- The opt-in local Prometheus service scrapes and stores those measurements;
  local Grafana queries Prometheus and renders one provisioned dashboard.

Neither Prometheus nor Grafana belongs to the EC2 production topology in
Portfolio v1.

## 1. Start the local stack and observability profile

From the repository root, with the ignored `.env` already configured:

```powershell
docker compose --profile observability up --build -d
docker compose ps
```

Open:

- dashboard: `http://localhost:5173`;
- Prometheus targets: `http://localhost:9090/targets`;
- Grafana: `http://localhost:3000/d/relayforge-performance`.

Both Prometheus targets must show `UP`: `relayforge-api` scrapes `api:8080`,
and `relayforge-worker` scrapes `receiver:8082`. The latter is intentional:
local worker mode shares the receiver network namespace, while still exposing
only its management servlet.

Grafana is anonymous **viewer-only** local tooling. It has no configured
administrator, no persistent credentials, and no public route.

## 2. Create an isolated benchmark fixture

Run:

```powershell
./scripts/setup-group17-loadtest-fixture.ps1
```

The script signs in with the ignored local owner credentials, creates one
project, one one-time publisher API key, and one enabled local success endpoint
for `performance.accepted`. It configures the receiver's in-memory signing
secret, then writes the project ID and raw publisher key only to ignored
`performance/.loadtest.env`. The secret is not printed, committed, put in k6
results, Grafana, or Prometheus labels.

Use `-Force` only when intentionally replacing that local fixture with a new
one. The benchmark creates durable local events/deliveries; reset the local
database only when that history is no longer useful:

```powershell
docker compose down --volumes
Remove-Item performance/.loadtest.env -ErrorAction SilentlyContinue
```

This is destructive to the local development database, never to EC2.

## 3. Record a bounded k6 baseline

The built-in scenario ramps from zero to two virtual users, then five, then
back to zero over one minute. Every publish command has a distinct idempotency
key made from a benchmark-run ID, virtual-user ID, and iteration. A duplicate
key would turn the test into idempotency replays rather than measure new event
acceptance.

```powershell
docker compose --env-file .env --env-file performance/.loadtest.env `
  --profile loadtest run --rm --no-deps k6
```

`--no-deps` is deliberate: the benchmark must not recreate or alter API,
worker, PostgreSQL, or receiver while it is measuring them. Start and verify
the stack first; a missing/unready API is a failed precondition, not load-test
traffic to retry through.

k6 writes an ignored machine-readable summary to
`performance/results/k6-summary.json`. Thresholds make the run fail if more
than 1% of HTTP requests/checks fail or if publish p95 exceeds one second.
Those are guardrails for this modest local fixture, not a public SLA.

Read the non-secret summary without interpreting k6's threshold booleans by
hand:

```powershell
./scripts/summarize-group17-k6-results.ps1
```

In k6 JSON, a threshold value of `true` means that threshold **failed**; the
summary script reports the inverse as `ThresholdsPassed`.

While k6 runs, inspect the Grafana dashboard:

- ready backlog and oldest-due age tell whether accepted work is accumulating;
- available worker permits tell whether local worker capacity is saturated;
- dispatch p95 and outcome rate separate receiver/worker behavior from API
  acceptance latency;
- Hikari active/pending connections indicate pool pressure;
- JVM heap/non-heap and GC pause rate show memory/collection pressure.

Record the actual k6 summary, machine/Docker resources, endpoint mode, and
Grafana observations before interpreting a result. Never place credentials,
payloads, or raw endpoint URLs in a report.

## 4. Capture JVM evidence during the same workload

JFR is a low-overhead JVM event recording. Start a bounded 90-second API
recording before the k6 command:

```powershell
$env:RELAYFORGE_JAVA_TOOL_OPTIONS = '-XX:StartFlightRecording=name=relayforge-api,filename=/tmp/relayforge-api.jfr,settings=profile,duration=90s,maxsize=64m'
docker compose up -d --force-recreate api
```

Run the k6 workload, wait until the 90-second recording ends, then copy the
artifact and restore the normal API launch settings:

```powershell
$apiContainer = docker compose ps -q api
docker cp "${apiContainer}:/tmp/relayforge-api.jfr" performance/results/relayforge-api.jfr
Remove-Item Env:RELAYFORGE_JAVA_TOOL_OPTIONS
docker compose up -d --force-recreate api
```

Open the `.jfr` file in Java Mission Control. Begin with CPU samples, Java
application threads, allocation pressure, garbage collections, and JDBC/HTTP
waits. JFR identifies *where* time is spent; Prometheus identifies *when* a
metric changed.

To ask the JVM for a point-in-time thread dump without stopping it:

```powershell
docker compose kill -s QUIT api
docker compose logs --tail=400 api | Set-Content performance/results/api-thread-dump.log
```

Look for many request threads waiting on database connections, blocked locks,
or outbound work. A single thread dump is a snapshot, not proof of a persistent
bottleneck; correlate it with metrics and JFR.

## 5. Investigate before tuning

Use this order before changing a runtime value:

1. Compare k6 publish latency with API Hikari/JVM metrics.
2. Compare accepted-event rate with backlog, permits, and dispatch p95.
3. If PostgreSQL is implicated, obtain the actual slow query and run
   `EXPLAIN (ANALYZE, BUFFERS)` against the local database only.
4. State one hypothesis, make one smallest relevant change, and rerun the
   identical fixture/workload.

Do not increase worker permits, Hikari size, thread pools, memory, or add a
cache merely because load is present. A change is accepted only if it improves
the relevant measured symptom without violating existing delivery invariants.

## 6. Measure the dashboard polling baseline before considering SSE

Phase 3 Slice 4.1 measures the existing authenticated Delivery workspace before
any live-update design is accepted. Its steady state contains five visible-tab
REST reads at a five-second interval: event list/detail, delivery list/detail,
and attempt list. Attempt detail is fetched on selection only; it does not
poll.

Start the normal local stack, then run:

```powershell
./scripts/measure-dashboard-polling.ps1
```

The script creates a fresh local project/key/slow-receiver fixture, waits until
the worker has begun its controlled attempt, then uses a 2.5-second initial
phase offset and executes four five-second polling cycles with the same
recurring routes as the dashboard. It snapshots
the API's `http_server_requests_seconds_count` counters immediately before and
after the cycles, records client round-trip summaries, and observes the delay
from the persisted retryable attempt completion to the next delivery-list
response. Its ignored result is `performance/results/dashboard-polling.json`.

The fixture credentials, raw API key, signing secret, IDs, payload, and exact
destination are neither printed nor written to that result. This is one local
dashboard-equivalent session, not a browser load test, production capacity
claim, or end-user latency SLA. The owner has accepted ADR-013 as a
learning-oriented exception after this run; it is not evidence of a
performance need for SSE.

## Evidence record template

Use this compact format in a future committed report after a completed run:

```text
Commit/image:
Machine and Docker resource limits:
Fixture endpoint mode:
k6 scenario and duration:
Accepted-event throughput:
Publish p50 / p95 / p99:
HTTP error rate and threshold result:
Backlog / permits / dispatch p95 observations:
JFR or thread-dump observation:
Hypothesis:
Change (if any):
Same-workload before/after comparison:
Limitations:
```

Until this contains actual output from a controlled run, RelayForge has
performance tooling, not a performance claim.
