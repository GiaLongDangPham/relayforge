# Current Task

Status: Complete

## Goal

Complete Phase 3 Slice 3.2--3.4: persist the accepted bounded per-endpoint
retry floor, expose it through the owner API and dashboard, and apply it during
retry finalization and `UNKNOWN` recovery.

## Decisions

- ADR-012 defines an optional endpoint-owned whole-second minimum retry delay
  from 5 through 300; it can only lengthen the current schedule.
- Retry selection for attempts 1--4 and recovered `UNKNOWN` will use the
  bounded maximum of equal jitter, the floor, and eligible `Retry-After`.
  Attempt five remains `EXHAUSTED`; PostgreSQL owns `due_at`.
- Policy is read through a row-locked public endpoint snapshot during retry
  scheduling. Later endpoint changes never rewrite an existing due-time.

## Out of scope

Project-level policy, retry-budget or circuit-breaker changes, Redis,
billing/plans, SSE, ordering, RBAC, Kubernetes, and unbounded/custom
user-supplied retry logic.

## Evidence required

- Endpoint owner writes validate the optional 5--300 second value and preserve
  the existing optimistic-version behavior.
- Finalization and recovery persist the selected audit source while PostgreSQL
  remains authoritative for `due_at`; an existing due-time never changes after
  a later policy update.
- Dashboard editing preserves an absent policy and displays the configured
  value.

## Completion evidence

- V17 persists the nullable endpoint floor and permits `ENDPOINT_POLICY` in
  retry-schedule audit rows.
- Focused policy, endpoint API, owner HTTP, and PostgreSQL finalization/recovery
  tests passed 13/13.
- Frontend lint and production build passed.

## Next action

Owner review/commit the completed Slice 3 work. The next bounded Phase 3 slice
is evidence-gated SSE only if polling delay or load justifies it.
