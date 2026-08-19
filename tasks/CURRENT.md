# Current Task

Status: Complete

## Goal

Complete Group 9: durably finalize one observed dispatch attempt, persist bounded retries and recovery, and compose the first worker polling lifecycle.

## Decisions

- A delivery-owned completion policy maps a `DispatchObservation` to an immutable attempt observation and either a terminal delivery state or a PostgreSQL-time retry delay. Randomness is injectable so retry bounds have deterministic unit evidence.
- Normal finalization is one short `READ COMMITTED` conditional transaction. It must match `CLAIMED`, the current token, unexpired PostgreSQL lease, and `STARTED` attempt before atomically completing both attempt and delivery and clearing the token/lease.
- After HTTP, the worker may retry only finalization database work with the documented bounded delays while PostgreSQL reports at least one second of current lease remains. It never invokes the HTTP dispatcher twice for one started instruction.
- Expired `STARTED` claims are recovered in a short transaction as immutable `UNKNOWN`, then become retry-scheduled or `EXHAUSTED`. A result arriving after that transition may create at most one diagnostic record and never rewrites the delivery.
- Worker mode starts a bounded polling/recovery lifecycle. It uses virtual-thread tasks behind the existing permit reservation boundary; API mode composes none of these worker adapters.

## Out of scope

Replay, owner history endpoints, retention, metrics/health, full graceful-shutdown policy, cloud, and frontend work.

## Evidence required

- Unit evidence proves completion classification, jitter boundaries, exhaustion at attempt five, and finalization retry without a second HTTP dispatch.
- PostgreSQL evidence proves atomic attempt/delivery finalization, stale or expired token fencing, due-time persistence from PostgreSQL time, and `UNKNOWN` post-attempt recovery.
- Worker evidence proves a normal claim-to-finalization lifecycle, bounded permits, and worker-only composition. API mode must not start worker scheduling.

## Verification evidence

- 2026-08-14: clean JDK 25 focused non-container verification passed 13/13: retry policy (2), one-dispatch/finalization-retry behavior (1), permit admission (3), and architecture boundaries (7). `git diff --check` passed.
- 2026-08-14: JDK 25 Docker/Testcontainers regression passed 11/11: claim (3), attempt start (3), conditional finalization/recovery/late diagnostics (3), worker lifecycle against a local HTTP receiver (1), and packaged worker composition (1). It migrated a new PostgreSQL database through V10.
- 2026-08-14: independent read-only review returned `READY` with no P0/P1. The reviewer could not use Docker in its sandbox; the PostgreSQL result above is the primary execution evidence.
