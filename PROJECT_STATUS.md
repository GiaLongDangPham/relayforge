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
- Current slice: Portfolio v1 requirements completed and independently reviewed; the next slice is the delivery model.
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
| Retry limit | Maximum 5 total HTTP attempts, including the initial attempt | Bounds retry storms while leaving enough attempts to demonstrate backoff and recovery. |
| Retryable outcomes | Network error, timeout, HTTP 408, 429, and 5xx | These failures may be transient. Most other 4xx responses are permanent. |
| Success outcome | Any HTTP 2xx response | The receiver accepted the delivery at the transport level. |
| Ordering | No ordering guarantee in Portfolio v1 | Avoids head-of-line blocking and keeps worker concurrency meaningful. |
| Redirects | Do not follow redirects in v1 | Reduces SSRF risk and keeps delivery semantics explicit. |
| Production endpoint policy | Public HTTPS endpoints only; local HTTP is allowed only in a development profile | Protects transport security while retaining a practical local demo path. |
| Frontend | React + Vite + TypeScript | The product is an authenticated dashboard and does not need SSR or SEO. |
| Owner onboarding | Bootstrap accounts only in v1 | Avoids public registration, email verification, and password-reset scope. |
| Endpoint lifecycle | Disable pauses new attempts; re-enable resumes nonterminal work | Gives the owner an operational stop control without pretending an in-flight HTTP request can be undone. |
| Endpoint configuration | Future attempts use the current URL; the v1 signing secret is immutable | Avoids secret-versioning scope while making URL updates predictable. |
| Manual replay | New linked delivery with a project-scoped replay idempotency key | Preserves history and makes repeated or concurrent replay commands deterministic. |
| Resource bounds | 64 KiB event payload, 8 KiB response preview, 30-day terminal-history retention | Prevents unbounded persistence while keeping Portfolio v1 manageable. |
| Hardening timebox | Maximum 16 hours inside the 80-96 hour Portfolio v1 target | Bounds CI, cloud, load, JFR, and documentation work so correctness remains first. |

## Completed

- Selected RelayForge as the flagship project.
- Bounded Portfolio v1 and explicitly deferred non-core technologies.
- Chosen the core delivery semantics listed above.
- Inspected the existing Spring Boot skeleton without modifying application code.
- Added the repo-scoped `relayforge-mentor` skill and this progress ledger.
- Added and independently reviewed the Portfolio v1 product requirements, actors, use cases, non-goals, resource bounds, and measurable acceptance criteria.

## Not completed

- Business invariants and delivery state machine specification.
- Failure model and non-functional requirements.
- Module boundaries and dependency rules.
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

## Next recommended slice

Create one delivery-model document containing only:

- business invariants;
- delivery and attempt state transitions;
- lease and claim-token lifecycle;
- failure scenarios and expected recovery behavior.

Do not design database tables, JPA entities, repository queries, or API contracts in that slice.

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
