# Current Task

Status: Complete

## Goal

Complete Group 6: PostgreSQL-backed worker claim, pre-attempt lease recovery, and local permit-admission foundation.

## Decisions

- A short `READ COMMITTED` transaction takes an enabled-endpoint snapshot, locks due `PENDING` deliveries with `SKIP LOCKED`, then locks and rechecks only candidate endpoints before changing eligible rows to `CLAIMED`.
- The endpoint recheck uses a PostgreSQL row lock. A concurrent disable either commits before the recheck and excludes the delivery, or waits until the claim commits; it cannot commit between eligibility and claim.
- Claim capacity is bounded by permits reserved before the database transaction. A returned claim retains one bound permit until its future handling task explicitly completes; no background loop starts before Group 7 can start attempts safely.
- A pre-attempt expired claim returns to `PENDING` using PostgreSQL time and clears its current token/lease without consuming attempt budget.

## Out of scope

Attempt-start records, endpoint URL/secret snapshots, outbound HTTP/HMAC/SSRF, attempt completion, retry scheduling, post-attempt `UNKNOWN` recovery, replay, history endpoints, retention, cloud, and frontend work.

## Evidence required

- Concurrent workers create at most one active claim per delivery, with distinct current tokens and PostgreSQL-time lease values.
- Disabled endpoints remain `PENDING`; an arbitrarily ordered paused backlog cannot prevent the static enabled due set from filling requested claim capacity.
- A concurrent endpoint disable cannot commit between the final eligibility recheck and a successful claim commit.
- Expired pre-attempt claims return to `PENDING` with no consumed attempt; recovery cannot clear a newer claim.
- Worker permit admission never claims more deliveries than reserved permits, releases unused permits after a short claim, and leaves the worker non-web with no polling loop until attempt start exists.

## Verification evidence

- 2026-08-13: JDK 25 focused Docker/Testcontainers regression passed 46/46: permit admission (3), runtime binding (7), API/worker composition (2), module boundaries (7), PostgreSQL foundation (21), claim integration (3), publish integration (2), and real publisher HTTP (1).
- PostgreSQL 17.10 applied V1-V8. Tests prove paused rows cannot consume a static enabled claim batch, concurrent workers produce one active claim, a final endpoint row lock blocks concurrent disablement, and expired pre-attempt claims return to `PENDING` without budget consumption.
- `git diff --check` passed. No full suite was run under the focused-test policy.
