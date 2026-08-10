# RelayForge Agent Context

Last updated: 2026-08-09

RelayForge is a portfolio learning project: an outbound webhook delivery platform that demonstrates reliable at-least-once dispatch under failure and concurrency.

Current phase: **Phase 1 - foundation.** The backend uses `com.gialong.relayforge` with behavior-free `identity`, `project`, `endpoint`, and `delivery` package anchors. ArchUnit tests enforce the approved dependency graph, cycle freedom, and cross-module public-API access. A strict required `relayforge.runtime=api|worker` property activates exactly one behavior-free composition marker. The persistence foundation uses Spring JDBC/Hikari, Flyway, Hibernate/JPA, and PostgreSQL Testcontainers against pinned PostgreSQL 17.10 in the `public` schema. V2 creates `owner_accounts`; its internal identity JPA entity has verified assigned-UUID, persistence-context, database-timestamp, dirty-checking, and optimistic-version behavior.

The next task is the bounded owner-bootstrap use case: add only the identity-owned persistence/password-hash ports and adapters required for race-safe idempotent bootstrap by canonical login. Do not add login authentication, sessions, HTTP, project data, or another table.

The high-level architecture is a modular monolith: one artifact/image starts in either `api` or `worker` mode, and PostgreSQL is the source of truth and Portfolio v1 work transport. Detailed contracts belong in their dedicated documents; this file intentionally does not restate them. The project requires JDK 25, but the current terminal defaults to JDK 21, so local Maven commands must select the installed JDK 25 until the environment is corrected.

Start work with [AGENTS.md](../AGENTS.md), then [CURRENT.md](../tasks/CURRENT.md), and use the concise [documentation index](README.md) to open only the relevant authority. `PROJECT_STATUS.md` is the project-wide progress ledger. Accepted ADRs in `docs/adr/` record material architectural choices and are not silently reversed.
