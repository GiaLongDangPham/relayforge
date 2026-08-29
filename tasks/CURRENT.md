# Current Task

Status: Completed

## Goal

Complete Group 20: close RelayForge as a portfolio project with a concise
repository entry point, evidence-based CV material, an interview playbook, and
a short demo script. The work must represent accepted architecture and measured
evidence accurately, without adding a feature merely for presentation.

## Decisions

- The root README is an entry point and evidence map; detailed contracts stay
  in their existing authoritative documents.
- CV bullets may state only demonstrated behavior, measured local results, or
  accepted implementation decisions. They must not claim production SLA,
  capacity, exactly-once delivery, HA, or a managed database.
- Interview answers explain why the project chose PostgreSQL, modular monolith,
  local observability, and bounded worker processing rather than collecting
  technology keywords.
- The demo flow uses the already-verified local/public paths and never reveals
  raw API keys, signing secrets, environment values, or private IP details.

## Out of scope

New product features, dependencies, runtime tuning, production deployment,
production load, credential rotation, managed observability, and repository
hosting/publication decisions outside the existing codebase.

## Evidence required

- A concise README explains the product, architecture, local run, public demo,
  evidence, and honest limits.
- A reusable guide contains CV bullets, design-decision interview questions,
  and a short safe demo sequence tied to repository evidence.
- Project status records Group 20 completion and points to the next action as
  owner review/commit rather than speculative feature development.

## Completion evidence

- `README.md` is the recruiter/reviewer entry point: product summary,
  architecture, measured local evidence, links, and explicit limitations.
- `docs/PORTFOLIO_PLAYBOOK.md` supplies bounded CV claims, design-decision
  interview answers, and a safe seven-minute demo sequence tied to evidence.
- This group changed documentation only: it added no runtime behavior,
  dependency, deployment, performance claim, or production operation.

## Next action

Owner review, then explicitly choose whether to commit and push the Group 20
documentation closeout.
