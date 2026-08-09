# RelayForge Project Status

Last updated: 2026-08-09

This file is the durable source of truth for project scope and progress. Update it after every completed slice.

## Target

- Product: outbound webhook delivery platform.
- Portfolio v1: approximately 8 weeks at 10-12 hours per week.
- Priority: correctness, testing, concurrency/transactions, failure handling, observability, cloud, then performance.
- Cloud budget: approximately USD 15-20 per month while the demo environment is active; 24/7 operation is not required.

## Current position

- Current phase: Phase 0 - Requirements and Architecture.
- Current slice: the complete Phase 0 documentation baseline is ready for owner review; no further architecture-first document is planned before code.
- Repository state: Git repository on `main`; minimal backend and frontend skeletons exist under `backend/` and `frontend/`.
- Backend baseline observed: Java 25, Spring Boot 4.1.0, Maven, and Spring Web MVC only; no RelayForge production behavior or database migration exists.

## Approved decisions

| Area | Decision | Reason |
| --- | --- | --- |
| Product scope | Outbound webhook delivery only | The outbound reliability problem already provides sufficient backend depth. |
| Runtime | API and worker are separate processes from the same codebase and image | Enables failure isolation and independent scaling without microservices. |
| Source of truth | PostgreSQL | Keeps event, delivery, attempt, and job state consistent. |
| MVP job transport | PostgreSQL-backed jobs | Creates a real transaction, locking, claim, and recovery learning problem. |
| Delivery guarantee | At-least-once | An ambiguous HTTP outcome can require redelivery; exactly-once is not promised. |
| Tenant model | One owner per project for v1 | Keeps authorization focused on ownership and IDOR prevention. |
| Event routing | Fan out an event to endpoints subscribed to its exact event type | Preserves the event-to-many-deliveries model without wildcard/filter complexity. |
| Routing snapshot | Determine matching endpoints when the event is accepted | Later endpoint changes do not rewrite historical delivery intent. |
| Worker claim | Short transaction with a time-bounded lease and claim token | Avoids holding DB locks during network I/O and supports crash recovery. |
| Publish idempotency | Require `Idempotency-Key`, unique within a project | Makes client retry behavior explicit and testable. Same key with different content is a conflict. |
| Retry limit | Maximum 5 total dispatch attempts, including the initial attempt | Bounds retry storms while accounting for a security rejection that may stop before network I/O. |
| Retryable outcomes | Network error, timeout, HTTP 408, 429, and 5xx | These failures may be transient. Most other 4xx responses are permanent. |
| Success outcome | Any HTTP 2xx response | The receiver accepted the delivery at the transport level. |
| Ordering | No ordering guarantee in Portfolio v1 | Avoids head-of-line blocking and keeps worker concurrency meaningful. |
| Redirects | Do not follow redirects in v1 | Reduces SSRF risk and keeps delivery semantics explicit. |
| Production endpoint policy | Public HTTPS endpoints only; local HTTP is allowed only in a development profile | Protects transport security while retaining a practical local demo path. |
| Frontend | React + Vite + TypeScript | The product is an authenticated dashboard and does not need SSR or SEO. |
| Owner onboarding | Bootstrap accounts only in v1 | Avoids public registration, email verification, and password-reset scope. |
| Endpoint lifecycle | Disable pauses new attempts; re-enable resumes nonterminal work | Gives the owner an operational stop control without pretending an in-flight HTTP request can be undone. |
| Endpoint configuration | Every normal or replay attempt snapshots the current URL atomically when that attempt starts; the v1 signing secret is immutable | Gives URL changes one deterministic linearization point without secret-versioning scope. |
| Manual replay | New linked delivery with a project-scoped replay idempotency key | Preserves history and makes repeated or concurrent replay commands deterministic. |
| Attempt boundary | A dispatch attempt consumes budget when its durable `STARTED` record is created | A crash after that boundary has ambiguous progress and must not reuse the attempt number. |
| Claim validity | Normal completion requires `CLAIMED`, the current token, and an unexpired lease; every exit from `CLAIMED` clears token and lease | Prevents a stale or expired worker from overwriting recovery or a newer claim. |
| Lease strategy | Extend the lease once at attempt start to cover the bounded HTTP timeout plus completion margin; no periodic heartbeat in v1 | Keeps transactions short and recovery testable without renewal races. |
| Time authority | PostgreSQL time controls due-time and lease-expiry decisions | Worker clock skew must not determine correctness. |
| Ambiguous outcome | Recovery finalizes an unfinished started attempt as immutable `UNKNOWN`; a late result is a separate diagnostic | Preserves append-only history and prevents stale observations from changing delivery state. |
| Business modules | `identity`, `project`, `endpoint`, and `delivery` | Organizes by business capability while keeping Portfolio v1 small and cohesive. |
| Delivery-module cohesion | Publish idempotency, event, delivery, attempt, replay, and worker state stay in one `delivery` module | Keeps atomic event acceptance local and avoids artificial cycles or asynchronous consistency. |
| Module dependencies | `endpoint` may use public `project` contracts; `delivery` may use public `project` and `endpoint` contracts; no reverse dependencies | Produces an acyclic graph and prevents repository/entity sharing. |
| Runtime selection | The same artifact and image start in exactly one explicit `api` or `worker` mode | Gives process isolation and independent instance counts without internal HTTP or microservices. |
| Claim eligibility boundary | `delivery` checks endpoint enabled state in one batch inside the short claim transaction before changing candidates to `CLAIMED` | Preserves endpoint pause semantics without importing endpoint persistence. |
| Queue representation | Persisted delivery state is the PostgreSQL job; no separate generic job record in v1 | Avoids two lifecycle records drifting while only one background capability exists. |
| Worker capacity admission | Reserve local dispatch permits before claim; return at most the reserved capacity and hold one permit until each local claim task stops | Prevents unbounded local prefetch and avoids releasing capacity merely because a stale task's lease expired. |
| Delivery timing defaults | 2-second connect, 10-second dispatch, 15-second initial lease, 20-second attempt lease, 5-second recovery scan, and 20-second shutdown | Gives Portfolio v1 bounded, internally consistent starting values that can be tuned from metrics. |
| Retry timing defaults | 5-second base, multiplier 4, 300-second cap, and equal jitter | Demonstrates persisted exponential backoff while keeping the portfolio workflow observable in a short session. |
| Worker polling defaults | 8 local permits, claim only up to free permits, poll every 500 ms plus 0-100 ms jitter | Bounds local concurrency and avoids claimed-work prefetch while retaining responsive demo latency. |
| Configuration identifiers | Application-generated UUIDv4 with PostgreSQL `uuid`; lifecycle timestamps use `timestamptz` and PostgreSQL time | Keeps identifiers opaque and timestamps unambiguous without another generator dependency. |
| Configuration concurrency | Optimistic `bigint version` on mutable owner, project, and endpoint aggregates | Detects dashboard lost updates without confusing them with worker lease/token fencing. |
| Secret persistence | Password hashes and API-key digests are nonrecoverable; endpoint signing secrets use encrypted envelopes with an external key reference | Signing needs plaintext recovery while authentication secrets must never be recoverable from persistence. |
| Owner authentication | Spring Security with PostgreSQL-backed server sessions, BCrypt cost 12, CSRF on owner mutations, and a 30-minute idle timeout | Fits one first-party dashboard and keeps logout/invalidation server-controlled without JWT or Redis. |
| Publisher authentication | One-time project API key with public selector and 32-byte secret; persist only a peppered HMAC-SHA-256 digest | Enables direct record lookup, revocation, and nonrecoverable credential storage. |
| Outbound authenticity | One-time 32-byte endpoint secret, encrypted at rest, signs versioned canonical identity fields plus exact body digest with HMAC-SHA-256 | Lets receivers verify origin and body integrity while preserving recoverability only where dispatch needs it. |
| SSRF boundary | Validate configuration, then resolve, reject prohibited address classes, and pin the actual connection on every attempt; do not follow redirects | URL syntax checks alone cannot prevent DNS rebinding or internal-network access. |
| HTTP contract | Versioned REST under `/api/v1`, Problem Details errors, opaque cursor pagination, optimistic version conflicts, and one-time secret responses | Keeps the demo API explicit, bounded, and safe without exposing worker internals. |
| Configuration lifecycle | No owner/project/endpoint hard deletion in v1; revoke API keys and disable endpoints | Prevents configuration actions from accidentally cascading into future delivery history or nonterminal work. |
| Boundary enforcement | Use capability packages plus ArchUnit architecture tests when the module skeleton is implemented | Shared-process boundaries need executable enforcement rather than naming convention alone. |
| Resource bounds | 64 KiB event payload, 8 KiB response preview, 30-day terminal-history retention | Prevents unbounded persistence while keeping Portfolio v1 manageable. |
| Hardening timebox | Maximum 16 hours inside the 80-96 hour Portfolio v1 target | Bounds CI, cloud, load, JFR, and documentation work so correctness remains first. |

