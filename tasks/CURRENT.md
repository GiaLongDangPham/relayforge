# Current Task

Status: Completed

## Goal

Establish a real PostgreSQL persistence test foundation with JDBC pooling, Flyway migrations, and Testcontainers before creating any RelayForge business table.

## Learning outcome

Understand why integration tests for PostgreSQL-specific behavior need a real PostgreSQL engine, how Spring Boot service connections supply container credentials, and how Flyway turns schema evolution into ordered, auditable application inputs.

## Scope

- Use Flyway SQL migrations managed by the Spring Boot dependency set.
- Use PostgreSQL 17.10 as the pinned test baseline and `public` as the v1 application schema.
- Add Spring JDBC, PostgreSQL driver, Spring Boot Flyway/PostgreSQL support, Spring Boot Testcontainers, and PostgreSQL/JUnit Testcontainers modules.
- Add a technical V1 migration that verifies PostgreSQL 17 or newer without creating business tables.
- Prove a pooled JDBC connection, PostgreSQL version, Flyway history, and absence of business tables on a real container.
- Keep runtime-mode and architecture tests isolated and green.
- Remove the accidental bundled `api` runtime default so missing mode continues to fail outside focused tests.

## Decisions and trade-offs

- Choose Flyway over Liquibase because later migrations will intentionally use PostgreSQL SQL for constraints, indexes, and queue behavior. Liquibase's database-neutral changelog and richer diff/rollback tooling do not solve a current need.
- Pin `postgres:17.10-alpine`, the current supported minor in the chosen mature major line. A floating major tag would make test evidence change without a code diff.
- Keep one `public` schema. Separate schemas would not enforce module ownership inside one database user and would add search-path and cross-schema migration complexity.
- Let Spring Boot manage library versions so its tested Flyway/Testcontainers set remains coherent.
- Use `@ServiceConnection` so container connection details override only the integration-test context; no credentials enter application configuration.
- Defer production migration ownership. Local/test startup may run Flyway automatically, while cloud rollout may later use one migration job with API/worker instances validating compatibility.

## Out of scope

- Owner, project, endpoint, event, delivery, attempt, replay, session, or other business tables.
- JPA, entities, repositories, transaction use cases, claim SQL, or indexes.
- Docker Compose, persistent local volumes, cloud databases, migration rollback automation, and production credentials.

## Test evidence

- Docker-backed Spring context starts with a PostgreSQL 17.10 service connection.
- Auto-configured datasource is pooled and returns a valid PostgreSQL JDBC connection.
- Server major version is 17 and database/session time zone is UTC.
- Flyway applies exactly the technical V1 migration successfully and creates schema history.
- No RelayForge business table exists after migration.
- Runtime-mode focused tests do not need PostgreSQL and existing architecture rules remain green.

## Definition of done

- Focused PostgreSQL integration test and full JDK 25 Maven suite pass with Docker running.
- Independent review has no unresolved P0/P1.
- Persistence documents and project memory record only verified foundation decisions.
- No business schema or behavior enters this slice.

## Actual verification

- The first focused run exposed a Spring Boot 4.1 modularization detail: `flyway-core` alone provided no Flyway auto-configuration bean. Replacing it with `spring-boot-starter-flyway` fixed the context while retaining Boot-managed versions.
- The focused Docker-backed PostgreSQL test passed 2/2 against `postgres:17.10-alpine` after the final correction.
- The final JDK 25 Maven suite passed 18/18: two PostgreSQL foundation tests, nine runtime tests, and seven architecture rules.
- Flyway created `public.flyway_schema_history` and applied only V1; the test found zero other tables in `public`.
- Hikari returned a valid PostgreSQL connection whose session timezone is UTC and current schema is `public`.
- Independent review found one P1: `public` initially depended on the container's default search path. Explicit Flyway default-schema and Hikari search-path configuration plus a `current_schema()` assertion resolved it; re-review returned `READY` with no P0/P1.
- `git diff --check` is required once more after this final Markdown update.

## Remaining scope

- No RelayForge business table, JPA mapping, repository, transaction use case, index, or claim SQL exists.
- Production migration ownership and rollout compatibility checks remain a later deployment decision.
- Docker is required for the PostgreSQL integration test.
- The existing non-failing Mockito/Byte Buddy future dynamic-agent warning remains inherited from the Spring test stack.

## Next task

Create and Testcontainers-test only the V2 `owner_accounts` migration and its database invariants. Keep JPA, repositories, authentication behavior, and every other business table out of that slice.
