# ADR-012: Bounded Endpoint Retry Floor

- Status: Accepted
- Date: 2026-09-01
- Decision owners: RelayForge project
- Supersedes: None; extends ADR-008 and ADR-009

## Context

RelayForge currently applies one fixed equal-jitter schedule to every
retryable or recovered `UNKNOWN` delivery: bases of 5, 20, 80, and 300 seconds
for attempts one through four. ADR-008 permits a bounded receiver
`Retry-After` delay but only for the single delivery that received a qualifying
response. ADR-009 separately protects a failing endpoint's whole backlog.

Some receivers need a longer quiet period than the portfolio default, but a
general customer-defined retry algorithm would make delivery timing,
at-least-once evidence, and attempt history difficult to reason about. The
owner has approved the smallest receiver-specific customization: an endpoint
may require a longer minimum wait, while RelayForge retains its retry budget,
backoff curve, PostgreSQL time authority, and receiver-hint guardrails.

## Decision

### Configuration scope and bounds

An endpoint may have one optional owner-managed `minimumRetryDelay` expressed
as whole seconds. Its inclusive range is 5 through 300 seconds; absence means
no endpoint floor and preserves the existing equal-jitter schedule. The
configuration belongs to the `endpoint` module and participates in that
endpoint's existing optimistic version. It is neither a project policy nor a
customer plan.

For retryable completed attempts one through four, and for recovered `UNKNOWN`
attempts one through four, RelayForge will select:

```text
min(300 seconds,
    max(normal equal-jitter backoff,
        endpoint minimumRetryDelay when configured,
        accepted bounded Retry-After delay when eligible))
```

All inputs are bounded at 300 seconds, so the outer cap is a defensive
invariant rather than permission for a larger value. The fifth started attempt
remains `EXHAUSTED` and schedules no sixth attempt.

`Retry-After` keeps the accepted ADR-008 rules: only one valid delta-seconds
value on a final HTTP `429` or `503` is eligible; it cannot accelerate work;
it never changes the endpoint configuration. Circuit-breaker classification,
threshold, cooldown, and probe behavior remain independent of this floor.

### Effective-time and audit semantics

The worker reads the currently committed endpoint floor through a public
endpoint contract in the same short delivery transaction that selects and
persists a retry. This applies to both observed finalization and `UNKNOWN`
recovery. A concurrent owner configuration mutation and retry selection must
serialize on the endpoint configuration row, so the selected value is one
committed endpoint version. A due-time already persisted on a `PENDING`
delivery is never recalculated or rewritten after a later configuration change.

Attempt history continues to retain only the effective delay and a bounded
source, never raw receiver headers or owner metadata. The next implementation
must add `ENDPOINT_POLICY` to the retry-schedule source when the endpoint floor
is greater than both ordinary backoff and the accepted hint. Source selection
is deterministic:

1. `BACKOFF` when ordinary backoff is at least both other inputs;
2. `RETRY_AFTER` when the eligible hint is greater than ordinary backoff and
   at least the endpoint floor; otherwise
3. `ENDPOINT_POLICY` when the endpoint floor is greater than ordinary backoff
   and greater than the accepted hint.

This makes a tie between a receiver hint and endpoint floor auditable as
`RETRY_AFTER`, while an equal ordinary backoff remains `BACKOFF`.

### Delivery and module boundaries

The endpoint module owns validation, persistence, owner authorization, and
optimistic update of the floor. The delivery module owns retry selection,
attempt-state transitions, and PostgreSQL `due_at`. It may obtain only a narrow
public endpoint retry-policy snapshot inside finalization/recovery; it must not
read endpoint repositories or entities. No outbound HTTP occurs in that
transaction.

## Consequences

The first implementation can protect a receiver with an explicit longer quiet
period without creating an unbounded parameter set or adding Redis, a broker,
or a scheduler. The trade-off is intentional: owners cannot make retries more
aggressive than the established defaults, change the five-attempt budget,
choose a new multiplier/cap, configure a circuit breaker, or update an already
persisted due-time.

Slice 3.1 changed the contract only. Slices 3.2--3.4 implement it with V17, the
owner endpoint API/dashboard, `ENDPOINT_POLICY` audit, and finalization/recovery
integration; they do not add a new scheduler, dependency, or runtime setting.

## Verification gate

Later slices are accepted only after they prove:

1. endpoint owner mutations validate 5--300 whole seconds, preserve absent
   default behavior, and use the existing optimistic-version conflict rule;
2. finalization and recovery select the documented maximum without exceeding
   300 seconds, while a fifth attempt remains `EXHAUSTED`;
3. valid `Retry-After`, equal values, and each strict winner receive the
   documented bounded audit source;
4. concurrent endpoint policy updates and retry selection use one committed
   configuration version without stale delivery-token corruption;
5. a later configuration change leaves an existing persisted `due_at`
   unchanged;
6. PostgreSQL remains the absolute due-time authority, circuit behavior remains
   unchanged, and no transaction covers outbound HTTP; and
7. API/worker runtime separation, history redaction, and existing retry,
   circuit, recovery, and endpoint-configuration regressions remain green.

## References

- [Requirements](../REQUIREMENTS.md)
- [Delivery Model](../DELIVERY_MODEL.md)
- [Delivery Runtime Defaults](../DELIVERY_RUNTIME_DEFAULTS.md)
- [Architecture Boundaries](../ARCHITECTURE_BOUNDARIES.md)
- [ADR-008](0008-bounded-retry-after-scheduling.md)
- [ADR-009](0009-postgresql-endpoint-circuit-breaker.md)
