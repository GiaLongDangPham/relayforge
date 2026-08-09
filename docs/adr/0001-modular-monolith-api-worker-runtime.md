# ADR-001: Modular Monolith with Separate API and Worker Runtime Modes

- Status: Accepted
- Date: 2026-08-09
- Decision owners: RelayForge project
- Supersedes: None

## Context

RelayForge must accept an event durably and create its complete delivery set atomically, then perform outbound webhook attempts asynchronously. A slow or failing receiver must not hold a publisher request or a database transaction open.

The Portfolio v1 team is one developer. The project needs meaningful transaction, locking, failure-recovery, deployment, and operations work, but it does not yet have evidence that independently deployed services or a message broker solve a measured constraint.

Running API and worker behavior in one process would be operationally simple, but one worker crash, resource leak, or restart would also remove the API. Building separate services would isolate them, but would add network contracts, independent versioning, distributed consistency, and more deployment surface before those costs are justified.

The architecture therefore needs process-level isolation without creating a distributed system inside the product prematurely.

## Decision

RelayForge Portfolio v1 will be a modular monolith with four business-capability modules: `identity`, `project`, `endpoint`, and `delivery`.

The backend will produce one versioned application artifact and one container image. Each running instance must select exactly one explicit runtime mode:

- `api` serves owner and publisher use cases and does not start delivery polling, lease recovery, or outbound webhook dispatch;
- `worker` claims and processes delivery work and does not expose owner or publisher business endpoints.

API and worker run as separate operating-system processes or containers from the same image. They may have different instance counts and resource limits, but they are released as one backend version.

The processes do not call each other over HTTP. PostgreSQL is the source of truth and coordination mechanism for accepted events, delivery work, leases, and attempts. Correctness-critical module collaboration remains synchronous and in-process, with local PostgreSQL transactions. Portfolio v1 does not add a broker, outbox pipeline, or asynchronous in-process event bus for those state changes.

Environment profiles configure environments such as development, test, or production. They do not replace the explicit runtime-mode selection. Outside focused tests, startup with a missing, unsupported, or ambiguous mode must fail rather than silently enabling both roles.

## Decision details

### Build and deployment unit

- One source repository and backend build.
- One backend artifact and image digest per released version.
- The same image may be launched as one or more API instances and one or more worker instances.
- API and worker share module contracts, persistence migrations, and release compatibility.
- Exact Maven structure, Spring conditional configuration, container entrypoint, and cloud service definitions are follow-up implementation decisions.

### Coordination and consistency

- API persists accepted work in PostgreSQL before acknowledging it.
- Worker discovers and claims work from PostgreSQL using the reviewed lease and claim-token model.
- No database transaction remains open during outbound HTTP.
- No internal API-to-worker request is required for progress.
- Worker downtime accumulates durable backlog; API downtime does not stop an already-running worker from processing committed backlog.

### Module and adapter boundaries

- Business code is organized by capability rather than global technical layers.
- Runtime composition and inbound adapters may depend on module public contracts but contain no business rules.
- Outbound adapters depend on ports owned by their module.
- JPA entities and repositories are not public cross-module contracts.
- ArchUnit tests will enforce the allowed dependency graph when the module skeleton is implemented.

## Alternatives considered

### 1. One process running API and worker together

This is the smallest deployment topology.

It is rejected as the Portfolio v1 operating model because API and worker would share lifecycle, CPU, memory, thread pools, and failure fate. Worker restart or resource exhaustion would unnecessarily remove event-ingestion availability, and worker capacity could not be adjusted independently.

### 2. Two independently built Spring Boot applications sharing a database

This provides process isolation and allows different dependency graphs.

It is rejected for v1 because two artifacts can drift in version and persistence expectations while still being tightly coupled through one database. It adds build, configuration, rollout, and compatibility work without creating true service autonomy.

### 3. API and worker microservices connected by a message broker

This could provide an explicit asynchronous transport and independent deployment.

It is rejected for v1 because atomic event acceptance would then need an outbox or another dual-write solution, consumers would require broker-specific duplicate and offset handling, and local/cloud operation would gain another stateful dependency. PostgreSQL-backed work already provides the transaction and recovery problems the project intends to study.