## Completed

- Selected RelayForge as the flagship project.
- Bounded Portfolio v1 and explicitly deferred non-core technologies.
- Chosen the core delivery semantics listed above.
- Inspected the existing Spring Boot skeleton without modifying application code.
- Added the repo-scoped `relayforge-mentor` skill and this progress ledger.
- Added and independently reviewed the Portfolio v1 product requirements, actors, use cases, non-goals, resource bounds, and measurable acceptance criteria.
- Added and independently reviewed the delivery invariants, state transitions, attempt boundary, claim/lease lifecycle, failure matrix, and required concurrency evidence.
- Added and independently reviewed the four business-module boundaries, acyclic dependency graph, API/worker runtime composition, and transaction ownership rules.
- Accepted ADR-001 for one modular-monolith artifact/image with separate explicit API and worker runtime modes.
- Accepted ADR-002 for PostgreSQL-backed delivery jobs, bounded batch claims, leases, claim tokens, and evidence-based broker migration triggers.
- Added a compact agent operating guide and project context so routine agent tasks can load stable constraints without repeatedly loading the full progress ledger.
- Chosen bounded delivery runtime defaults with validation relationships, metrics, tuning triggers, and future test evidence.
- Defined database model Part 1 for owner accounts, projects, API keys, endpoint configuration, subscriptions, secrecy, optimistic locking, and restrictive deletion.
- Defined database model Part 2 for immutable events, PostgreSQL delivery queue state, append-only attempts, late diagnostics, replay idempotency, transaction boundaries, retention, and future evidence.
- Defined the owner, publisher, inspection, replay, and outbound webhook HTTP contract.
- Defined the Portfolio v1 security baseline for sessions, CSRF, API keys, ownership, signing secrets, outbound HMAC, SSRF-resistant connections, and redaction.
- Added the Phase 0 handoff, review map, exit checklist, locked decisions, and first Phase 1 slice.
- Replaced the file-count workflow limit with a cohesive-scope rule in the repository guide and RelayForge mentor skill.
- Refined repository guidance for learning-first collaboration: separated repository policy, compact navigation, mentor behavior, the documentation index, and the single active-task record.

