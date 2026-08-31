# Current Task

Status: Complete

## Goal

Accept the bounded `Retry-After` scheduling contract for Phase 2B before
changing the worker's HTTP handling, retry persistence, or circuit-breaker
state.

## Decisions

- Only retryable HTTP `429` and `503` responses may contribute a receiver
  retry hint. Other retryable outcomes retain equal-jitter backoff alone.
- Accept only one `Retry-After` `delay-seconds` value: non-negative decimal
  seconds after HTTP optional-whitespace trimming. HTTP-date and ambiguous,
  repeated, malformed, or negative values fall back to normal backoff.
- The receiver hint is capped at 300 seconds. The selected retry delay is the
  greater of the normal equal-jitter delay and the bounded hint, so a receiver
  cannot make RelayForge retry sooner or postpone it without bound.
- PostgreSQL remains the clock authority. The worker will eventually provide a
  selected duration and source; persistence computes the absolute due-time.
- Future owner history may retain only the effective selected delay and its
  source (`BACKOFF` or `RETRY_AFTER`), never the raw response header.

## Out of scope

No HTTP parser/adapter, worker retry-code change, schema migration, history
field, circuit breaker, metric, retry-policy customization, broker, Redis, or
deployment change.

## Evidence required

- An accepted ADR, delivery model, and runtime defaults state the same
  eligibility, syntax, cap, selection, time-authority, and fifth-attempt rule.
- Status/context/index documentation identifies the next isolated slice.

## Completion evidence

- ADR-008 defines eligible statuses, one-field delta-seconds syntax, the
  300-second cap, `max(equal jitter, hint)` selection, PostgreSQL time
  authority, safe future audit data, and the unchanged fifth-attempt boundary.
- The delivery model and runtime defaults align on that same contract.
- The documentation index, agent context, and project status now identify
  Slice 2 as response-header capture and pure parsing only.
- This is documentation-only work: no runtime source changed, so no Maven,
  Docker, or browser acceptance run was needed.

## Next action

Begin Phase 2B Slice 2: implement only response-header capture and pure
`Retry-After` parsing, with focused unit tests for valid, invalid, repeated,
date, overflow, and cap behavior.
