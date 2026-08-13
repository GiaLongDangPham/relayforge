# Current Task

Status: Complete

## Goal

Complete Group 5: publisher event acceptance, publish idempotency, immutable event persistence, and atomic initial routing to `PENDING` deliveries.

## Decisions

- Delivery persistence uses PostgreSQL-specific JDBC where JSONB and `INSERT ... ON CONFLICT DO NOTHING RETURNING` make the idempotency decision explicit and transaction-safe.
- Event fingerprint v1 hashes a canonical JSON value plus the exact normalized event type. A repeated key still compares persisted event type and JSON semantics as the correctness fallback.
- The delivery application use case owns one `READ COMMITTED` transaction. Endpoint routing joins it through a narrow endpoint public query; event and every original delivery commit or roll back together.
- Publisher HTTP authentication is Bearer API-key only. It ignores dashboard sessions and CSRF, returns generic 401 for absent/invalid/revoked keys, and returns 403 when a valid key names a different project path.
- V7 implements only `events` and original `deliveries`; attempt, claim/lease, retry, replay, and history tables are deferred until their workflows begin.

## Out of scope

Worker polling/claiming, endpoint enable checks for claims, signing-secret decryption, outbound HTTP/HMAC, attempts, retry/recovery, replay, owner history endpoints, retention, cloud, and frontend work.

## Evidence required

- Concurrent equivalent publishes converge to one event and complete original delivery set; same key/different content conflicts without mutation.
- Only enabled endpoints with an exact matching subscription route; endpoint changes after acceptance do not rewrite prior deliveries; zero-route events persist.
- PostgreSQL rejects cross-project delivery relationships and invalid pending-state rows.
- HTTP rejects bad/revoked publisher keys, wrong path project, oversized body, and idempotency conflict; it accepts valid publish without a session or CSRF token.

## Verification evidence

- 2026-08-12: JDK 25 focused regression passed 35/35: fingerprint (1), publisher filter (2), API composition (1), module boundaries (7), PostgreSQL foundation (21), publish transaction/concurrency (2), and real publisher HTTP (1). Docker Server 29.6.2 ran PostgreSQL 17.10 containers.
- The first container startup exposed a real Spring proxy failure: the `@Transactional` endpoint-routing service was `final`, so CGLIB could not proxy it. Removing only `final` restored the required proxy and the complete regression passed.
- Independent review cleared strict JSON-field, Problem Details code, replay-schema, and final transactional-proxy checks with no P0/P1 findings. `git diff --check` passed.
