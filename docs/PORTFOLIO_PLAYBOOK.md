# RelayForge Portfolio, CV, Interview, and Demo Playbook

Use this guide after reviewing the linked evidence. It is a speaking aid, not
a replacement for understanding the code and runbooks.

## 1. Thirty-second project description

> RelayForge is an outbound webhook delivery platform built as a Java modular
> monolith. The API persists an event, routing snapshot, and idempotency record
> atomically in PostgreSQL. A separate worker claims due deliveries with
> leases/tokens, sends signed HTTP outside a database transaction, and records
> bounded retry or recovery state. I chose at-least-once delivery because a
> remote HTTP outcome can be ambiguous. I deployed it with Docker Compose on
> EC2 and measured local performance and failure/recovery scenarios.

## 2. Evidence-based CV bullets

Choose two to four bullets appropriate for the role; do not use all of them as
a keyword list.

- Built RelayForge, a Java 25/Spring Boot outbound webhook platform using
  PostgreSQL as the transactional event, delivery, attempt, and idempotency
  source of truth; separated API and worker runtime modes from one artifact.
- Implemented leased `FOR UPDATE SKIP LOCKED` delivery claims with opaque
  tokens, conditional finalization, equal-jitter retry, and durable `UNKNOWN`
  recovery, preserving at-least-once semantics without holding a database
  transaction during HTTP I/O.
- Secured owner operations with Spring Security sessions and CSRF; implemented
  project-scoped HMAC-protected publisher keys, one-time encrypted signing
  secrets, owner-scoped history, and SSRF-resistant outbound destination
  validation.
- Built a Docker Compose/EC2 delivery path with Caddy TLS, immutable Git-SHA
  Docker Hub images, GitHub Actions quality/deploy gates, and PostgreSQL
  backup/rollback procedures.
- Added local Prometheus/Grafana, k6, and JVM diagnostics; a bounded baseline
  accepted 1,275 publishes with zero HTTP errors and drained 1,275 successful
  deliveries, while controlled failure drills proved timeout, retry, worker
  crash/`UNKNOWN`, and five-attempt exhaustion behavior.

Do **not** claim: exactly-once delivery, high availability, production p95,
autoscaling, a managed database, or that 1,275 requests represent production
throughput.

## 3. Interview questions to rehearse

### Why PostgreSQL jobs instead of Kafka or RabbitMQ?

Event acceptance, idempotency, routing snapshot, delivery creation, and attempt
history need one atomic transaction in Portfolio v1. PostgreSQL supplies that
without an outbox/consumer/offset consistency problem. A broker becomes
justified when measured throughput, independent scaling, retention, or replay
requirements exceed this bounded queue design.

### Why a modular monolith instead of microservices?

API and worker already need process failure isolation, but the business
capabilities share delivery invariants and one transactional source of truth.
Separate services now would add network contracts and distributed consistency
without a measured reason. Modules and public contracts make later extraction
possible without paying that cost upfront.

### What does `FOR UPDATE SKIP LOCKED` solve?

It allows multiple workers to find due rows without waiting behind work another
worker already locked. It improves concurrent claim throughput. It does not
prove a worker owns an HTTP request forever; the claim token and lease are the
current-state fence.

### Why have both a claim token and a lease?

A lease makes abandoned work recoverable. A token lets PostgreSQL reject a
late/stale worker's completion after a newer owner exists. Neither prevents the
old process from having already sent HTTP, so exactly-once HTTP is still not
promised.

### Why is `UNKNOWN` immutable?

After a started attempt and worker/database failure, RelayForge cannot prove
whether the receiver applied the request. Rewriting `UNKNOWN` to success from a
late result would corrupt auditable history. It records late diagnostics
separately and retries under the attempt budget.

### Why not keep `@Transactional` open while sending the webhook?

Remote HTTP can take up to the dispatch deadline or fail unpredictably. Holding
a row lock and Hikari connection during that wait blocks claims, increases pool
pressure, and makes outages spread into PostgreSQL. RelayForge commits attempt
start first, sends HTTP, then finalizes conditionally in another short
transaction.

### Why JPA in some places and JDBC in others?

Projects/endpoints are conventional owner-scoped aggregates where Hibernate's
persistence context and optimistic versioning are useful. Delivery claiming,
conditional state transitions, partial indexes, PostgreSQL time, and history
queries are queue-specific; explicit JDBC makes their locking and SQL visible.

### What did local performance evidence actually show?

The bounded local k6 run accepted 1,275 publishes with zero HTTP errors and
publish p95 16.49 ms on the recorded machine. It showed a local comparison
point, not production capacity. During the run, backlog/permits were observed
alongside API latency so API acceptance was not mistaken for completed delivery.

### What happens if the worker dies after an outbound request starts?

The Group 18 drill killed the local worker after a slow receiver observed the
request. Lease recovery recorded attempt one as `UNKNOWN`; a later attempt
succeeded. Receiver-side idempotency is necessary because duplicate external
effects remain possible by design.

### Why is image rollback not automatic?

An older application image may be incompatible with a newer Flyway schema. The
release script stops on failure after health checks; the operator takes a
verified backup and chooses rollback or fix-forward based on compatibility.

## 4. Seven-minute demo sequence

1. Open the public dashboard or local dashboard and explain API versus worker
   runtime modes. Do not display credentials or one-time secrets on screen.
2. Create/select a project, create a publisher key, and explain why its raw
   value appears once only.
3. Create an enabled endpoint subscribed to one event type; explain that event
   routing is snapshotted at acceptance.
4. Publish one event and show delivery history plus signed receiver evidence.
5. Open Grafana and distinguish API publish p95 from ready backlog and worker
   permits.
6. Show [Resilience Evidence](RESILIENCE_EVIDENCE.md): 500 retry, timeout,
   `UNKNOWN`, and exhaustion. Explain one failure path instead of performing a
   disruptive exercise live.
7. Show the GitHub Actions workflow and EC2 release/restore runbooks. State
   the single-host limitation honestly.

## 5. Questions to ask the interviewer

- How does your current platform decide whether a webhook side effect is
  idempotent or at-least-once?
- Which signals distinguish a slow receiver from a worker/database bottleneck?
- How are schema migrations and image rollback coordinated in your release
  process?

## References

- [Root README](../README.md)
- [Performance Baseline](PERFORMANCE_BASELINE.md)
- [Resilience Evidence](RESILIENCE_EVIDENCE.md)
- [Architecture Boundaries](ARCHITECTURE_BOUNDARIES.md)
- [Production Release Runbook](PRODUCTION_RELEASE_RUNBOOK.md)
