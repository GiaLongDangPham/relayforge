# Current Task

Status: Completed

## Goal

Implement a controlled owner-bootstrap use case that canonicalizes login, hashes plaintext before the transaction, and converges concurrent attempts on one stored owner without replacing the winner's hash.

## Learning outcome

Understand application-owned transaction boundaries, CPU work outside transactions, PostgreSQL `ON CONFLICT` behavior, why catching a unique violation inside the same transaction is unsafe, and how ports keep cryptography and persistence details behind an identity-owned use case.

## Scope

- Add one identity public bootstrap contract returning only owner ID, canonical login, and `CREATED|EXISTING`.
- Canonicalize input with trim plus locale-stable lowercase, then enforce the exact stored-login alphabet and length.
- Reject null/blank plaintext before hashing without adding a broader password policy.
- Add an identity-owned password-hashing port and Spring Security Crypto BCrypt adapter at cost 12.
- Hash before opening the short database transaction.
- Add an identity-owned bootstrap store port and PostgreSQL JDBC adapter using conflict-safe insert-or-read behavior.
- Prove first creation, repeated idempotency, case-variant convergence, hash preservation, and concurrent convergence against PostgreSQL.

## Decisions and trade-offs

- Use `INSERT ... ON CONFLICT (login_name) DO NOTHING RETURNING id`. Catching a unique violation and then selecting in the same PostgreSQL transaction would fail because the violation aborts that transaction.
- Let every competing caller hash before attempting the insert. Some losing CPU work is acceptable for controlled bootstrap and prevents expensive BCrypt from extending a database transaction.
- Use BCrypt cost 12 exactly as the security baseline specifies. Deployment benchmarking may change the cost later, but this slice does not invent a new password algorithm.
- Accept `char[]` at the public use-case boundary and hash a temporary copy that is cleared afterward. This reduces accidental retention but cannot erase every internal allocation made by the encoder/JVM.
- Keep the application service responsible for the transaction through `TransactionTemplate`; the JDBC adapter joins it and does not declare an independent transaction.
- Pin this transaction to PostgreSQL `READ COMMITTED`: after a conflicting insert statement waits for the winner, the following select receives a new statement snapshot that can see the committed row.
- Use PostgreSQL JDBC for this race-sensitive insert while retaining JPA for normal entity mapping. Choosing JPA globally does not prohibit explicit SQL where database conflict semantics are the behavior being implemented.

## Out of scope

- Automatic environment/configuration startup runner, dashboard login, password verification, credential rotation, Spring Security filters, sessions, CSRF, or HTTP.
- General password strength rules, breach checks, rate limiting, or BCrypt performance tuning.
- Project data or another migration.

## Test evidence

- A mixed-case padded login is stored once in canonical form with a BCrypt cost-12 hash and no plaintext.
- Repeated bootstrap returns `EXISTING`, the same owner ID, and preserves the original hash even when another password is supplied.
- Concurrent attempts using case/spacing variants produce exactly one `CREATED`, one database row, one owner ID, and a stored hash matching only the winning password.
- Invalid login and blank plaintext fail before a row is created.
- Full runtime, architecture, migration, and JPA suites remain green.

## Definition of done

- Focused owner-bootstrap tests and the full JDK 25 Maven suite pass with Docker.
- Independent review has no unresolved P0/P1.
- Project memory records verified behavior, limitations, and the next bounded slice.
- No authentication/session/HTTP behavior or unrelated table enters this slice.

## Actual verification

- `OwnerBootstrapServiceTests` passed 1/1 and proves hashing occurs before the explicit `READ COMMITTED` transaction, the store runs inside it, the caller's array is not mutated, and the temporary copy is cleared.
- `OwnerBootstrapIntegrationTests` passed 4/4 against `postgres:17.10-alpine`, including a four-caller concurrency test that produced one `CREATED`, three `EXISTING`, one row, one ID, and the winner's hash only.
- The final JDK 25 Maven suite passed 38/38 with zero failures, errors, or skips.
- Independent read-only review returned `READY` with no P0/P1 findings.
- One sandboxed full run failed because Testcontainers could not access Docker's Windows named pipe; the permitted outside-sandbox rerun passed and confirms this was an environment boundary rather than an application failure.

## Remaining

- The use case is not yet invoked automatically from environment or application configuration.
- Dashboard authentication, password verification, sessions, HTTP, password rotation, and recovery remain unimplemented.
- Clearing the temporary `char[]` reduces accidental retention but cannot erase immutable/internal allocations made by libraries or the JVM.

## Next recommended slice

Add a controlled, disabled-by-default owner-bootstrap startup adapter for `api` mode, with secret-redacted configuration and tests for first startup, restart idempotency, disabled behavior, and worker exclusion.
