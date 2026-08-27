# RelayForge Operations Runbook

## Scope

This is a small Portfolio v1 operator runbook. It helps diagnose a local or temporary-cloud stack without editing PostgreSQL rows. It does not provide HA, alert thresholds, a managed Prometheus deployment, or a log-aggregation service.

## First checks

1. Probe API readiness: `GET /actuator/health/readiness` on the API port. A down result means RelayForge cannot safely accept durable work, commonly because PostgreSQL is unavailable.
2. Probe worker readiness on its management port. API readiness does not prove a worker is running.
3. Scrape `/actuator/prometheus` on both processes. Only health and Prometheus are exposed; a business route on the worker must be forbidden.

## Diagnose backlog

Read these Prometheus signals:

- `relayforge_delivery_backlog{state="ready"}`: enabled work that is due.
- `relayforge_delivery_backlog{state="paused"}`: due work held by disabled endpoints.
- `relayforge_delivery_oldest_ready_due_age_seconds`: how overdue the oldest enabled work is.
- `relayforge_worker_running`, `relayforge_worker_permits_available`, and `relayforge_worker_claimed_total`: worker lifecycle and local capacity.
- `relayforge_delivery_attempts_total`, `relayforge_delivery_dispatch_seconds`, recovery, and finalization metrics: dispatch outcome, latency, and recovery evidence.

If ready backlog and oldest-due age rise while worker-running is zero, restore or inspect the worker. If permits remain zero with slow dispatch latency, inspect receiver behavior and the configured outbound deadline before increasing concurrency. If paused backlog rises, inspect the endpoint enabled state; do not manually change delivery rows.

## Logs and correlation

Console output is ECS JSON by default. Start with a safe `trace.id`, project UUID, event UUID, delivery UUID, or attempt UUID. Worker completion/finalization entries include bounded outcome, failure code, HTTP status, duration, and attempt number. Never search for or paste raw credentials, signing secrets, URLs, or payloads into diagnostics.

## Expected failure behavior

| Symptom | Expected behavior | Operator action |
| --- | --- | --- |
| PostgreSQL unavailable | API readiness is down; worker claim/finalization later retries and durable state remains authoritative. | Restore database connectivity, then recheck readiness and backlog. |
| Worker stopped | API may still accept durable events; ready backlog grows. | Start the worker; lease recovery handles abandoned work. |
| Receiver timeout/5xx | Attempt outcome is recorded and retry scheduling follows the existing bounded policy. | Inspect safe attempt history and receiver health; do not force state transitions. |
| Endpoint disabled | Existing nonterminal deliveries remain pending but paused. | Enable it when delivery should resume. |
| Worker crashes after outbound I/O | A later recovery records `UNKNOWN` where needed; a retry can duplicate receiver side effects. | Treat receiver processing as idempotent and inspect the durable attempt history. |

## Local Compose commands

Use the Group 12/13 local commands in [Local Docker Demo](LOCAL_DOCKER_DEMO.md). For live inspection, `docker compose logs -f api worker` and the two `/actuator/prometheus` URLs are sufficient. Do not run destructive database commands during diagnosis.
