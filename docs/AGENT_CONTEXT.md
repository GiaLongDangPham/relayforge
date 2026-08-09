# RelayForge Agent Context

Last updated: 2026-08-09

RelayForge is a portfolio learning project: an outbound webhook delivery platform that demonstrates reliable at-least-once dispatch under failure and concurrency.

Current phase: **Phase 0 is ready for owner review.** The codebase has Spring Boot and React/Vite skeletons only; no RelayForge product behavior, migrations, or product tests exist. The next product task is owner review of [Phase 0 Handoff](PHASE_0_HANDOFF.md). After approval, Phase 1 starts with capability packages and ArchUnit boundary tests only.

The high-level architecture is a modular monolith: one artifact/image starts in either `api` or `worker` mode, and PostgreSQL is the source of truth and Portfolio v1 work transport. Detailed contracts belong in their dedicated documents; this file intentionally does not restate them.

Start work with [AGENTS.md](../AGENTS.md), then [CURRENT.md](../tasks/CURRENT.md), and use the concise [documentation index](README.md) to open only the relevant authority. `PROJECT_STATUS.md` is the project-wide progress ledger. Accepted ADRs in `docs/adr/` record material architectural choices and are not silently reversed.
