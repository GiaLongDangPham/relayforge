# Current Task

Status: Complete

## Goal

Complete Group 7: durable attempt start and an in-memory dispatch instruction boundary.

## Decisions

- Group 7 will add the durable `STARTED` attempt boundary: a transaction conditionally validates the claimed delivery, current token, PostgreSQL-time lease, enabled endpoint, and remaining budget; it creates the next attempt, increments the count, and replaces the initial lease with the attempt-execution lease.
- Attempt-start locks the endpoint configuration through its public contract. A concurrent disable or URL update linearizes before or after the snapshot; no attempt may start from an uncommitted/mixed configuration.
- The resulting in-memory dispatch instruction retains the exact URL snapshot and opaque encrypted signing material, but never a public raw signing secret. Group 8 will decrypt only when it signs/sends HTTP.
- No worker scheduler starts in this group: a loop must not create `STARTED` attempts until outbound dispatch and finalization exist.

## Out of scope

Outbound HTTP/HMAC/SSRF, worker polling/scheduling, attempt completion, retry scheduling, post-attempt `UNKNOWN` recovery, replay, history endpoints, retention, cloud, and frontend work.

## Evidence required

- PostgreSQL V9 enforces at most five numbered attempts, one unfinished `STARTED` attempt per delivery, immutable started/terminal shape, and a restrictive delivery relationship.
- A valid current claim atomically creates attempt number `attempt_count + 1`, snapshots the enabled endpoint URL/signing material, increments the count, and applies the PostgreSQL-time 20-second attempt lease.
- Expired/stale claims create no attempt; a disabled endpoint conditionally returns a valid current claim to `PENDING` with no budget use.
- Concurrent attempt starts for one claim create exactly one `STARTED` attempt and one durable count increment.
- No plaintext endpoint signing secret, raw payload, or exact destination URL can appear in dispatch-instruction diagnostics.

## Verification evidence

- 2026-08-14: final JDK 25 focused Docker/Testcontainers regression passed 49/49: attempt start (3), claim (3), PostgreSQL foundation (21), permit admission (3), runtime binding/composition (9), module boundaries (7), publish integration (2), and publisher HTTP (1).
- V1-V9 applied against PostgreSQL 17.10. Tests prove the persisted `STARTED` shape and one-started-attempt constraint, valid start/URL fingerprint/20-second PostgreSQL-time lease, expired claim rejection, disabled-endpoint release without budget use, pre-attempt recovery ignoring started work, and concurrent start convergence on one attempt.
- `git diff --check` passed. No deprecated API pattern was introduced in Group 7 production paths, and no full suite was run under the focused-test policy.
