# Current Task

Status: Complete

## Goal

Record a repeatable noisy-neighbor baseline for the current global-FIFO claim
selection before changing claim SQL or indexes.

## Decisions

- The baseline intentionally preserves the existing `ORDER BY due_at, id`
  global-FIFO behavior; it is evidence for a later comparison, not a defect
  being fixed in this slice.
- Fixture: endpoint A has 32 earlier due deliveries; endpoint B has two later
  due deliveries; every claim opportunity has eight free permits.
- The test drives normal attempt start/finalization with synthetic 204 evidence
  so each completed A wave returns permits before the next claim.
- Claim-wave delay is recorded, rather than an elapsed-time SLA: B first
  becomes eligible for a claim only after four full A waves under FIFO.

## Out of scope

Fair-selection SQL, migration/index changes, real slow-receiver load testing,
`EXPLAIN` comparison, `Retry-After`, circuit breaker, secret rotation, event
schema versioning, broker/Redis adoption, and unrelated refactoring.

## Evidence required

- A PostgreSQL Testcontainers fixture proves the first four eight-row waves
  contain only A, and B first appears in wave five.
- The fixture uses normal claim, attempt-start, and finalization boundaries;
  it does not mutate delivery state to simulate completion.
- The recorded baseline distinguishes claim-wave delay from an elapsed-time or
  production-capacity statement.

## Completion evidence

- Added `recordsTheGlobalFifoNoisyNeighborBaselineBeforeFairDispatchChanges` to
  `DeliveryClaimIntegrationTests`.
- On PostgreSQL 17.10 Testcontainers, A's 32 earlier due deliveries consumed
  four full eight-claim waves; B's two deliveries first appeared in wave five.
- The test uses normal claim, attempt-start, and conditional-finalization
  boundaries with synthetic HTTP 204 observations; it does not directly mutate
  delivery state to fake completion.
- `mvnw.cmd -Dtest=DeliveryClaimIntegrationTests test` with JDK 25 passed:
  4 tests, 0 failures, 0 errors.
- This is a deterministic claim-order baseline, not a timing/capacity claim.

## Next action

Implement endpoint-fair candidate selection under ADR-007, preserving the
same fixture for a direct before/after claim-distribution comparison.
