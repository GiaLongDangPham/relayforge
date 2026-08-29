# Current Task

Status: In progress

## Goal

Correct the endpoint-configuration request used by the dashboard so a live
owner can change an existing endpoint URL while preserving optimistic-version
protection. This unblocks the requested production pause/resume demo data.

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

New product features, dependencies, runtime tuning, credential rotation,
managed observability, and unrelated portfolio documentation changes.

## Evidence required

- A replace-endpoint request contains only the API contract fields: `name`,
  `destinationUrl`, `eventTypes`, and current `version`.
- Frontend checks pass and a local browser confirms the endpoint editor is
  still reachable.

## Completion evidence

Pending.

## Next action

Run the narrow frontend check, review the small contract fix, then deploy the
new immutable frontend image before resuming the live pause/resume scenario.
