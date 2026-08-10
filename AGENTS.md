# RelayForge Repository Working Rules

RelayForge is a learning project for an outbound webhook delivery platform. Treat the user as the project owner and learner, not as a requester for bulk code generation.

## Start and scope every task

1. Understand the requested outcome, then read `docs/AGENT_CONTEXT.md`, `tasks/CURRENT.md`, and run `git status --short --branch`.
2. Read only the authoritative documents relevant to the task, using `docs/README.md` as the navigation map.
3. State one small, coherent outcome and what is out of scope. Do not implement an entire phase or large feature unless the user explicitly asks.
4. Inspect only the code directly involved; do not scan or refactor unrelated areas.

## Learning mode

Before significant implementation, explain in RelayForge terms what will be built and why, where it belongs in the accepted architecture, the important Java/Spring/PostgreSQL concepts, and the invariant, failure mode, trade-off, and test evidence.

For a meaningful core behavior, when the user has not asked for a complete implementation, offer a small checkpoint or invite them to implement the central part first. Do not hide important concepts behind generated boilerplate.

When reviewing user-written code, first identify what works, explain mistakes and their impact, and propose a focused correction. Do not replace the implementation wholesale unless the user asks or agrees after review. Increase difficulty gradually and connect each slice to the previous one.

## Engineering guardrails

- Read the relevant authoritative documentation before non-trivial design or implementation work.
- Do not silently change architecture, invariants, module boundaries, runtime behavior, or an accepted ADR. Surface the impact and request a decision when a change is material.
- Do not add dependencies, infrastructure, or a new service/store without a documented need, compatible source-of-truth guidance, and explicit user approval when it expands scope.
- Avoid unrelated cleanup, formatting, renames, and refactors. A change may touch many files when one cohesive outcome requires it, but it must not add unrelated behavior.
- Do not replace a sound persistence approach merely to demonstrate another abstraction. Introduce Spring Data repositories, direct JPA, or JDBC/native SQL when the active use case makes that choice useful; defer migrations between them unless they solve a concrete problem.
- A production method with zero current callers is not automatically dead code when it supports a clearly anticipated near-term use case. Keep it only when its intent is understood and bounded; do not add speculative APIs without such a use case.
- Treat pre-existing or untracked changes as user-owned. Do not overwrite, stage, or reformat them unless asked.
- After implementation, run the narrowest tests or build checks that prove the changed behavior. Do not run the full suite by default; broaden verification only when shared infrastructure, cross-module behavior, a milestone, or regression risk warrants it. Never claim a check passed unless it actually passed.
- Prefer a small number of high-value tests around core logic, invariants, database behavior, concurrency, and failure handling. Avoid duplicate coverage whose only benefit is increasing the test count, and do not create production methods solely for test convenience.

## Documentation and memory

- `docs/AGENT_CONTEXT.md` is concise orientation and navigation only; it is not a specification.
- `tasks/CURRENT.md` records only the active unit of work. Update it when that unit is started, materially refined, or completed.
- `PROJECT_STATUS.md` records project-wide phase, completed slices, durable decisions, evidence, limitations, and the next recommended slice. Update it after a completed slice when durable progress changed.
- ADRs record an architectural decision, alternatives, rationale, and consequences. Accepted ADRs are not silently reversed or merged into general documentation.
- Do not create a new documentation file unless the information has a clearly different responsibility from all existing documents. Prefer updating an existing authoritative document and linking to it rather than duplicating it.

## Source-of-truth policy

For a topic, read the highest relevant source first. This order defines authority and where a correction belongs:

1. `docs/REQUIREMENTS.md` - product scope, rules, and acceptance criteria.
2. Accepted files in `docs/adr/` - material architectural decisions, rationale, alternatives, and consequences.
3. `docs/ARCHITECTURE_BOUNDARIES.md` - module ownership, dependency direction, runtime composition, and transaction ownership.
4. `docs/DELIVERY_MODEL.md`, then `docs/DELIVERY_RUNTIME_DEFAULTS.md` - delivery invariants/failure behavior, then concrete runtime values.
5. `docs/DATABASE_MODEL_PART1.md` and `docs/DATABASE_MODEL_PART2.md` - conceptual persistence and database responsibilities.
6. `docs/API_CONTRACT.md` - HTTP behavior and DTO boundaries.
7. `docs/SECURITY_BASELINE.md` - authentication, authorization, secret handling, SSRF, and redaction.
8. `PROJECT_STATUS.md` - progress ledger and current-phase record; it does not redefine accepted behavior.
9. `docs/PHASE_*_HANDOFF.md` and other summaries - review/navigation aids, not specifications.

This order does **not** authorize silently resolving a conflict. If two authoritative sources disagree, stop implementation; cite the conflicting statements, explain the behavioral or architectural impact, and ask the user which direction to take. An unaccepted ADR draft is not authoritative.

## Completion report

Report the outcome in plain language, including files changed and each file's purpose, what was implemented or decided, tests/checks run and their real results, remaining issues or decisions, and one small proposed next step when appropriate.
