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
- Current slice: Architecture boundaries completed and independently reviewed; the next slice is the first Architecture Decision Record.
- Repository state: Git repository on `main`; minimal backend and frontend skeletons exist under `backend/` and `frontend/`.
- Backend baseline observed: Java 25, Spring Boot 4.1.0, Maven, and Spring Web MVC only.

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

## Not completed

- Concrete retry, timeout, lease, and remaining non-functional defaults.
- Architecture Decision Records.
- Database model and migrations.
- API contracts.
- Any RelayForge production code or tests.
- Docker, CI, frontend, observability, performance testing, or cloud infrastructure.

## Verification log

| Date | Slice | Evidence |
| --- | --- | --- |
| 2026-08-09 | Project workflow setup | `quick_validate.py` returned `Skill is valid!`. No application code changed, so backend tests were not run. |
| 2026-08-09 | Portfolio v1 requirements | An independent review ran three passes. The final pass reported no unresolved P0/P1 contradiction and returned `ready for the next Phase 0 slice`. No application code changed, so backend tests were not run. |
| 2026-08-09 | Delivery correctness model | An independent reviewer checked concurrency, transaction, lease, attempt, recovery, and replay semantics. Two correction passes resolved six P1 findings; the final pass reported no unresolved P0/P1 and returned `READY`. `git diff --check` passed. No application code changed, so backend tests were not run. |
| 2026-08-09 | Architecture boundaries | `docs/ARCHITECTURE_BOUNDARIES.md` defines four capability modules, dependency and runtime rules, transaction ownership, and future architecture-test evidence. Independent review found one P1 claim-eligibility gap; the correction added a batch endpoint contract inside the short claim transaction, and re-review returned `READY` with no unresolved P0/P1. `git diff --check` passed. No application code changed, so backend tests were not run. |

## Next recommended slice

Create one ADR for the foundational deployment decision:

- modular monolith instead of initial microservices;
- one artifact and image with explicit `api` or `worker` runtime mode;
- PostgreSQL coordination instead of internal HTTP or a message broker;
- consequences and evidence that would justify revisiting the decision.

Do not create module packages, add dependencies, or implement runtime configuration in that slice.

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
