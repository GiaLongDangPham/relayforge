# RelayForge Compact Agent Context

Last updated: 2026-08-09

Use this as the default working memory for a RelayForge task. It is intentionally compact; detailed rules live in the linked documents. `PROJECT_STATUS.md` remains the full durable ledger.

## Mission and current phase

- Build a portfolio-grade **outbound webhook delivery platform** in roughly 80-96 hours. Correctness, failure handling, testing, and observability come before scale.
- Current phase: **Phase 0 — requirements and architecture**.
- Current implementation: minimal Spring Boot backend (Java 25, Spring Boot 4.1.0, Maven, Web MVC) and React/Vite/TypeScript frontend skeletons. No product behavior, schema, migrations, or API contract exists yet.
- Active architecture work: bounded delivery runtime defaults are recorded in `docs/DELIVERY_RUNTIME_DEFAULTS.md`. The next slice is database model Part 1 for identity, project, and endpoint configuration only; do not include delivery tables, migrations, APIs, or production code.

## Product contract to preserve

- A publisher submits an immutable event with an `Idempotency-Key` scoped to a project. Same key + same command returns the original result; different content conflicts.
- Acceptance atomically persists the event and every delivery selected from enabled exact event-type subscriptions at that moment. Later subscription changes do not alter that routing snapshot.
- A delivery allows at most five started attempts. HTTP 2xx succeeds; 408, 429, 5xx, timeout, and network failures retry; other 4xx and blocked destinations are permanent failures.
- The system provides **at-least-once** delivery. Receivers must tolerate duplicates; no ordering guarantee exists.
- Workers claim due `PENDING` deliveries in a short PostgreSQL transaction using a finite lease and opaque token. Commit before outbound HTTP. Completion is conditional on the current, unexpired token.
- `STARTED` consumes attempt budget. A lease recovery turns an unfinished started attempt into immutable `UNKNOWN`; late worker results are diagnostics and must not rewrite current state.
- Disabled endpoints block new attempts but cannot cancel an HTTP request already begun. Redirects are disabled; production endpoints must be public HTTPS (local HTTP only in development).

## Architecture contract to preserve

- One modular-monolith artifact/image runs exactly one explicit mode: `api` or `worker`. They share PostgreSQL and never call each other via HTTP.
- Business modules: `identity`, `project`, `endpoint`, `delivery`. Dependencies are acyclic: `endpoint -> project`; `delivery -> project, endpoint`; `identity` and `project` have no business-module dependencies.
- Modules expose narrow commands, queries, identifiers, results, and ports—not JPA entities, repositories, or mutable domain objects. Each module owns persistence.
- `delivery` owns publish idempotency, events, deliveries, attempts, replay, claim/retry/recovery, and worker orchestration. It calls narrow `endpoint` contracts inside short transactions for routing, claim eligibility, and attempt snapshots.
- Persisted delivery state is the v1 PostgreSQL job; there is no separate generic job record or broker. A worker reserves local dispatch permits before a bounded claim, and each returned claim keeps one permit until its old local task has stopped all HTTP/state work.
- Initial delivery runtime values: 2-second connect, 10-second dispatch, 15-second initial lease, 20-second attempt lease, 5-second recovery scan, 20-second shutdown, retry bases 5/20/80/300 seconds with equal jitter, and 8 permits polled every 500 ms plus 0-100 ms jitter.

## Work routing and durable memory

| If changing… | Authority |
| --- | --- |
| Scope and acceptance criteria | `docs/REQUIREMENTS.md` |
| State machine or fault behavior | `docs/DELIVERY_MODEL.md` |
| Module/runtime/transaction boundary | `docs/ARCHITECTURE_BOUNDARIES.md` |
| Architectural alternative or reversal | `docs/adr/` |
| Progress, decisions, evidence, next slice | `PROJECT_STATUS.md` |

For every task: read this file, inspect Git status, declare one slice, and inspect only routed files. Update this file when the current phase, active slice, or a summarized invariant changes. Update `PROJECT_STATUS.md` after a completed slice with only auditable facts and actual verification output.

## Deferred unless evidence justifies it

Kafka/RabbitMQ/SQS, Redis, microservices, Kubernetes, strict ordering, inbound webhooks, organization RBAC, SSE/WebSockets, and multi-region deployment.
