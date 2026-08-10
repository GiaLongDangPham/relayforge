# RelayForge Agent Context

Last updated: 2026-08-10

RelayForge is a portfolio learning project: an outbound webhook delivery platform that demonstrates reliable at-least-once dispatch under failure and concurrency.

Current phase: **Phase 1 - foundation.** The backend uses `com.gialong.relayforge` with `identity`, `project`, `endpoint`, and `delivery` capability packages. ArchUnit tests enforce the approved dependency graph, cycle freedom, and cross-module public-API access. A strict required `relayforge.runtime=api|worker` property activates exactly one composition marker. The persistence foundation uses Spring JDBC/Hikari, Flyway, Hibernate/JPA, and PostgreSQL Testcontainers against pinned PostgreSQL 17.10 in the `public` schema. V2 creates `owner_accounts`; its internal JPA mapping, race-safe bootstrap use case, and opt-in API-only startup adapter have verified first-start/restart idempotency, secret-redacted logs, persistence-context, optimistic-lock, and concurrent behavior.

The next recommended task is a bounded owner credential lookup and password-verification use case. Use JPA/Hibernate for the ordinary canonical-login read, keep BCrypt verification and unknown-login timing behavior explicit, and expose no password hash outside `identity`. Do not add Spring Security filters, sessions, HTTP, project data, or another table yet.

The high-level architecture is a modular monolith: one artifact/image starts in either `api` or `worker` mode, and PostgreSQL is the source of truth and Portfolio v1 work transport. Detailed contracts belong in their dedicated documents; this file intentionally does not restate them. The project requires JDK 25, but the current terminal defaults to JDK 21, so local Maven commands must select the installed JDK 25 until the environment is corrected.

Start work with [AGENTS.md](../AGENTS.md), then [CURRENT.md](../tasks/CURRENT.md), and use the concise [documentation index](README.md) to open only the relevant authority. `PROJECT_STATUS.md` is the project-wide progress ledger. Accepted ADRs in `docs/adr/` record material architectural choices and are not silently reversed.
