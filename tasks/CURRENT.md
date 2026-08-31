# Current Task

Status: Complete

## Goal

Capture repeatable PostgreSQL `EXPLAIN (ANALYZE, BUFFERS)` evidence for the
actual endpoint-fair claim candidate query, then decide whether the existing
V8 claim indexes need a change.

## Decisions

- The query plan must be generated from the same SQL template used by
  `JdbcDeliveryStore`, not a manually copied approximation.
- The fixture uses two enabled endpoints with deep due backlogs and an
  eight-row claim capacity. It proves the plan can find eight fair candidates;
  it does not model production history volume or receiver latency.
- A plan choice is interpreted in fixture context. A sequential scan on a
  small, largely eligible table is not by itself an index defect.

## Out of scope

No runtime query behavior change, index or migration change, forced planner
setting, data-volume benchmark, load test, pool/permit tuning, broker/Redis,
or unrelated refactoring.

## Evidence required

- A PostgreSQL integration test executes the live fair SQL under
  `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)` and verifies eight candidates are
  planned/executed for the representative fixture.
- The recorded summary identifies actual rows, execution time, shared blocks,
  and scan/index nodes without treating one local result as an SLA.
- Local dashboard smoke remains clean after code changes.

## Completion evidence

- Extracted the package-private fair SQL template so production claim code and
  query-plan evidence execute the identical statement.
- `FairClaimQueryPlanIntegrationTests` used PostgreSQL 17.10 Testcontainers,
  two enabled endpoints, 128 due deliveries, and capacity 8. The plan returned
  eight rows in 2.703 ms (planning 0.464 ms), with root shared-hit blocks
  3,987; it used the existing V8 pending and claimed indexes.
- The narrow regression command ran the plan fixture and
  `DeliveryClaimIntegrationTests`: 9 tests, 0 failures, 0 errors.
- Reloading `http://localhost:5173/` rendered the local sign-in page with no
  browser-console errors. No service rebuild was required because this slice
  changes the claim SQL extraction and test source only, not runtime behavior.
- No V13 index migration or performance/tuning claim is justified by this
  small warm local fixture.

## Next action

Begin Phase 2B Slice 1: define the bounded `Retry-After` contract before
changing retry scheduling code.
