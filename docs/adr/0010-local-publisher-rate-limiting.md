# ADR-010: Bounded Local Publisher Rate Limiting

- Status: Accepted
- Date: 2026-08-31
- Decision owners: RelayForge project
- Supersedes: None

## Context

The publisher event endpoint accepts authenticated requests that can create an
immutable event and a fan-out delivery snapshot in one PostgreSQL transaction.
Payload size, API-key revocation, worker permits, and backlog metrics already
bound adjacent risks, but they do not prevent one valid publisher credential
from creating a burst of JSON parsing and database work.

The public demo currently runs one API process. Its measured local baseline
accepted 1,275 unique publishes through the real API, PostgreSQL, and worker
path with no HTTP errors; that is a comparison point, not a production capacity
claim. RelayForge needs a bounded admission control for that one-process setup
without treating a cache as a durable quota, changing idempotency correctness,
or adding Redis.

## Decision

RelayForge will apply a local token-bucket rate limit to publisher event
acceptance.

### Scope and placement

The limiter applies only to:

```text
POST /api/v1/projects/{projectId}/events
```

It runs in API mode after a project API key is authenticated and its
`VerifiedPublisherProject.projectId` matches the path project, but before the
request body is read, parsed, or passed to `EventPublisher.publish(...)`. An
invalid/revoked key remains the existing generic `401`; a valid key for another
path project remains `403`; neither consumes a publisher bucket. Worker mode,
owner APIs, replay, and health/metrics endpoints are outside this contract.

### Bucket identity, algorithm, and defaults

One API process owns one bucket per authenticated `projectId`, shared by all of
that project's active API keys. Token consumption is atomic per project. The
initial fixed values are:

| Setting | Value |
| --- | ---: |
| Bucket capacity | 60 requests |
| Refill rate | 30 requests per second |
| Request cost | 1 token |
| Idle-entry expiry | 15 minutes |
| Maximum retained project buckets | 10,000 |

Refill uses monotonic process time, never a client timestamp, PostgreSQL time,
or wall-clock comparison. It is therefore valid only for local process state.
Bucket storage must have the stated idle expiry and hard entry bound; it must
not retain unbounded project identities. Restarting the API process clears all
buckets. Separate API processes each enforce their own independent limit.

The values are bounded local-demo defaults, not a customer plan, production
capacity claim, or durable daily/monthly quota. A later configuration slice may
make values configurable only with validated positive bounds and focused tests;
it must not silently turn this limiter into a distributed quota.

### Admission and idempotency

Each authenticated, path-authorized request that reaches the limiter consumes
one token before body parsing and before any idempotency lookup or publish
transaction. This includes a malformed body and an equivalent retry using an
existing `Idempotency-Key`.

When no token is available, RelayForge returns `429 Too Many Requests` with:

- `application/problem+json` code `PUBLISH_RATE_LIMITED`;
- `Retry-After` as the ceiling of the next token availability in whole seconds,
  with a minimum value of `1`; and
- no event, delivery, idempotency lookup, or publish transaction.

A client retry that was rate-limited may wait and retry with the same key. Once
admitted, the existing idempotency contract is unchanged: an equivalent command
returns the original logical result, while different content for that key is a
conflict. Rate limiting can delay that result after an uncertain client timeout,
but it cannot create a duplicate event or delivery.

### Observability and security

The API emits bounded admission/rejection metrics and a sanitized traceable log
event. They may use fixed outcome labels such as `admitted` and `rejected`, but
must not use project IDs, API-key IDs, idempotency keys, request bodies, or raw
credentials as labels or log values. The rejection response exposes neither the
bucket balance nor another project's state.

## Rationale and trade-offs

- Project-wide buckets prevent a project from bypassing its bound by creating
  additional API keys. The authenticated project is already the ownership and
  event-idempotency boundary.
- Token buckets permit the explicit 60-request burst while restoring a stable
  30-request-per-second rate. A fixed window would create unfair boundary
  bursts and make the retry wait less meaningful.
- Counting an equivalent idempotent retry preserves a cheap rejection path. An
  exemption would require a body parse and database lookup before admission,
  allowing an abusive valid credential to bypass the protection precisely on the
  path it is intended to bound.
- Local monotonic state is simple and has no distributed correctness claim. The
  trade-off is restart reset and per-instance limits, accepted for the one-API
  process demo.
- The baseline allows a moderate normal publish burst without claiming the
  chosen values are an EC2 or production-safe limit.

## Alternatives considered

### One bucket per API key

This can isolate independent clients, but a project can create multiple keys
and multiply its admitted rate. The project-level resource boundary is more
useful for the current product.

### Exempt equivalent idempotent retries

This reduces a client's wait after a lost response, but needs pre-admission
parsing/fingerprinting and a database read. It is rejected for this local abuse
control; the existing idempotency guarantee still resolves the retry once it is
admitted.

### PostgreSQL-backed or Redis-backed counter

Either could coordinate multiple API instances, but adds database load or a new
availability/consistency dependency. No multi-instance shared-counter need has
been measured, so both are deferred.

### Rate-limit before API-key authentication

An IP-based anonymous limiter would address credential-guessing or network
abuse, not publisher-project admission. It cannot safely identify a project
and is a separate security slice.

### Persistent quota or billing plan

Durable accounting changes product scope, reset semantics, owner visibility,
and authorization. It is explicitly outside this local admission contract.

## Verification gate

A later implementation is accepted only after focused tests prove:

1. authenticated requests for one project share a bucket across its API keys,
   while another project has independent capacity;
2. concurrent requests cannot consume more than the available capacity;
3. a rejected request performs no body read, publish transaction, event insert,
   or delivery creation;
4. `429` uses `PUBLISH_RATE_LIMITED` and a positive whole-second
   `Retry-After` without exposing bucket state;
5. an admitted equivalent retry preserves the existing idempotent response and
   a conflicting retry remains `409`;
6. malformed bodies and equivalent retries consume one token only after valid
   authentication and path authorization;
7. idle buckets expire, retained state never exceeds the configured bound, and
   API restart clears local state;
8. metrics/logs are bounded and contain no project/API-key/idempotency/raw-body
   value; and
9. API/worker runtime separation, generic invalid-key handling, the 64 KiB
   body bound, and existing publish regression tests remain intact.

This ADR changes documentation only. It does not add an implementation,
dependency, migration, Redis, broker, persistent quota, billing, or cluster
guarantee.

## References

- [RelayForge Requirements](../REQUIREMENTS.md)
- [RelayForge Security Baseline](../SECURITY_BASELINE.md)
- [RelayForge API Contract](../API_CONTRACT.md)
- [RelayForge Architecture Boundaries](../ARCHITECTURE_BOUNDARIES.md)
- [RelayForge Local Performance Baseline](../PERFORMANCE_BASELINE.md)
