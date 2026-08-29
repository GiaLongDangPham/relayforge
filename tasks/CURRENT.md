# Current Task

Status: Completed

## Goal

Complete Group 17: create a repeatable, local-only performance evidence
workflow for RelayForge. It must expose the existing API/worker metrics to a
Prometheus server, visualize bounded operational/JVM signals, generate a safe
publisher workload, and capture JVM diagnostics without claiming a performance
result before a controlled run has produced one.

## Decisions

- Load generation targets only the local Docker Compose stack. The public EC2
  host receives no benchmark traffic.
- Prometheus and Grafana are opt-in local Compose profiles. They do not change
  the production topology or expose management routes publicly.
- The workload uses a real publisher API key and one enabled local receiver
  endpoint, but its generated key/configuration remains ignored and local.
- The initial workload is bounded and measures publish acceptance plus the
  resulting worker/delivery signals. It does not pretend to model every SaaS
  client pattern.
- JFR and JVM thread dumps are diagnostic artifacts. Any tuning change requires
  a measured bottleneck and a before/after comparison under the same workload.

## Completion evidence

- The local observability profile started Prometheus and Grafana. Prometheus
  validated its configuration and scraped both API and worker targets as `UP`.
- Grafana rendered its provisioned local performance dashboard. The regular
  React dashboard also rendered after the final API restart.
- The ignored fixture generated a real publisher key without printing or
  tracking it. The final bounded k6 run used `--no-deps`, accepted 1,275
  requests with zero HTTP errors, and passed every threshold.
- After worker drain, Prometheus reported 1,275 succeeded attempts, zero ready
  backlog, eight available permits, and zero pending Hikari connections.
- A bounded API JFR recording and a live API thread dump were captured under
  ignored `performance/results/`; normal API launch settings were restored.

## Out of scope

Production load testing, a managed observability backend, alerting, remote
metrics/log aggregation, autoscaling, and speculative changes to worker
permits, Hikari, transactions, indexes, or delivery semantics.

## Evidence required

- Local Compose can start the opt-in observability services and Prometheus
  successfully scrapes both API and worker management endpoints.
- Grafana provisions one non-secret operational dashboard with bounded labels.
- A setup flow creates local benchmark credentials without writing secrets to
  Git or benchmark output.
- k6 validates the configured publisher inputs, uses unique idempotency keys,
  records a machine-readable summary, and has explicit thresholds.
- JFR/thread-dump capture steps are repeatable and documentation distinguishes
  baseline evidence from a performance claim.