## Not completed

- Owner review and approval of the final Phase 0 documentation batch.
- Physical database design, migrations, indexes, lock SQL, mappings, and repositories.
- Any RelayForge production code or tests.
- Docker, CI, frontend, observability, performance testing, or cloud infrastructure.

## Verification log

| Date | Slice | Evidence |
| --- | --- | --- |
| 2026-08-09 | Project workflow setup | `quick_validate.py` returned `Skill is valid!`. No application code changed, so backend tests were not run. |
| 2026-08-09 | Portfolio v1 requirements | An independent review ran three passes. The final pass reported no unresolved P0/P1 contradiction and returned `ready for the next Phase 0 slice`. No application code changed, so backend tests were not run. |
| 2026-08-09 | Delivery correctness model | An independent reviewer checked concurrency, transaction, lease, attempt, recovery, and replay semantics. Two correction passes resolved six P1 findings; the final pass reported no unresolved P0/P1 and returned `READY`. `git diff --check` passed. No application code changed, so backend tests were not run. |
| 2026-08-09 | Architecture boundaries | `docs/ARCHITECTURE_BOUNDARIES.md` defines four capability modules, dependency and runtime rules, transaction ownership, and future architecture-test evidence. Independent review found one P1 claim-eligibility gap; the correction added a batch endpoint contract inside the short claim transaction, and re-review returned `READY` with no unresolved P0/P1. `git diff --check` passed. No application code changed, so backend tests were not run. |
| 2026-08-09 | ADR-001 modular-monolith runtime | `docs/adr/0001-modular-monolith-api-worker-runtime.md` records context, alternatives, consequences, failure behavior, guardrails, and evidence-based revisit triggers. Independent review returned `READY` on the first pass with no P0/P1 findings. `git diff --check` passed. No application code changed, so backend tests were not run. |
| 2026-08-09 | Agent context and workflow | Added `AGENTS.md` as the repository entrypoint, `docs/AGENT_CONTEXT.md` as compact working memory, and updated the RelayForge mentor skill to route routine work to them before the full ledger. `git diff --check` passed. No application code changed, so backend tests were not run. |
| 2026-08-09 | ADR-002 PostgreSQL-backed delivery jobs | `docs/adr/0002-postgresql-backed-delivery-jobs.md` records the database-job decision, batch claim contract, permit lifecycle, alternatives, failure behavior, correctness evidence, and broker revisit criteria. Independent review found unbounded test oracles and an incomplete permit lifecycle; two correction passes resolved them, and final re-review returned `READY` with no unresolved P0/P1. `git diff --check` passed. No application code changed, so backend tests were not run. |
| 2026-08-09 | Delivery runtime defaults | `docs/DELIVERY_RUNTIME_DEFAULTS.md` defines bounded HTTP, lease, retry, polling, concurrency, recovery, and shutdown values with validation rules and tuning evidence. Per the user's documentation-only policy, no independent reviewer was used. `git diff --check`, timing arithmetic, and retry-schedule checks passed. No application code changed, so backend tests were not run. |
| 2026-08-09 | Database model Part 1 | `docs/DATABASE_MODEL_PART1.md` defines five configuration tables, ownership, bounded columns, constraints, transaction rules, secret representation, lifecycle behavior, query patterns, and future Testcontainers evidence. Per the user's documentation-only policy, no independent reviewer was used. `git diff --check` passed; the scope check found exactly five Part 1 tables and no SQL block. No application code changed, so backend tests were not run. |
| 2026-08-09 | Final Phase 0 documentation batch | Added database model Part 2, API contract, security baseline, and Phase 0 handoff; aligned the earlier requirements, delivery, architecture, persistence, workflow, status, and compact-context documents. Per the user's documentation-only policy, no independent reviewer was used. `git diff --check` and all local Markdown-link checks passed; `quick_validate.py` returned `Skill is valid!`; structural checks found five conceptual tables in each database-model part, 26 unique API endpoint headings, a 740-word compact context, and no application-path changes. No application code changed, so backend tests were not run. |
| 2026-08-09 | Learning-first agent workflow | Rewrote `AGENTS.md` as the repository-wide learning, source-of-truth, validation, and reporting policy; reduced `docs/AGENT_CONTEXT.md` to orientation/navigation; added `docs/README.md` and `tasks/CURRENT.md`; and limited the mentor skill to learner-specific behavior. `git diff --check` passed. No application code changed, so backend tests were not run. |

