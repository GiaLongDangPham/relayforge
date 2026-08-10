# RelayForge Agent Context

Last updated: 2026-08-10

RelayForge is a portfolio learning project: an outbound webhook delivery platform that demonstrates reliable at-least-once dispatch under failure and concurrency.

Current phase: **Phase 1 - foundation.** The backend uses `com.gialong.relayforge` with `identity`, `project`, `endpoint`, and `delivery` capability packages. ArchUnit tests enforce the approved dependency graph, cycle freedom, and cross-module public-API access. A strict required `relayforge.runtime=api|worker` property activates exactly one composition marker. The persistence foundation uses Spring JDBC/Hikari, Flyway, Hibernate/JPA, and PostgreSQL Testcontainers against pinned PostgreSQL 17.10 in the `public` schema. V2 creates `owner_accounts`; identity now has race-safe JDBC bootstrap and ordinary JPA credential lookup, with BCrypt work outside short transactions, dummy work for unknown users, generic invalid outcomes, and no public hash exposure.

The identity verifier now has an API-mode Spring Security authentication adapter in `runtime.security`: it translates username/password authentication input into the existing verifier, returns a principal containing only owner ID and canonical login, and maps invalid verification to generic bad credentials. It does not add a filter chain, sessions, HTTP endpoints, CSRF, rate limiting, project data, or another table. The next recommended slice is a deliberately scoped API security configuration decision, beginning with how the future filter chain stays deny-by-default without prematurely enabling sessions or dashboard routes.

The high-level architecture is a modular monolith: one artifact/image starts in either `api` or `worker` mode, and PostgreSQL is the source of truth and Portfolio v1 work transport. Detailed contracts belong in their dedicated documents; this file intentionally does not restate them. The project requires JDK 25, but the current terminal defaults to JDK 21, so local Maven commands must select the installed JDK 25 until the environment is corrected.

Start work with [AGENTS.md](../AGENTS.md), then [CURRENT.md](../tasks/CURRENT.md), and use the concise [documentation index](README.md) to open only the relevant authority. `PROJECT_STATUS.md` is the project-wide progress ledger. Accepted ADRs in `docs/adr/` record material architectural choices and are not silently reversed.
