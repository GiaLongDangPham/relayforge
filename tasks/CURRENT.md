# Current Task

Status: Complete

## Goal

Reorganize public `delivery` contracts by workflow so the package tree makes
publish, processing, history, replay, and operations responsibilities visible.

## Decisions

- Group public contracts under `delivery.api.publish`, `.processing`,
  `.history`, `.replay`, and `.operations`, never under a generic
  `records`/`interfaces` folder.
- Preserve public types, visibility, signatures, Spring wiring, SQL,
  migration history, transaction behavior, and module boundaries.
- Keep `delivery.application` and the persistence adapter in their current
  packages; this slice does not combine package cleanup with logic redesign.

## Out of scope

Moving application/persistence implementation classes, splitting
`JdbcDeliveryStore`, new abstractions, runtime behavior, database migrations,
or signing-secret rotation.

## Evidence required

- Compilation catches every package/import mismatch.
- ArchUnit confirms runtime composition still accesses only `delivery.api..`.
- Narrow delivery tests and local Compose/browser smoke prove no behavior
  changed by the structural refactor.

## Completion evidence

- Moved 39 public delivery contracts into five workflow packages and updated
  all consumers mechanically; no method signature or runtime behavior changed.
- `mvn clean test` passed 131/131, local API and worker reached health, and
  the dashboard sign-in smoke had no browser console warnings or errors.

## Next action

Owner review/commit. The next implementation phase remains signing-secret
rotation, beginning with a focused contract/ADR slice rather than code.
