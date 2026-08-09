# Current Task

Status: Completed

## Goal

Create the first RelayForge business-table migration for `owner_accounts` and prove its database-owned invariants against real PostgreSQL.

## Learning outcome

Understand which invariants belong in PostgreSQL constraints and defaults, which remain application responsibilities, and why a migration test should exercise failing writes rather than only inspect table metadata.

## Scope

- Add only Flyway V2 for `public.owner_accounts`.
- Require application-supplied UUID identifiers.
- Enforce canonical lowercase ASCII login names, bounded storage, and global uniqueness.
- Require a bounded, nonblank, whitespace-free password-hash value without coupling the database to one hash format.
- Default optimistic version to zero and reject negative versions.
- Default lifecycle timestamps from PostgreSQL time.
- Update migration tests for the new schema version and expected table set.

## Decisions and trade-offs

- Accept login names matching `^[a-z0-9][a-z0-9._-]*$` under PostgreSQL's `C` collation. The database rejects noncanonical input instead of silently trimming or lowercasing it, and the ASCII meaning does not vary with deployment collation.
- Do not enforce a BCrypt prefix or cost in SQL. The security layer owns hash generation and verification, while the table requires the whitespace-free shape common to encoded hashes and supports later algorithm/cost changes.
- Do not generate UUIDs in PostgreSQL. The accepted model requires application-generated UUIDv4 and avoids a database extension.
- Use PostgreSQL defaults for `version`, `created_at`, and `updated_at`, but leave timestamp advancement on mutation to the future owner update use case.
- Test behavior through real inserts and constraint violations. Metadata assertions alone would not prove PostgreSQL actually rejects invalid state.

## Out of scope

- JPA entities, repositories, bootstrap services, Spring Security, password hashing, login endpoints, sessions, or seed configuration.
- Optimistic update SQL and concurrent bootstrap behavior.
- Projects, API keys, endpoints, events, deliveries, attempts, or indexes beyond constraints created for this table.

## Test evidence

- Flyway reaches V2 in `public` and the only business table is `owner_accounts`.
- A minimal valid row receives version zero and PostgreSQL timestamps.
- Duplicate canonical login is rejected and does not replace the existing password hash.
- Uppercase, whitespace, disallowed alphabet, empty, and overlength logins are rejected.
- Null/blank/whitespace-containing/overlength password hashes and negative versions are rejected.
- Omitting `id` is rejected and the column has no database default.

## Definition of done

- Focused PostgreSQL migration tests and the full JDK 25 Maven suite pass with Docker.
- Independent review has no unresolved P0/P1.
- Project memory records verified behavior and the next bounded slice.
- No application persistence or authentication behavior enters this slice.

## Actual verification

- Flyway applied V1 then V2 to a clean `public` schema on `postgres:17.10-alpine`.
- Focused JDK 25 PostgreSQL tests passed 14/14 after the final corrections.
- The final full Maven suite passed 30/30: fourteen database invocations, nine runtime tests, and seven architecture rules.
- Independent review found three P1 gaps: ordinary-space-only trimming, collation-dependent ASCII ranges, and missing evidence for application-owned UUIDs.
- `C` collation, a whitespace-free encoded-hash constraint, and metadata plus omitted-ID tests resolved all findings; re-review returned `READY` with no remaining P0/P1.
- Final `git diff --check` passed after the progress documentation update.

## Remaining scope

- The database cannot prove a stored value is BCrypt or that plaintext never reached the persistence call; bootstrap/security tests must prove those responsibilities later.
- Bootstrap idempotency, concurrent seed behavior, optimistic update SQL, and `updated_at` advancement are not implemented.
- No JPA mapping, repository, identity use case, authentication, session, or owner HTTP endpoint exists.

## Next task

Map only `owner_accounts` through JPA inside the `identity` module and prove insert/load plus optimistic-version behavior against PostgreSQL. Keep bootstrap and authentication out of that slice.
