# ADR-008: Bounded Receiver `Retry-After` Scheduling

- Status: Accepted
- Date: 2026-08-31
- Decision owners: RelayForge project
- Supersedes: None; extends the retry scheduling in ADR-002

## Context

RelayForge already schedules retryable outcomes with bounded equal-jitter
backoff. A receiver that is temporarily overloaded can return HTTP `429` or
`503` with `Retry-After` to communicate that an earlier retry is unlikely to
help. Ignoring every such hint can create unnecessary receiver pressure;
accepting it without bounds lets a receiver control RelayForge's queue delay
or makes retry correctness depend on receiver and worker clock agreement.

The existing retry cap is 300 seconds, PostgreSQL owns persisted due-time, and
the fifth started attempt must still become `EXHAUSTED`. This decision must
improve receiver interoperability without weakening those constraints.

## Decision

For a final retryable HTTP response, RelayForge may use a receiver retry hint
only under all of these conditions:

1. the status is HTTP `429` or `503`;
2. exactly one `Retry-After` field value is present;
3. after HTTP optional-whitespace trimming, its value is a non-negative ASCII
   decimal `delay-seconds` value.

HTTP-date form is deliberately unsupported. Missing, repeated, malformed,
signed, decimal, or otherwise invalid values are ignored and normal
equal-jitter retry scheduling applies. An excessively large decimal must be
handled without numeric overflow and is clamped to 300 seconds.

For attempts one through four, the effective retry delay is:

```text
max(normal equal-jitter backoff, bounded accepted Retry-After delay)
```

Therefore the header cannot make RelayForge retry sooner than its normal
backoff. `Retry-After: 0` is valid but normally has no scheduling effect under
this maximum rule. A fifth retryable attempt remains `EXHAUSTED`; it does not
schedule a sixth attempt regardless of the header.

The worker will pass the selected duration and a scheduling source
(`BACKOFF` or `RETRY_AFTER`) to persistence. PostgreSQL computes the absolute
due-time from its own current time. Future history may expose the effective
duration and source but never the raw receiver header value.

## Rationale and trade-offs

- Limiting header use to `429` and `503` captures explicit overload signals
  without allowing every transient `5xx` response to dictate scheduling.
- Delta seconds are self-contained. HTTP-date would require a receiver clock
  interpretation and introduces a clock-skew question that adds little value
  to this portfolio scope.
- Taking the maximum respects the receiver's requested quiet period while
  preserving RelayForge's own anti-storm backoff. It is conservative: a
  receiver cannot ask for a faster recovery probe.
- The existing 300-second cap makes a receiver hint bounded by the same
  portfolio retry horizon as local backoff. It can defer work, but cannot
  indefinitely retain it.
- Rejecting repeated values avoids selecting an arbitrary value from an
  ambiguous response. Clamping one valid oversized value is safer than
  overflowing or treating a receiver's intent as an unbounded wait.

## Alternatives considered

### Ignore `Retry-After`

This keeps scheduling simpler but repeatedly retries a receiver that has
explicitly reported temporary overload. It is rejected because the bounded
delta-seconds form addresses that concrete interoperability problem without
new infrastructure.

### Honor both delta seconds and HTTP-date

This is broader HTTP compatibility, but date interpretation brings receiver
clock semantics into a system intentionally based on PostgreSQL time. It is
deferred until a real receiver requires it.

### Let a valid header replace normal backoff

This could accelerate retry after `Retry-After: 0` or a small value and increase
receiver pressure during a failure wave. It is rejected; the maximum rule
retains the local exponential-backoff guardrail.

### Store the raw header in attempt history

Raw diagnostic storage has little owner value, complicates safe rendering, and
can expose arbitrary receiver-controlled data. The effective selected delay and
source are the auditable facts RelayForge needs.

## Consequences and verification gate

Slice 1 changes documentation only. Subsequent slices must prove:

1. response capture distinguishes a single header from missing or repeated
   fields;
2. parser tests cover valid seconds, OWS, zero, malformed values, HTTP-date,
   overflow, and cap behavior;
3. only `429` and `503` can select the hint source;
4. selected delay is the documented maximum of deterministic jitter and the
   bounded hint;
5. PostgreSQL computes the persisted due-time, including after worker restart;
6. invalid or ineligible headers preserve the existing equal-jitter behavior;
7. the fifth attempt remains `EXHAUSTED`; and
8. any history/metric addition avoids raw header values and high-cardinality
   endpoint identifiers.

No circuit breaker, custom retry policy, broker, Redis, migration, or runtime
behavior belongs to this ADR by itself.

## References

- [ADR-002: PostgreSQL-Backed Delivery Jobs with Leases and Claim Tokens](0002-postgresql-backed-delivery-jobs.md)
- [RelayForge Delivery Model](../DELIVERY_MODEL.md)
- [RelayForge Delivery Runtime Defaults](../DELIVERY_RUNTIME_DEFAULTS.md)
