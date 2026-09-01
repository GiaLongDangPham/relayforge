# RelayForge Agent Context

Last updated: 2026-09-01

RelayForge is a portfolio learning project: an outbound webhook delivery platform that demonstrates reliable at-least-once dispatch under failure and concurrency.

Delivery public contracts are grouped by workflow under `delivery.api.publish`, `.processing`, `.history`, `.replay`, and `.operations`; application and persistence remain internal implementation packages.

**Phase 2 - advanced delivery controls is complete; Phase 3 - evidence-gated product and scale controls is next.** Phase 2A PostgreSQL candidate selection ranks endpoint allocation level as committed `CLAIMED` count plus per-endpoint pending ordinal. A 32/2 noisy-neighbor wave changes from FIFO A 8/B 0 to fair A 6/B 2; deep backlogs split 4/4, one endpoint can burst to eight, and new B work gets the next free claim. Concurrent claims prove eight distinct claims and progress for both endpoints; disable, recovery, attempt-start, and stale-token/late-result regressions remain green. A live PostgreSQL 17.10 `EXPLAIN (ANALYZE, BUFFERS)` fixture returned eight candidates in 2.703 ms and used the V8 pending/claimed indexes, so no index migration is accepted from this small local evidence. Phase 2B has bounded `Retry-After` parsing/capture plus effective scheduling: attempts 1-4 choose `max(equal jitter, hint)`, persist only paired positive delay/source (`BACKOFF` or `RETRY_AFTER`) in V13, and let PostgreSQL set `due_at`; unknown recovery is `BACKOFF` and attempt five stores no retry data. ADR-009's circuit state is active through V14/V15 persistence and validated three-failure/30-second/one-probe settings. PostgreSQL fair claim selection admits normal work only for missing/`CLOSED` circuits and atomically fences exactly one cooldown-expired `OPEN` probe as `HALF_OPEN`; conditional finalization and `UNKNOWN` recovery transition that same durable circuit in their existing short transactions. Three receiver failures open one endpoint without idling unrelated capacity; a matching probe success/nonqualifying result closes it, while qualifying or unknown probe outcomes reopen it. Focused PostgreSQL concurrency evidence proves one cross-worker probe and a healthy endpoint continuing to claim. Versioned signing-secret rotation and immutable event-schema versions are deferred rather than default next work. ADR-010 completes the local publisher rate-limit contract; persistent quotas, custom retry policy, SSE, ordering, RBAC, Redis, a broker, and Kubernetes remain decision gates rather than technology-count goals.

Phase 3 items are parent initiatives, not fixed-size slices. If an initiative has material scope, risk, or proof burden, split it before implementation. Implement accepted decisions autonomously. For a new material trade-off that would change accepted code, dependencies, runtime behavior, infrastructure, or data design, present options and a recommendation, then wait for owner approval.

Phase 3 Slice 1 is complete in ADR-010 and code: local publisher admission is a
project-wide API-process token bucket (60 burst, 30 requests/second, 15-minute
idle expiry, 10,000 retained-bucket maximum). It runs after publisher
authentication and path-project authorization but before body/database work;
all requests reaching it count, including equivalent idempotent retries. A
rejection is `429 PUBLISH_RATE_LIMITED` with positive `Retry-After` and no
event/delivery. It records only bounded admission outcome metrics and sanitized
logs. The limiter is bounded local state, not Redis, a durable quota, or a
cluster-wide guarantee.

Phase 3 Slice 2 is complete in ADR-011 and code: PostgreSQL retains one current
usage row per project and atomically admits at most the configured 10,000 new
events per UTC day. A new event reserves quota inside the existing publish
transaction; quota exhaustion rolls back it and returns `429
PUBLISH_QUOTA_EXCEEDED` with `Retry-After` to the next UTC day. Equivalent and
conflicting idempotent retries consume no quota. This is neither billing nor a
plan, has no owner-specific tier/usage UI, and needs neither Redis nor a reset
scheduler.

