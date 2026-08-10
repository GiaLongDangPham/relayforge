# Current Task

Status: Completed

## Goal

Map only `owner_accounts` through JPA inside the `identity` module and prove persistence-context plus optimistic-version behavior against PostgreSQL.

## Learning outcome

Understand assigned identifiers, entity lifecycle states, first-level identity, dirty checking, flush timing, database-sourced timestamps, and how `@Version` prevents a detached stale state from overwriting a newer committed revision.

## Scope

- Add Spring Boot Data JPA support without changing Flyway ownership of DDL.
- Map only `public.owner_accounts` in an `identity` internal persistence package.
- Use application-assigned UUID and boxed `Long` optimistic version.
- Map PostgreSQL `timestamptz` to Java `Instant` and source lifecycle timestamps from the database.
- Validate the Flyway-managed schema at application startup.
- Prove persist/load, persistence-context identity, dirty checking/version increment, and stale detached merge rejection.

## Decisions and trade-offs

- Use `EntityManager` directly in this slice. A repository would hide whether JPA calls `persist` or `merge` before the entity-state rules are understood.
- Expect JPA's `OptimisticLockException` at this direct `EntityManager` boundary; Spring's translated optimistic-lock exception belongs to a later repository adapter boundary.
- Keep Flyway as the only schema creator and configure Hibernate `ddl-auto=validate`; Hibernate may reject a mismatched mapping but may not mutate the schema.
- Use boxed `Long version`, initially null in a new Java object and seeded by Hibernate on persist. This remains compatible with Spring Data's future new-entity detection for assigned UUIDs.
- Use Hibernate's database-sourced creation/update timestamp annotations. This is a deliberate provider-specific mapping because standard JPA does not express PostgreSQL-time generation.
- Disable Open EntityManager in View. Lazy database access must not leak into future controllers or view rendering.
- Do not implement equality/hash-code policy until an entity relationship or collection requires one; persistence-context identity is tested explicitly instead.

## Out of scope

- Spring Data repository interfaces/adapters, repository ports, bootstrap seeding, BCrypt, authentication, sessions, or HTTP.
- Login normalization/validation messages, password rotation use case, retry policy after optimistic conflict, or concurrent threads.
- Projects or any additional database migration.

## Test evidence

- Hibernate validates the existing V2 schema and starts against PostgreSQL 17.10.
- Persist assigns version zero and PostgreSQL timestamps; a repeated find in one persistence context returns the same managed instance.
- Clearing the persistence context and finding again reconstructs the stored entity with `Instant` timestamps.
- Dirty checking updates a changed hash and increments version exactly once without an explicit update call.
- Merging a detached stale revision fails with an optimistic-lock exception and does not overwrite the winning value.
- Runtime and architecture tests remain green.

## Definition of done

- Focused JPA/PostgreSQL tests and the full JDK 25 Maven suite pass with Docker.
- Independent review has no unresolved P0/P1.
- Project memory records verified behavior, limitations, and the next bounded slice.
- No repository, identity use case, security, HTTP, or unrelated table enters this slice.

## Actual verification

- Spring Boot selected Hibernate ORM 7.4.1 and Flyway completed V1/V2 before Hibernate schema validation.
- The first focused run passed 16 behaviors and failed only the expected exception-type assertion: direct `EntityManager` produced JPA `OptimisticLockException`, while the test expected Spring's repository-level translation.
- After aligning the assertion with the actual boundary, focused PostgreSQL/JPA tests passed 17/17.
- The final full JDK 25 Maven suite passed 33/33: seventeen database/JPA invocations, nine runtime/application tests, and seven architecture rules.
- Independent review returned `READY` with no P0/P1; it confirmed entity encapsulation, assigned UUID/boxed version semantics, DB-sourced timestamps, Flyway ownership, transaction detachment, dirty checking, and stale-winner protection.
- Final `git diff --check` passed after the progress documentation update.

## Remaining scope

- The tests use sequential committed transactions and a detached stale entity; they prove JPA optimistic-version semantics but are not a concurrent-thread bootstrap test.
- No repository or exception-translation adapter exists, so the direct boundary intentionally exposes JPA `OptimisticLockException`.
- Login canonicalization, BCrypt, bootstrap idempotency, retry/conflict handling, authentication, session, and HTTP behavior remain unimplemented.
- The existing non-failing Mockito/Byte Buddy future dynamic-agent warning remains inherited from the test stack.

## Next task

Implement the owner-bootstrap use case with the smallest identity-owned repository/password-hash ports and prove race-safe idempotency by canonical login. Keep authentication, sessions, HTTP, and every other table out of that slice.
