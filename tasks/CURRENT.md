# Current Task

Status: Completed

## Goal

Complete Group 13 operational observability: expose tightly scoped API/worker health and Prometheus metrics, emit safe structured delivery logs, and record an operator failure runbook without adding an observability platform.

## Decisions

- Use Spring Boot Actuator and Micrometer Prometheus, not a dashboard/ELK/tracing platform.
- Run worker mode as a management-only servlet on a distinct internal port; no owner or publisher adapter is activated and a fallback security chain denies all non-management requests.
- Use only bounded state/outcome metric tags. Identity and correlation values belong in structured logs, never metric tags.
- Refresh the API backlog snapshot periodically so scrape frequency cannot directly create an unbounded database-query load.

## Out of scope

Prometheus/Grafana deployment, ELK, OpenTelemetry collector/tracing backend, alerting, metrics dashboard, cloud networking, retention, and changes to delivery semantics.

## Evidence required

- Worker integration proves health and Prometheus are reachable while a worker business API is forbidden.
- Rebuilt Compose exposes API and worker management ports and preserves the Group 12 smoke flow.

## Verification evidence

- Focused worker composition and delivery-processor verification passed with PostgreSQL Testcontainers. It proves worker readiness/Prometheus HTTP exposure, no business adapter/session repository, and a deny-all fallback for `/api/v1/**`.
- API composition test and compile pass after anchoring the trace filter to Spring Security's ordered `SecurityContextHolderFilter`, rather than to a custom publisher filter.
- Rebuilt Compose reports API and worker `Up`; both readiness endpoints return `UP`, and both Prometheus endpoints include RelayForge metrics.
- `group12-smoke.ps1 -VerifyRestart` passed: CSRF mutations, idempotent publish, valid receiver HMAC, one successful delivery attempt, and PostgreSQL-backed session/event persistence across API/worker restart.
- Browser acceptance after the restart passed: the dashboard at `http://localhost:5173/` reached its sign-in state with no console errors.