## Next recommended slice

The owner reviews `docs/PHASE_0_HANDOFF.md` and the linked final batch. Resolve only concrete contradictions or requested changes.

After approval, begin Phase 1 with one code slice: create the backend capability-package skeleton and ArchUnit tests that enforce the accepted dependency graph and prevent repository/entity leakage. Compile and run the tests. Do not add migrations, business behavior, controllers, security configuration, worker execution, or frontend changes in that slice.

## Deferred until evidence justifies them

- RabbitMQ, SQS, or Kafka.
- Redis or distributed caching.
- SSE/WebSocket status updates.
- Organization and complex RBAC.
- Inbound webhook gateway.
- Strict per-endpoint ordering.
- Kubernetes, microservices, multi-region, billing, and quota management.

## Change log

### 2026-08-09

- Established the incremental learning workflow.
- Recorded the pre-Phase 0 choices and the remaining Phase 0 decisions.
- Chose fan-out routing, leased claims, required publish idempotency, five total attempts, and no ordering guarantee.
- Completed the reviewed Portfolio v1 requirements baseline after an independent three-pass review.
- Bounded hardening to 16 hours and clarified endpoint pause/resume, immutable signing secrets, claim-token completion, replay idempotency, and resource limits.
- Completed the reviewed delivery correctness baseline and aligned normal and replay attempts on one attempt-start URL snapshot rule.
- Defined the durable attempt boundary, token-and-lease conditional transitions, PostgreSQL time authority, immutable unknown outcomes, and one-extension/no-heartbeat lease policy.
- Completed the reviewed architecture-boundaries baseline with four capability modules and an acyclic public-contract dependency graph.
- Defined one-image `api`/`worker` runtime composition and the batch endpoint eligibility boundary required by the short claim transaction.
- Accepted ADR-001 documenting why RelayForge uses one modular-monolith artifact/image with separate API and worker process modes.
- Added a token-efficient agent workflow: `AGENTS.md` is the startup contract, `docs/AGENT_CONTEXT.md` is compact working memory, and the full status file is read only for phase/decision/handoff work or targeted sections. The mentor skill now enforces that routing.
- Accepted ADR-002 documenting why delivery state is the PostgreSQL job and how lease, token, local permits, and broker-migration evidence bound that choice.
- Chosen the initial bounded delivery runtime values and the metrics and evidence required before tuning them.
- Completed the Part 1 configuration database baseline with opaque UUIDs, optimistic versions, nonrecoverable authentication secrets, encrypted signing material, and restrictive deletion.
- Changed the incremental workflow from a file-count limit to a small cohesive review-scope limit.
- Completed the Phase 0 event/delivery persistence, API, and security baselines and linked them through a final handoff and owner-review checklist.
- Separated learning-first agent workflow responsibilities: `AGENTS.md` holds repository rules and source authority; `AGENT_CONTEXT.md` provides navigation; `docs/README.md` indexes sources; `tasks/CURRENT.md` holds the active unit; and the mentor skill supplies only teaching behavior.
