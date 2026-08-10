# RelayForge Agent Context

Last updated: 2026-08-10

RelayForge is a portfolio learning project: an outbound webhook delivery platform that demonstrates reliable at-least-once dispatch under failure and concurrency.

Current phase: **Phase 1 - foundation.** The backend uses `com.gialong.relayforge` with `identity`, `project`, `endpoint`, and `delivery` capability packages. ArchUnit tests enforce the approved dependency graph, cycle freedom, and cross-module public-API access. A strict required `relayforge.runtime=api|worker` selects one process; the packaged launcher makes worker mode non-web before context creation. The persistence foundation uses Spring JDBC/Hikari, Flyway, Hibernate/JPA, and PostgreSQL Testcontainers against pinned PostgreSQL 17.10 in the `public` schema. V2 creates `owner_accounts`; V3 creates the technical Spring Session tables. Identity has race-safe JDBC bootstrap and ordinary JPA credential lookup, with BCrypt work outside short transactions, dummy work for unknown users, generic invalid outcomes, and no public hash exposure.

The completed owner browser-authentication slice adds API-only Spring Security, JSON CSRF/login/me/logout endpoints, a JDBC-backed `RF_SESSION` with rotation and invalidation, credentialed origin allowlisting, and a bounded local failed-login limiter. The verifier remains the identity-owned credential decision point and `VerifiedOwner` remains the sole hash-free principal. PostgreSQL integration tests prove CSRF, session rotation/restart/logout, CORS, rate limiting, and non-web worker exclusion. The next recommended slice is the `project` capability and owner-scoped resource access; publisher authentication and delivery behavior remain deferred.

The high-level architecture is a modular monolith: one artifact/image starts in either `api` or `worker` mode, and PostgreSQL is the source of truth and Portfolio v1 work transport. Detailed contracts belong in their dedicated documents; this file intentionally does not restate them. The project requires JDK 25, but the current terminal defaults to JDK 21, so local Maven commands must select the installed JDK 25 until the environment is corrected.

Start work with [AGENTS.md](../AGENTS.md), then [CURRENT.md](../tasks/CURRENT.md), and use the concise [documentation index](README.md) to open only the relevant authority. `PROJECT_STATUS.md` is the project-wide progress ledger. Accepted ADRs in `docs/adr/` record material architectural choices and are not silently reversed.