Phase 3 Slice 3 is complete under ADR-012 and V17: an owner may set one
optional per-endpoint `minimumRetryDelaySeconds` of 5--300 whole seconds via
the existing optimistic endpoint configuration workflow. It can only lengthen
the established retry wait; attempts one through four and recovered `UNKNOWN`
attempts select the maximum of equal jitter, the optional floor, and an
eligible bounded `Retry-After` hint, with the existing 300-second cap and
five-attempt limit intact. The worker locks one endpoint-policy snapshot inside
the short retry transaction; a later update never rewrites persisted `due_at`.
`ENDPOINT_POLICY` records only a strict floor winner. The dashboard exposes the
optional setting; there is no project-level policy, scheduler, or new runtime
dependency.

Phase 3 Slice 4.1 measured the active Delivery dashboard polling baseline. One
authenticated dashboard-equivalent local session makes five recurring GET reads
every five seconds; four phase-offset cycles produced the expected 20 API
requests, with per-route client p95 9.928--22.210 ms and one observed delivery
transition visible after 2.809 seconds. This is not concurrent or production
load evidence; it does not justify SSE, PostgreSQL `LISTEN/NOTIFY`, Redis, or a
broker. Keep bounded REST polling unless a later measured symptom reaches the
evidence gate.

The owner has accepted Phase 3 Slice 4.2/ADR-013 as an explicit learning
exception: API-mode SSE is a best-effort, owner/project-scoped invalidation
hint from post-commit PostgreSQL `NOTIFY`/`LISTEN`, never a durable or
correctness-critical event source. It carries only project/delivery identity
and observation time for committed worker finalization/`UNKNOWN` recovery;
REST remains authoritative and five-second polling remains the recovery path.

Phase 3 Slice 4.3 now implements that API-only bridge. Committed delivery
finalization and expired-`UNKNOWN` recovery call PostgreSQL `pg_notify` in the
same transaction; a dedicated, reconnecting API listener connection fans only
the project ID, delivery ID, and observation time to owner-authorized SSE
streams. It keeps a 15-second heartbeat and 15-minute lifetime, cleanly closes
streams at shutdown, and exposes bounded outcome-only metrics. The stream has
no replay, ordering, or correctness guarantee; worker composition has no SSE
  route or listener. Slice 4.4 mounts one credentialed `EventSource` only for
  the visible selected-project Delivery workspace. Open/reconnect/error and a
  validated same-project hint invalidate existing history queries; the client
  never writes delivery state from SSE and keeps five-second REST polling as
  recovery.

Phase 3 Slice 4.6 closes the reliability/acceptance evidence: Testcontainers
forcibly terminates the dedicated PostgreSQL listener and proves bounded API
reconnect plus resumed same-project fan-out; API lifecycle shutdown removes a
stream exactly once with a bounded outcome metric. An authenticated local
dashboard opened/closed/reopened the Delivery stream, received a valid fixture
hint, retained REST-rendered history, and logged no browser warnings/errors.
The hint remains deliberately lossy; five-second polling remains recovery.

The owner-approved follow-on roadmap is maintained in `PROJECT_STATUS.md` under
“Owner-approved product and job-readiness roadmap.” U1 is complete under
option A: a static explanatory landing is public, while sign-in, owner data,
and operations remain private. The truthful promise and section/public-data
contract are authoritative in `REQUIREMENTS.md` section 12. U1.4 acceptance
proved the public artifact's start-of-page keyboard order, visible focus,
semantic landmarks, reduced-motion guard, clean console, and 320px no-overflow
rendering; direct 200% browser zoom remains unavailable in the embedded browser.
The current next child is U2.1, first-owner empty-state and guided-success
contract. U1.3 provides a code-native
`DeliveryPathVisual` in `frontend/src/features/landing`, showing Publisher →
PostgreSQL durable intent → Worker → Receiver without claiming live telemetry,
exactly-once delivery, or ordering. It also removed the global `body` 320px
minimum width after browser evidence found it created a narrow-screen scrollbar.
U1.2 now
uses `react-router-dom`: the static `/` landing is isolated in
`frontend/src/features/landing`, while `/login` and `/app` invoke the existing
owner-session behavior through `frontend/src/app/router`; frontend navigation
is never authorization. Read the ledger at the start
of planning/implementation work and update it with `tasks/CURRENT.md` after
every child slice. Product clarity U1–U5 is intentionally prioritized before
J1–J7 technology extensions; existing Phase 3 decision gates remain valid.

