# Current Task

Status: Complete

## Goal

Complete Phase 2B receiver-aware backpressure without introducing a second
queue, Redis, broker, owner UI, or custom retry policy.

## Decisions

- V13 records only the bounded effective retry delay/source; PostgreSQL still
  computes due time.
- V14/V15 persist delivery-owned circuit state. A missing row remains
  semantically `CLOSED`, while a closed sub-threshold failure streak is valid.
- PostgreSQL owns fair normal/probe admission and all cooldown comparisons.
  A cooldown-expired endpoint gets one token-fenced `HALF_OPEN` probe.
- Conditional delivery finalization and post-attempt `UNKNOWN` recovery update
  the matching circuit in their existing short transactions.

## Out of scope

Circuit metrics and owner-visible safe state, versioned signing-secret
rotation, event-schema versions, custom retry policy, broker, Redis, endpoint
rate limit, or deployment change.

## Evidence required

- PostgreSQL Testcontainers proves a three-failure open circuit cannot consume
  capacity needed by a healthy endpoint.
- Concurrent workers prove exactly one cooldown-expired `HALF_OPEN` probe.
- Probe success closes/resets the circuit; expired probe recovery reopens it
  without leaving a fence.
- Architecture checks prove runtime composition uses only delivery public API.

## Completion evidence

- V15 corrects V14's closed-state constraint discovered by Testcontainers:
  closed circuits must retain streaks one and two before the third failure opens.
- Claim SQL joins durable circuit state, atomically fences a single probe, and
  treats a lost claim race as no claim rather than a worker failure.
- Finalization/recovery changes the circuit only after its conditional delivery
  mutation succeeded, preserving token/lease fencing and the no-DB-transaction
  during HTTP invariant.
- Final clean Maven verification passed 131/131 tests. The rebuilt local
  API/worker reached health, and the dashboard sign-in smoke had no console
  warnings or errors.

## Next action

Owner review/commit. The next implementation phase is versioned
signing-secret rotation; start with its contract/ADR rather than code.