### 4. Serverless functions for API and individual delivery attempts

This can scale invocation count automatically and reduce idle compute.

It is rejected as the initial design because lease recovery, bounded concurrency, connection-pool behavior, long-running observability, and predictable low-cost local parity would become provider-specific before baseline behavior exists.

## Consequences

### Positive

- Event acceptance and delivery intent remain inside one local transaction boundary.
- API and worker failures are isolated at process level.
- Worker instance count and resources can change without multiplying API instances.
- There is no internal network API, broker, or cross-service version contract to build and operate.
- One artifact prevents API and worker code from silently using different domain rules in the same release.
- The topology can demonstrate backlog, recovery, graceful shutdown, and independent runtime health without starting with microservices.

### Negative

- API and worker share a release cadence and can share a code defect.
- PostgreSQL remains a common availability and contention boundary.
- The image contains code for both modes, so conditional component activation must be tested carefully.
- A database migration must remain compatible with the rollout of both modes; migration strategy still requires a focused decision.
- Logical module boundaries can erode because they are not network boundaries; architecture tests and review are required.
- Independent service extraction later would still require explicit data and contract separation work.

## Failure and operational implications

| Failure or pressure | Expected behavior under this decision |
| --- | --- |
| API process stops | New requests fail, but existing worker processes continue committed delivery work. |
| All worker processes stop | API may continue accepting durable events; backlog grows until a worker recovers. |
| One worker is slow or crashes | Other workers continue; lease recovery makes abandoned claims eligible according to the delivery model. |
| PostgreSQL is unavailable | API readiness fails when durable acceptance is impossible; workers stop claiming/finalizing and rely on recovery after PostgreSQL returns. |
| Receiver is slow | Only worker resources are occupied; no API request or database transaction waits for receiver I/O. |
| Worker load grows | Increase worker instances or resources first; claim contention and database capacity must be measured. |
| Defective shared release | Both modes may be affected; one artifact simplifies rollback but does not remove shared-code blast radius. |

## Guardrails and verification

The decision is not considered implemented until automated evidence demonstrates:

1. missing, unsupported, or ambiguous runtime mode fails startup;
2. API mode creates no polling, recovery, or outbound-dispatch components;
3. worker mode exposes no owner or publisher business controllers;
4. both modes are built from the same backend artifact and container image;
5. stopping the worker allows accepted backlog to remain durable and process after restart;
6. stopping the API does not prevent an existing worker from processing committed backlog;
7. multiple worker instances preserve the lease and claim-token invariants;
8. outbound HTTP runs without an open database transaction;
9. architecture tests enforce the approved module dependency graph;
10. health, logs, and metrics identify the active runtime mode.

## Revisit triggers

This decision should be reconsidered only when evidence shows one or more of the following:

- PostgreSQL claim polling or contention is a measured throughput or latency bottleneck that query and index work cannot reasonably correct;
- API and worker require incompatible release cadences or dependency sets;
- a distinct team needs independent ownership and deployment of one capability;
- worker failure or resource pressure cannot be contained with separate processes, resource limits, and connection pools;
- cloud cost or scaling measurements show that the shared artifact/runtime approach is materially inefficient;
- a required integration demands a durable external event stream rather than RelayForge's internal delivery queue.

The presence of a clean module boundary, the popularity of microservices, or a desire to add Kafka keywords is not sufficient evidence by itself.

If a trigger is met, a new ADR must describe the measured evidence, migration path, data ownership, failure semantics, and rollback plan. It supersedes this ADR only after acceptance.

## Follow-up decisions

- Exact Spring mechanism and property validation for `api` and `worker` modes.
- Module package skeleton and ArchUnit enforcement rules.
- PostgreSQL schema, claim SQL, indexes, isolation, and migrations.
- HTTP timeout, lease, retry, and worker-concurrency defaults.
- Container entrypoint, local Docker Compose topology, and cloud runtime.
- Safe database-migration ownership during API and worker rollout.

## References

- [RelayForge Architecture Boundaries](../ARCHITECTURE_BOUNDARIES.md)
- [RelayForge Delivery Model](../DELIVERY_MODEL.md)
- [RelayForge Portfolio v1 Requirements](../REQUIREMENTS.md)