The backend uses `com.gialong.relayforge` with `identity`, `project`, `endpoint`, and `delivery` capability packages. ArchUnit tests enforce the approved dependency graph, cycle freedom, and cross-module public-API access. A strict required `relayforge.runtime=api|worker` selects one process. Both modes use a servlet context: API hosts business routes; worker hosts only health/Prometheus behind a management permit chain and deny-all fallback. The persistence foundation uses Spring JDBC/Hikari, Flyway, Hibernate/JPA, and PostgreSQL Testcontainers against pinned PostgreSQL 17.10 in the `public` schema. V2 creates `owner_accounts`; V3 creates the technical Spring Session tables. Identity has race-safe JDBC bootstrap and ordinary JPA credential lookup, with BCrypt work outside short transactions, dummy work for unknown users, generic invalid outcomes, and no public hash exposure.

The completed owner browser-authentication slice adds API-only Spring Security, JSON CSRF/login/me/logout endpoints, a JDBC-backed `RF_SESSION` with rotation and invalidation, credentialed origin allowlisting, and a bounded local failed-login limiter. The verifier remains the identity-owned credential decision point and `VerifiedOwner` remains the sole hash-free principal. PostgreSQL integration tests prove CSRF, session rotation/restart/logout, CORS, rate limiting, and non-web worker exclusion. The completed `project` slices add Flyway V4 projects and V5 publisher API keys, owner-UUID-only JPA aggregates, explicit owner-scoped management, keyset pagination, and API-only REST routes that reuse session and CSRF. API keys use a required environment pepper, a UUID selector, a one-time 32-byte secret, and HMAC digest storage. V6 adds owner-managed endpoints with exact subscriptions, immutable one-time `whsec_` signing material encrypted with AES-256-GCM, owner-bound cursor pagination, optimistic configuration updates, and idempotent enable/disable. V7 supports publisher API-key event acceptance: the delivery module stores immutable JSONB events and their exact enabled-subscription delivery snapshot atomically, with project-scoped idempotency and no browser session or CSRF requirement. V8 adds the worker claim foundation: bounded PostgreSQL-time `PENDING` claims, lease/token fencing, paused-backlog filtering plus final endpoint row-lock recheck, expired pre-attempt recovery, and local permit reservation. V9 adds durable `STARTED` attempts: a current-claim/endpoint snapshot atomically increments attempt count, stores one numbered attempt, fingerprints the URL, and extends the lease; dispatch instructions carry opaque encrypted signing material and are never persisted. V10 adds conditional attempt finalization, equal-jitter retry scheduling, immutable `UNKNOWN` recovery with late diagnostics, and a worker-only bounded polling lifecycle using virtual-thread tasks behind the permit limit. V11 adds owner-scoped event/delivery/attempt history with opaque filter-bound cursors, bounded escaped attempt previews, and a replay transaction that reserves a project idempotency key then creates one linked `PENDING` delivery. Existing worker orchestration consumes that replay normally. Group 11 frontend is now complete: its React/Vite dashboard has session/auth controls, query-cached project and configuration lists, creation-only raw API-key/signing-secret reveals kept out of cache/storage, endpoint configuration, bounded REST-polled history, safe diagnostic rendering, and exhausted-delivery replay with a component-memory idempotency key. Docker, operational observability, and terminal-history retention are now complete; cloud, CI, load/JVM profiling, and an owner-led authenticated dashboard walkthrough remain deferred.

