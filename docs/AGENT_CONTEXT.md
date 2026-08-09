# RelayForge Agent Context

Last updated: 2026-08-09

RelayForge is a portfolio learning project: an outbound webhook delivery platform that demonstrates reliable at-least-once dispatch under failure and concurrency.

Current phase: **Phase 1 - foundation.** The backend uses `com.gialong.relayforge` with behavior-free `identity`, `project`, `endpoint`, and `delivery` package anchors. ArchUnit tests enforce the approved dependency graph, cycle freedom, and cross-module public-API access. A strict required `relayforge.runtime=api|worker` property now activates exactly one behavior-free composition marker and rejects missing, unsupported, or noncanonical values.

The next task is a PostgreSQL persistence test foundation: choose one migration tool, add JDBC/PostgreSQL/Testcontainers dependencies, and prove container connectivity plus migration execution without creating business tables.

The high-level architecture is a modular monolith: one artifact/image starts in either `api` or `worker` mode, and PostgreSQL is the source of truth and Portfolio v1 work transport. Detailed contracts belong in their dedicated documents; this file intentionally does not restate them. The project requires JDK 25, but the current terminal defaults to JDK 21, so local Maven commands must select the installed JDK 25 until the environment is corrected.

Start work with [AGENTS.md](../AGENTS.md), then [CURRENT.md](../tasks/CURRENT.md), and use the concise [documentation index](README.md) to open only the relevant authority. `PROJECT_STATUS.md` is the project-wide progress ledger. Accepted ADRs in `docs/adr/` record material architectural choices and are not silently reversed.
