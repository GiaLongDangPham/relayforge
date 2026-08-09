# RelayForge Phase 0 Handoff

Status: Ready for owner review
Last updated: 2026-08-09

## 1. Phase outcome

Phase 0 has produced an implementation-ready baseline for RelayForge Portfolio v1. It fixes the product boundary, correctness model, module ownership, runtime shape, conceptual persistence, HTTP contract, and minimum security behavior without creating production code, SQL migrations, or framework-specific mappings.

This is the stopping point for architecture-first documentation. After owner approval, the next work is Phase 1 code in small cohesive slices. A new design document is justified only when implementation evidence exposes a missing decision or an accepted decision must change.

## 2. Product and technical thesis

RelayForge is an outbound webhook delivery platform whose core learning problem is reliable at-least-once dispatch under concurrency and partial failure.

The Portfolio v1 thesis is:

- accept an immutable event idempotently;
- atomically snapshot all enabled exact-type routes into durable deliveries;
- use PostgreSQL delivery rows as a bounded job queue;
- claim work with short transactions, leases, and opaque fencing tokens;
- perform no outbound HTTP inside a database transaction;
- preserve append-only attempt evidence, including ambiguous crash outcomes;
- expose owner configuration/history through a small secured REST dashboard;
- deploy the same artifact/image as separate `api` and `worker` processes.

The project deliberately does not begin with a message broker, Redis, microservices, Kubernetes, ordering guarantees, or exactly-once claims.

## 3. Review map

Review these documents in order. Later documents refine earlier decisions but must not weaken their invariants.

| Order | Document | Review question |
| --- | --- | --- |
| 1 | [Requirements](REQUIREMENTS.md) | Is the Portfolio v1 product scope valuable and bounded? |
| 2 | [Delivery model](DELIVERY_MODEL.md) | Are state, retry, crash, replay, and at-least-once semantics explicit? |
| 3 | [Architecture boundaries](ARCHITECTURE_BOUNDARIES.md) | Are module ownership, dependency direction, and transaction boundaries coherent? |
| 4 | [ADR-001](adr/0001-modular-monolith-api-worker-runtime.md) | Is one artifact with separate API/worker processes the right starting runtime? |
| 5 | [ADR-002](adr/0002-postgresql-backed-delivery-jobs.md) | Is PostgreSQL-backed work coordination justified and bounded? |
| 6 | [Runtime defaults](DELIVERY_RUNTIME_DEFAULTS.md) | Are initial timeout, lease, retry, polling, and concurrency values internally consistent? |
| 7 | [Database model Part 1](DATABASE_MODEL_PART1.md) | Are identity, project, key, endpoint, and subscription responsibilities complete? |
| 8 | [Database model Part 2](DATABASE_MODEL_PART2.md) | Can event acceptance, dispatch, recovery, history, and replay be persisted without two sources of truth? |
| 9 | [API contract](API_CONTRACT.md) | Can the dashboard and publisher demonstrate every MVP workflow without leaking internal state? |
| 10 | [Security baseline](SECURITY_BASELINE.md) | Are sessions, credentials, signing, SSRF, ownership, and redaction concrete enough to implement and test? |

`PROJECT_STATUS.md` is the durable progress ledger. `docs/AGENT_CONTEXT.md` is the compact working memory for later agent sessions.

## 4. Decisions now locked for implementation

- Modular monolith with `identity`, `project`, `endpoint`, and `delivery` capabilities.
- One build artifact/container image; each process starts in exactly one explicit `api` or `worker` mode.
- PostgreSQL is the source of truth and the Portfolio v1 job transport.
- Event acceptance and complete route fan-out commit atomically.
- At-least-once dispatch, no ordering guarantee, and at most five started attempts.
- Short claims with database-time leases and fencing tokens; no database transaction spans outbound HTTP.
- Attempt records are append-only; recovery records an unfinished started attempt as immutable `UNKNOWN`.
- React + Vite + TypeScript dashboard using bounded REST polling rather than SSE/WebSocket.
- Owner browser authentication uses Spring Security and PostgreSQL-backed sessions with CSRF.
- Publisher authentication uses project API keys whose raw secret is shown once and stored only as a peppered digest.
- Each endpoint has a one-time recoverable encrypted signing secret and signs exact outbound bytes with versioned HMAC-SHA-256.
- Every dispatch re-resolves, validates, and pins its destination address; redirects are disabled.
- No hard deletion of owners, projects, or endpoints in v1; history is retained for a bounded period.

Changing one of these decisions requires explaining the new evidence, affected invariants, migration/compatibility impact, and rollback path. A new ADR is needed only for a material architectural reversal.

## 5. Intentionally deferred implementation choices

The following are not Phase 0 gaps. They should be chosen close to the code slice that needs them:

- Maven module/package layout details and the exact Spring component-selection mechanism for runtime modes;
- Flyway/Liquibase choice, PostgreSQL development version, physical DDL, indexes, and claim SQL;
- exact JPA versus JDBC responsibility at hot queue paths;
- concrete Java HTTP client capable of SSRF-safe IP pinning;
- JSON canonicalization and version encoding;
- cloud provider/service mapping, encryption key provider, secret manager, IAM, and HTTPS termination;
- measured pool sizes, JVM/container limits, and performance targets;
- final frontend CSP and UI structure.

These decisions must preserve the locked behavior above. They are not permission to introduce brokers, caches, or orchestration platforms without evidence.

## 6. Phase 0 exit checklist

- [x] MVP scope and non-goals are explicit.
- [x] Happy path and important failure paths have acceptance criteria.
- [x] Delivery invariants and state transitions define concurrency behavior.
- [x] Modules own their data and dependencies are acyclic.
- [x] API and worker runtime boundaries are documented.
- [x] PostgreSQL job coordination and revisit triggers are recorded.
- [x] Runtime defaults form a bounded, testable starting configuration.
- [x] Conceptual data covers configuration, events, queue state, attempts, diagnostics, and replay.
- [x] Owner and publisher HTTP contracts cover the MVP workflow.
- [x] Authentication, authorization, credential storage, HMAC, SSRF, and redaction have a baseline.
- [x] Future tests required to prove critical claims are named.
- [ ] Project owner has reviewed and accepted this Phase 0 batch.

Phase 0 is complete when the final checkbox is approved. Approval does not mean every implementation choice is frozen; it means coding may start without reopening product or architecture fundamentals.

## 7. First Phase 1 slice after approval

Create only the backend capability skeleton and executable boundary guardrails:

- establish packages for `identity`, `project`, `endpoint`, and `delivery`;
- define the allowed public-contract direction without business implementation;
- add ArchUnit tests that reject forbidden reverse dependencies and repository/entity leakage;
- compile and run the tests.

This slice should not create database migrations, entities, controllers, security configuration, runtime workers, or frontend behavior. Its learning goal is to turn the documented modular-monolith boundary into an executable constraint before feature code grows around it.

## 8. Owner review checklist

Before replying with approval, verify that you can explain:

1. why an event and all initial deliveries must commit together;
2. why a database transaction must not remain open during outbound HTTP;
3. why a lease needs a fencing token and why an expired worker cannot finalize normally;
4. why `STARTED` consumes attempt budget and recovery may produce `UNKNOWN`;
5. why at-least-once means the receiver still needs idempotency;
6. why PostgreSQL is sufficient for v1 and what evidence would justify a broker;
7. why browser sessions and publisher API keys are separate authentication contexts;
8. why URL validation without connection pinning does not stop DNS rebinding.

Questions or changes discovered during this review should be resolved before Phase 1. Otherwise, the continuation signal is: **`bắt đầu Phase 1`**.
