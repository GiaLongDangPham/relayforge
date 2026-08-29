# Current Task

Status: Completed

## Goal

Complete Group 18 and Group 19 with repeatable evidence of RelayForge failure
handling and recovery operations. The work must prove the existing delivery
semantics rather than add another delivery mechanism or alter production
runtime defaults without measured justification.

## Decisions

- Group 18 uses only the existing local Compose receiver's `fail` and `slow`
  endpoints. It creates isolated local projects and publisher keys and keeps
  their raw material out of Git and command output.
- Failure scenarios must inspect owner-safe history plus bounded Prometheus
  metrics. They must not update PostgreSQL rows manually.
- A worker crash is simulated only on the local Compose worker. The expected
  durable result is `UNKNOWN` followed by normal lease recovery; no exactly-once
  claim is made.
- Group 19 creates a backup and restore target only from the local Compose
  PostgreSQL container. The restore container has no published port and is
  removed after validation.
- Image rollback evidence is an isolated compatibility check against a chosen
  immutable Docker Hub tag. It never changes the running EC2 tag.
- EC2 stop/start, live production restore, and a live production rollback are
  owner-operated disruptive actions. The repository provides a checked
  readiness procedure, not automation that performs those actions.

## Out of scope

New message brokers, worker concurrency/timeout tuning, production load,
production database restore, EC2 stop/start, live rollback, remote observability,
and automatic DuckDNS update.

## Evidence required

- A controlled 5xx result produces a retryable attempt and succeeds after the
  endpoint is corrected.
- A slow receiver exceeds the dispatch deadline, records a retryable timeout,
  and releases local capacity without Hikari pressure.
- A local worker kill after a started attempt produces `UNKNOWN` and bounded
  lease recovery rather than silently treating the result as success.
- A local custom-format PostgreSQL archive validates and restores into an
  isolated container with expected schema and durable business data.
- A known immutable backend image can start against that restored database, and
  a DuckDNS recovery command can verify expected DNS/HTTPS state after the
  owner updates the record.

## Completion evidence

- The Group 18 harness proved `500 → retry → success`, timeout →
  `DISPATCH_TIMEOUT → success`, and worker kill → `UNKNOWN → success`, each
  with exactly two attempt records. The exhaustion scenario recorded exactly
  five retryable failures and terminal `EXHAUSTED`, with no sixth attempt.
- After each final scenario, bounded local metrics returned to zero ready
  backlog, eight free permits, and zero pending Hikari connections.
- Group 19 created and validated a local custom PostgreSQL archive, restored it
  to an isolated private container with 12 Flyway history rows and durable
  business data, and started known image `a75ef093bc8d` to API readiness
  against it.
- DuckDNS resolved the expected IP and HTTPS returned 200. No production dump,
  restore, rollback, EC2 stop/start, or DNS mutation was performed.