Group 12 is complete: root Docker Compose builds one backend image and starts it in separate API/worker modes with PostgreSQL, the React dashboard, and a bounded local Java receiver. The receiver shares the worker network namespace so its demo `localhost` URL remains actual loopback and does not weaken the SSRF policy. The smoke flow proves CSRF mutations, idempotent publish, signed delivery, and API/worker restart persistence. Group 13 adds Actuator health/Prometheus, ECS structured logs, trace IDs, bounded delivery/worker metrics, a cached delivery backlog snapshot, ADR-003, and an operator runbook. Group 14 adds V12 indexes and worker-only retention: each `READ COMMITTED` transaction locks, rechecks, and removes one complete expired terminal graph in dependency order, while source-delivery locking coordinates with replay. Group 15 adds one read-only GitHub Actions quality gate: JDK 25 full Testcontainers tests, Node 24 frontend install/lint/build, then direct local-only image builds; it has no deploy, publishing, or runtime-secret requirement. Group 16 supersedes ADR-004 with ADR-005: a temporary Tokyo EC2 host runs Caddy, dashboard, API and worker containers from the same backend image, and internal PostgreSQL through Docker Compose. The selected image pair is `d70776814883` for `https://gialong.duckdns.org`; it contains the JPQL-backed publisher API-key revoke correction. EC2 Slice 2 is complete: Caddy alone publishes 80/443 through AWS Security Group and UFW, has acquired a Let's Encrypt certificate, and proxies the public dashboard/API. PostgreSQL/API/worker remain unpublished and all health checks pass. Worker excludes Spring's unused generated default user, and initial bootstrap credentials were removed after creation. A browser reload proved the PostgreSQL-backed owner session survives API recreation. Group 17 is complete: opt-in local Prometheus/Grafana profiles, an ignored bounded k6 fixture, and repeatable JFR/thread-dump capture now provide local-only performance evidence. The initial success-path baseline accepted 1,275 unique publishes with zero HTTP errors and drained 1,275 successful deliveries to zero ready backlog; it deliberately made no runtime tuning claim. Groups 18–19 are complete: controlled local 500/timeout/crash/exhaustion paths now prove persisted retry, `UNKNOWN`, bounded attempts, and terminal history; a custom archive restored into a private PostgreSQL container and immutable old backend image reached readiness; DuckDNS/HTTPS readiness is scriptable without mutation. After every code change, rebuild or reload the affected local service and browser-check `http://localhost:5173/` before reporting the work complete; report explicitly if that acceptance check is unavailable. Group 20 portfolio/interview closeout is the final recommended scope; managed observability and production load testing remain deferred.

Group 20 is complete: `README.md` and `docs/PORTFOLIO_PLAYBOOK.md` connect accepted architecture, measured local evidence, CV wording, interview trade-offs, and a safe demo flow without claiming an SLA or production capacity. The recommended action is owner review/commit, not another technology addition; managed observability and production load testing remain deferred.

Operational deployment note: the owner-led public dashboard workflow is complete. At `https://gialong.duckdns.org`, the authenticated owner created one project/key/enabled HTTPS endpoint, published one `invoice.paid` event, and observed one `SUCCEEDED` HTTP-204 worker delivery after one attempt; session and safe metadata survived a dashboard reload without console errors. The raw API key and signing secret remained one-time browser values. Deployment configuration/documentation is committed; manual rollback/backup drill, managed observability, and production load testing remain deferred.

The high-level architecture is a modular monolith: one artifact/image starts in either `api` or `worker` mode, and PostgreSQL is the source of truth and Portfolio v1 work transport. Detailed contracts belong in their dedicated documents; this file intentionally does not restate them. The project requires JDK 25, but the current terminal defaults to JDK 21, so local Maven commands must select the installed JDK 25 until the environment is corrected.

Start work with [AGENTS.md](../AGENTS.md), then [CURRENT.md](../tasks/CURRENT.md), and use the concise [documentation index](README.md) to open only the relevant authority. `PROJECT_STATUS.md` is the project-wide progress ledger. Accepted ADRs in `docs/adr/` record material architectural choices and are not silently reversed.
