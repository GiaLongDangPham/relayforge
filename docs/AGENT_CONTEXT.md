# RelayForge Agent Context

Last updated: 2026-08-09

RelayForge is a portfolio learning project: an outbound webhook delivery platform that demonstrates reliable at-least-once dispatch under failure and concurrency.

Current phase: **Phase 1 - foundation.** The backend uses `com.gialong.relayforge` with behavior-free `identity`, `project`, `endpoint`, and `delivery` package anchors. ArchUnit tests enforce the approved dependency graph, cycle freedom, and cross-module public-API access. There is still no RelayForge business behavior, migration, persistence, controller, security, or worker implementation.

The next task is the explicit `relayforge.runtime=api|worker` startup contract with conditional configuration markers and Spring tests for valid, missing, invalid, and mutually exclusive modes. Do not add actual API or worker behavior in that slice.

The high-level architecture is a modular monolith: one artifact/image starts in either `api` or `worker` mode, and PostgreSQL is the source of truth and Portfolio v1 work transport. Detailed contracts belong in their dedicated documents; this file intentionally does not restate them. The project requires JDK 25, but the current terminal defaults to JDK 21, so local Maven commands must select the installed JDK 25 until the environment is corrected.

Start work with [AGENTS.md](../AGENTS.md), then [CURRENT.md](../tasks/CURRENT.md), and use the concise [documentation index](README.md) to open only the relevant authority. `PROJECT_STATUS.md` is the project-wide progress ledger. Accepted ADRs in `docs/adr/` record material architectural choices and are not silently reversed.
