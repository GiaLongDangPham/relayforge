# RelayForge Agent Operating Guide

This repository builds RelayForge, an outbound webhook delivery platform. Work in one small, verifiable vertical slice at a time. Do not infer approval to implement unrelated roadmap items.

## Fast start (default context budget)

1. Read `docs/AGENT_CONTEXT.md` and run `git status --short --branch`.
2. State one outcome for this turn and what is out of scope. Prefer changing 1-3 files; explain before exceeding five.
3. Read only the document routed below and the code directly involved in the slice. Do not scan the whole repository or repeat prior analysis.
4. Before a production-code change, state the invariant, the failure it prevents, the trade-off, and what the test will prove.
5. Make the smallest coherent change, run the narrowest relevant verification, review the diff, then update the compact context and `PROJECT_STATUS.md` if a durable fact changed.

Read the full `PROJECT_STATUS.md` only when starting a new phase, resolving a contradiction, making/revising an architectural decision, or preparing a handoff. Otherwise use its `Current position`, `Approved decisions`, and `Next recommended slice` sections as needed.

## Documentation router

| Need | Read first |
| --- | --- |
| Product scope, actors, acceptance criteria | `docs/REQUIREMENTS.md` |
| Retries, leases, idempotency, delivery states | `docs/DELIVERY_MODEL.md` |
| Module ownership, transactions, API/worker modes | `docs/ARCHITECTURE_BOUNDARIES.md` |
| A decision and its alternatives | relevant file in `docs/adr/` |
| Current milestone, completed work, verification history | `PROJECT_STATUS.md` |

## Non-negotiable constraints

- Portfolio v1 is an outbound-only modular monolith: one artifact/image, one explicit `api` or `worker` process mode, PostgreSQL as the coordination boundary.
- Delivery is at-least-once, not exactly-once. Never hold a database transaction open during outbound HTTP.
- Publish idempotency is required per project. Use short lease-based claims with opaque claim tokens; PostgreSQL time decides due/expiry correctness.
- Do not promise delivery ordering. No broker, Redis, microservices, Kubernetes, or broad shared/common module without an ADR backed by evidence.
- Preserve module direction: `endpoint -> project`; `delivery -> project, endpoint`; no reverse dependency and no cross-module repository/entity access.
- Treat pre-existing/untracked changes as user-owned. Do not overwrite, stage, or reformat them unless the user asks.

## Memory and token discipline

- `docs/AGENT_CONTEXT.md` is the compact working memory. Keep it under roughly 1,200 words and update it only for active phase, active slice, changed invariants, or changed navigation.
- `PROJECT_STATUS.md` is the durable audit ledger, not a prompt dump. Add concise facts, changed files, real command results, decisions, limitations, and one next slice. Do not copy entire designs into it.
- Put stable rationale in an ADR; put detailed behavior and test evidence in the focused design document; link instead of duplicating.
- Use targeted commands (`rg`, exact paths, narrow tests). Summarize inspected evidence rather than pasting large files into follow-up prompts.
- If context is missing or conflicts, stop guessing: read the authoritative routed source and record the resolution where future agents can find it.

## Completion checklist

- [ ] Scope stayed within one primary outcome.
- [ ] Critical behavior has a focused test or an explicit reason it cannot yet be tested.
- [ ] Reported verification actually passed.
- [ ] No unrelated diff or user-owned change was touched.
- [ ] Durable memory was updated when needed.

