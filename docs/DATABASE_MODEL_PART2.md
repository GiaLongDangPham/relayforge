# RelayForge Database Model Part 2: Event and Delivery Processing

Status: Phase 0 baseline
Last updated: 2026-08-09

## 1. Purpose and boundary

This document completes the conceptual Portfolio v1 business-data model for:

- immutable accepted events and publish idempotency;
- original and replay delivery records;
- PostgreSQL-backed delivery queue state;
- append-only attempt history;
- late diagnostic observations;
- replay idempotency;
- terminal-history retention relationships.

It defines table responsibilities, conceptual PostgreSQL types, constraints, transaction rules, query patterns, and future test evidence. It does not provide migration SQL, indexes, lock statements, isolation levels, JPA mappings, repositories, or cleanup implementation.

Configuration tables remain defined by [Database Model Part 1](DATABASE_MODEL_PART1.md).

## 2. Table ownership and relationships

All Part 2 tables belong to the `delivery` module.

| Conceptual table | Responsibility |
| --- | --- |
| `events` | Immutable accepted event, payload, and publish-idempotency identity/fingerprint. |
| `deliveries` | One event-to-endpoint delivery intent, queue state, current claim, retry due-time, and replay lineage. |
| `delivery_attempts` | One append-only numbered dispatch cycle and its immutable terminal observation. |
| `attempt_late_diagnostics` | Optional stale-worker observation that arrived after its attempt became `UNKNOWN`. |
| `replay_requests` | Project-scoped replay idempotency mapping from command to one new linked delivery. |

```text
projects 1 -> many events
events 1 -> many deliveries
webhook_endpoints 1 -> many deliveries
deliveries 1 -> at most 5 delivery_attempts
delivery_attempts 1 -> zero or one attempt_late_diagnostics
exhausted delivery 1 -> many replay deliveries
replay_requests 1 -> exactly one replay delivery
```

No separate generic jobs table exists. The `deliveries` row is the durable work record.

## 3. `events`

### 3.1 Columns

| Column | Conceptual PostgreSQL type | Rule |
| --- | --- | --- |
| `id` | `uuid` | Primary key; application-generated UUIDv4. |
| `project_id` | `uuid` | Required foreign key to `projects.id`; restrictive deletion. |
| `event_type` | `varchar(200)` | Required exact case-sensitive event type. |
| `payload` | `jsonb` | Required immutable JSON value; at most 64 KiB before persistence. |
| `idempotency_key` | `varchar(200)` | Required publisher-supplied key, unique within a project. |
| `fingerprint_version` | `smallint` | Required positive canonicalization version. |
| `command_fingerprint` | `bytea` | Required digest of the equivalent publish command. |
| `accepted_at` | `timestamptz` | Required PostgreSQL acceptance time. |

### 3.2 Publish idempotency

`events` is also the publish-idempotency record; a second table would duplicate the event-to-key lifecycle.

- Unique `(project_id, idempotency_key)` identifies one logical publish command.
- Equivalent command means the same normalized exact event type and the same JSON value.
- JSON object member order is insignificant because persistence uses `jsonb`; array order and value differences remain significant.
- `command_fingerprint` is calculated from a versioned canonical representation of event type plus payload. The version is part of the fingerprint preimage so canonicalization can evolve without silently changing old comparisons.
- The fingerprint is an optimization and audit value. On a repeated key, RelayForge compares the stored event type/payload semantics as the correctness fallback rather than trusting digest collision impossibility.
- Same key plus equivalent command returns the original event and delivery count.
- Same key plus different command returns conflict and changes nothing.

### 3.3 Immutability

After commit, project, event type, payload, idempotency key, fingerprint, and acceptance time never change. Corrections create another event with another idempotency key.

The 64 KiB limit is checked against accepted request bytes before JSON parsing/persistence so alternate JSON formatting cannot bypass the ingress bound. The stored `jsonb` value may have a different serialized byte size.

## 4. `deliveries`

### 4.1 Columns

| Column | Conceptual PostgreSQL type | Rule |
| --- | --- | --- |
| `id` | `uuid` | Primary key; application-generated UUIDv4. |
| `project_id` | `uuid` | Required project scope; must match both event and endpoint project. |
| `event_id` | `uuid` | Required foreign key to `events.id`; restrictive deletion while retained. |
| `endpoint_id` | `uuid` | Required foreign key to `webhook_endpoints.id`; restrictive deletion. |
| `replay_of_delivery_id` | `uuid` | Nullable self-reference; null for original fan-out, original exhausted delivery ID for replay. |
| `state` | `varchar(32)` | Required checked value: `PENDING`, `CLAIMED`, `SUCCEEDED`, `FAILED_PERMANENT`, or `EXHAUSTED`. |
| `due_at` | `timestamptz` | Required only for `PENDING`; PostgreSQL-time claim eligibility. |
| `attempt_count` | `smallint` | Required integer from 0 through 5; count of durably started attempts. |
| `claim_token` | `uuid` | Nullable globally unique opaque current fencing token. |
| `lease_expires_at` | `timestamptz` | Nullable PostgreSQL-time lease expiry paired with `claim_token`. |
| `created_at` | `timestamptz` | Required PostgreSQL creation time. |
| `updated_at` | `timestamptz` | Required PostgreSQL time of last state/claim change. |
| `terminal_at` | `timestamptz` | Required only for terminal state. |

### 4.2 Project consistency

Denormalized `project_id` makes authorization and project-scoped pagination explicit, but it must not drift:

- delivery project must equal event project;
- delivery project must equal endpoint project;
- replay delivery project/event/endpoint must equal the original delivery.

Future migrations must enforce these relationships with database constraints or equivalent composite-key integrity, not application convention alone.

### 4.3 Original routing and replay lineage

- Original fan-out delivery has `replay_of_delivery_id = null`.
- One accepted event creates at most one original delivery for each selected endpoint.
- Replay delivery has a new ID, the same project/event/endpoint identity, and `replay_of_delivery_id` pointing to the exhausted source delivery.
- Any exhausted delivery, including a prior replay, may be replayed. Each new delivery points to its immediate source, preserving a lineage chain without rewriting ancestors.
- Terminal source history is never mutated when a replay is created.

The future physical model must enforce uniqueness for original `(event_id, endpoint_id)` while still permitting linked replay rows for that same pair.

### 4.4 State-dependent constraints

| State | `due_at` | `claim_token` and lease | `terminal_at` | Attempt constraint |
| --- | --- | --- | --- | --- |
| `PENDING` | Non-null | Both null | Null | 0 through 4. |
| `CLAIMED` | Null | Both non-null | Null | 0 through 5; attempt may or may not have started. |
| `SUCCEEDED` | Null | Both null | Non-null | 1 through 5. |
| `FAILED_PERMANENT` | Null | Both null | Non-null | 1 through 5. |
| `EXHAUSTED` | Null | Both null | Non-null | Exactly 5. |

Additional rules:

- token and lease are either both null or both non-null;
- a non-null current token is globally unique;
- every transition out of `CLAIMED` clears token and lease atomically;
- automatic processing never changes a terminal delivery;
- `RETRY_SCHEDULED` is derived from `PENDING`, `attempt_count > 0`, and future `due_at`;
- `PAUSED` is derived from a nonterminal delivery whose current endpoint is disabled;
- worker completion does not use an optimistic version; it conditionally matches state, current token, and unexpired lease.

## 5. `delivery_attempts`

### 5.1 Columns

| Column | Conceptual PostgreSQL type | Rule |
| --- | --- | --- |
| `id` | `uuid` | Primary key; application-generated UUIDv4 and stable outbound attempt ID. |
| `delivery_id` | `uuid` | Required foreign key to `deliveries.id`. |
| `attempt_number` | `smallint` | Required integer 1 through 5; unique within delivery. |
| `claim_token` | `uuid` | Required token that crossed this attempt's start boundary; historical, not current ownership. |
| `status` | `varchar(32)` | `STARTED`, `SUCCEEDED`, `RETRYABLE_FAILURE`, `PERMANENT_FAILURE`, or `UNKNOWN`. |
| `destination_fingerprint_version` | `smallint` | Required positive URL-fingerprint format version. |
| `destination_fingerprint` | `bytea` | Required digest of the exact URL snapshot used for this attempt. |
| `started_at` | `timestamptz` | Required PostgreSQL attempt-start time. |
| `finished_at` | `timestamptz` | Null only for `STARTED`; immutable terminal observation time otherwise. |
| `http_status` | `smallint` | Nullable observed HTTP status. |
| `failure_code` | `varchar(64)` | Nullable bounded internal classification such as timeout, network, blocked destination, or response class. |
| `latency_ms` | `integer` | Nullable nonnegative local observed dispatch duration. |
| `retry_delay_ms` | `integer` | Nullable positive effective delay only when this terminal attempt returned its delivery to retry `PENDING`. |
| `retry_schedule_source` | `varchar(32)` | Nullable paired bounded value: `BACKOFF` or `RETRY_AFTER`; records the input that selected the effective delay, never the raw receiver header. |
| `response_preview` | `bytea` | Nullable first at most 8 KiB of response body bytes. |
| `response_truncated` | `boolean` | Required; true when additional response bytes were omitted. |

### 5.2 Attempt invariants

- Unique `(delivery_id, attempt_number)` prevents reuse.
- Attempt number is delivery `attempt_count + 1` at the atomic start boundary; delivery count and inserted number advance together.
- At most one `STARTED` attempt exists for one delivery.
- `STARTED` has null finish/result fields except required identity and destination fingerprint; its required `response_truncated` flag is false.
- A terminal attempt status is immutable.
- HTTP 2xx maps to `SUCCEEDED`.
- HTTP 408, 429, 5xx, network errors, and timeout map to `RETRYABLE_FAILURE`.
- Other HTTP 4xx and a security-blocked destination map to `PERMANENT_FAILURE`; blocked destination has no HTTP status.
- Lease recovery maps an unfinished `STARTED` attempt to `UNKNOWN`.
- A retrying terminal attempt persists both a positive effective delay and its
  source, or neither. `RETRY_AFTER` is allowed only for a retryable HTTP
  response whose accepted bounded hint strictly exceeded equal-jitter backoff;
  `UNKNOWN` recovery uses `BACKOFF`. Terminal/exhausted outcomes store neither.
- `deliveries.due_at` is still computed from PostgreSQL time in the same
  conditional finalization transaction; the stored delay is audit evidence,
  not a second scheduling clock.
- A destination fingerprint is audit evidence, not a dispatch source. The exact URL exists only in the committed in-memory dispatch instruction; after a crash the attempt becomes `UNKNOWN` rather than being resumed.
- Response preview is sensitive owner-visible diagnostic data. It is bounded, never logged, and excluded from list responses by default.

The URL fingerprint uses a versioned preimage and SHA-256 over the exact stored URL bytes selected at attempt start. Fingerprint versioning prevents an algorithm change from making historical values ambiguous.

### 5.3 Future endpoint circuit state

V14 adds the delivery-owned `endpoint_circuit_breakers` relation keyed by
endpoint ID. It is intentionally not part of the owner-managed
`webhook_endpoints` aggregate: a worker must update receiver-health state
without changing endpoint configuration or its optimistic version.

| Column | Conceptual PostgreSQL type | Rule |
| --- | --- | --- |
| `endpoint_id` | `uuid` | Primary key and foreign-key identity of the protected endpoint. A missing row means `CLOSED` with zero failures. |
| `state` | `varchar(32)` | `CLOSED`, `OPEN`, or `HALF_OPEN`. |
| `consecutive_qualifying_failures` | `integer` | Nonnegative failure streak; `CLOSED` may retain a sub-threshold streak, while `OPEN` and `HALF_OPEN` retain threshold-crossing evidence. |
| `open_until` | `timestamptz` | Required for `OPEN`; PostgreSQL-time cooldown boundary. |
| `probe_delivery_id` | `uuid` | Required only for `HALF_OPEN`; the one delivery allowed to test recovery. |
| `probe_claim_token` | `uuid` | Required only for `HALF_OPEN`; fences that probe to the current delivery claim. |
| `updated_at` | `timestamptz` | Required PostgreSQL transition time. |

V14 creates the restrictive relation, while V15 corrects the state shape so
`CLOSED` can retain a zero, one, or two-failure streak without cooldown/probe
data. Together they enforce `CLOSED`/`OPEN`/`HALF_OPEN` shapes, nonnegative
failure counts, endpoint/probe-delivery references, and PostgreSQL timestamps.
`HALF_OPEN` has both probe fields and no normal claims; other states have
neither. Phase 2B Slice 6 implements the [ADR-009](adr/0009-postgresql-endpoint-circuit-breaker.md)
transition rules: fair candidate selection excludes unexpired `OPEN` and every
`HALF_OPEN` circuit; the conditional claim transaction turns one eligible
`OPEN` candidate into its fenced `HALF_OPEN` probe; conditional attempt
finalization updates delivery/attempt state and the circuit atomically; and
post-attempt `UNKNOWN` recovery reopens only the matching probe. PostgreSQL
time controls every cooldown comparison and extension.

## 6. `attempt_late_diagnostics`

### 6.1 Columns

| Column | Conceptual PostgreSQL type | Rule |
| --- | --- | --- |
| `id` | `uuid` | Primary key; application-generated UUIDv4 observation ID. |
| `attempt_id` | `uuid` | Required unique foreign key to one `UNKNOWN` attempt. |
| `claim_token` | `uuid` | Required stale token that produced the observation. |
| `observed_status` | `varchar(32)` | Observed success, retryable failure, or permanent failure; diagnostic only. |
| `http_status` | `smallint` | Nullable observed HTTP status. |
| `failure_code` | `varchar(64)` | Nullable diagnostic classification. |
| `latency_ms` | `integer` | Nullable nonnegative local observed duration. |
| `observed_at` | `timestamptz` | Required PostgreSQL persistence time. |

### 6.2 Diagnostic rules

- At most one late diagnostic is stored for an attempt; retries of the same diagnostic write return the existing row.
- The referenced attempt must already be terminal `UNKNOWN`.
- Diagnostic insertion never updates attempt status, delivery state, due-time, claim token, or attempt budget.
- It may be persisted only after validating that the submitted token belongs to that historical attempt.
- Response body preview is not duplicated into late diagnostics in v1, limiting sensitive diagnostic storage.

## 7. `replay_requests`

### 7.1 Columns

| Column | Conceptual PostgreSQL type | Rule |
| --- | --- | --- |
| `id` | `uuid` | Primary key; application-generated UUIDv4 command record. |
| `project_id` | `uuid` | Required project scope. |
| `idempotency_key` | `varchar(200)` | Required owner-supplied key, unique within project. |
| `source_delivery_id` | `uuid` | Required exhausted delivery requested for replay. |
| `replay_delivery_id` | `uuid` | Required unique delivery created by this command. |
| `created_at` | `timestamptz` | Required PostgreSQL time. |

### 7.2 Replay idempotency

- Unique `(project_id, idempotency_key)` identifies one logical replay command.
- Equivalent command means the same source delivery ID.
- Same key and source returns the existing replay delivery.
- Same key with a different source returns conflict.
- Creating `replay_requests` and the linked `deliveries` row is one transaction.
- Source must belong to the authenticated owner's project and be `EXHAUSTED` at the replay transaction's decision point.
- Replay delivery starts `PENDING`, due immediately by PostgreSQL time, with zero attempts, no claim, and a fresh five-attempt budget.

## 8. Transaction boundaries

### 8.1 Publish acceptance

One `delivery` transaction:

1. resolves `(project_id, idempotency_key)`;
2. returns existing equivalent event or conflicts on different content;
3. inserts the immutable event for a new key;
4. reads enabled exact-subscription endpoint identities through the endpoint public contract;
5. inserts the complete original delivery set, each initially `PENDING` and due at PostgreSQL time;
6. commits before acknowledging acceptance.

An event with zero matching endpoints commits with zero deliveries. No acknowledged event may contain only part of its selected delivery set.

### 8.2 Claim

One short transaction locks bounded candidates, checks endpoint eligibility in batch, and changes only eligible `PENDING` rows to `CLAIMED` with unique tokens and 15-second PostgreSQL-time leases. It reserves no attempt and performs no network work.

### 8.3 Attempt start

One short transaction conditionally matches `CLAIMED`, current token, unexpired lease, enabled endpoint, and remaining budget; snapshots endpoint URL/signing material through the endpoint contract; inserts `STARTED`; increments delivery attempt count; and replaces lease expiry with the 20-second attempt-execution lease.

### 8.4 Finalization

One short transaction conditionally matches current unexpired claim, changes `STARTED` to exactly one terminal attempt observation, changes delivery to terminal or retry `PENDING`, sets the retry due-time when needed, and clears token/lease.

Outbound HTTP occurs between attempt-start commit and finalization transaction. No finalization retry resends HTTP.

### 8.5 Lease recovery

Recovery conditionally matches the still-current expired token. A pre-attempt claim returns to `PENDING` without incrementing attempt count. A started attempt becomes `UNKNOWN`; delivery returns to retry `PENDING` with due-time or becomes `EXHAUSTED` at attempt five. Token and lease clear atomically.

### 8.6 Replay and late diagnostic

Replay command plus new linked delivery is one transaction. Late diagnostic insertion is a separate append-only transaction and cannot participate in current delivery state changes.

## 9. Retention relationships

Terminal event, delivery, attempt, late-diagnostic, and replay-command history has a 30-day Portfolio v1 retention target. Nonterminal work is never removed.

Cleanup must operate on a complete terminal graph:

- an event is removable only when all of its original and replay deliveries are terminal and old enough;
- a delivery is not removed while referenced as replay source by retained history;
- attempts and late diagnostics are removed with their owning terminal delivery inside one cleanup unit;
- replay request records are removed with their replay/source graph, never earlier in a way that breaks idempotency during the retention window;
- configuration rows are not cascaded from delivery cleanup.

Group 14 implements this relation with a worker-only inbound retention port. PostgreSQL `CURRENT_TIMESTAMP` is the time authority. The default retention period is 30 days; a run has an initial one-minute delay, then a fixed one-hour delay, and cleans at most 25 event graphs (configurable from 1 through 100) per run.

Each candidate graph gets its own short `READ COMMITTED` transaction. The cleanup first selects one eligible event, then locks every currently visible delivery in its graph with `FOR UPDATE SKIP LOCKED`. If another transaction owns any graph delivery, this run skips the graph; it does not wait. Only after it owns the whole graph does it lock the event and recheck that the event and all original/replay deliveries are terminal and old enough. It then deletes late diagnostics, attempts, replay requests, replay leaves from child to source, and finally the event. If a replay lineage cannot be removed completely, the transaction fails and rolls back rather than retaining a partial graph. An empty-delivery event is eligible after its own retention period.

Replay locks its source delivery in its own transaction before PostgreSQL takes the event foreign-key lock for the new child. Retention follows that same delivery-then-event order. Therefore retention either sees a committed pending child during the recheck and leaves the graph intact, or retains the source lock first and prevents the replay transaction from creating a child that cleanup would partially remove. This order also prevents their prior event-to-delivery versus delivery-to-event deadlock. V12 supports the bounded candidate and graph checks with `(accepted_at, id)` on `events` and `(event_id, state, terminal_at)` on `deliveries`. These are query-shape support, not performance claims; representative `EXPLAIN ANALYZE` remains future evidence.

## 10. Required future query patterns

Migrations and later `EXPLAIN ANALYZE` evidence must support:

1. resolve publish command by project and idempotency key;
2. list project events by stable cursor `(accepted_at, id)`;
3. fetch one owned event and count/list its deliveries;
4. claim due eligible delivery candidates by state and due-time without waiting behind other workers;
5. recover expired claims by state and lease expiry;
6. fetch one owned delivery and its replay lineage;
7. list project deliveries filtered by event, endpoint, or effective status using stable cursor `(created_at, id)`;
8. fetch attempts for one delivery ordered by attempt number;
9. locate the one unfinished attempt for recovery;
10. resolve replay command by project and idempotency key;
11. select bounded terminal graphs eligible for retention cleanup.

Exact indexes remain a migration decision. Each proposed index must map to an observed query and be verified with representative distributions, including many paused endpoints and mixed terminal/history rows.

Group 6 adds the initial physical claim support in V8: a partial pending `(endpoint_id, due_at, id)` index for enabled-endpoint-filtered claim selection and a partial claimed `(lease_expires_at, id)` index for expired pre-attempt recovery. These support the current query shapes but are not performance claims; `EXPLAIN ANALYZE` with representative backlog distributions remains required.

Group 7 adds V9 `delivery_attempts`: restrictive delivery ownership, unique `(delivery_id, attempt_number)`, a partial unique `STARTED`-per-delivery index, attempt-number/fingerprint/status checks, and the strict `STARTED` result-field shape. Its attempt-start transaction locks the current claim and endpoint snapshot, then conditionally rechecks state/token/PostgreSQL-time lease/budget and absence of `STARTED` before incrementing the count and inserting the record. The destination fingerprint is version `1`: SHA-256 over UTF-8 `relayforge.destination.v1` followed by one NUL byte and the exact stored URL UTF-8 bytes. This is durable audit evidence only; the exact URL and encrypted signing material are in-memory dispatch data and are never persisted in the attempt row.

Group 10 adds V11 physical replay support. A partial unique `(event_id, endpoint_id)` index applies only to original rows with a null replay parent, while a project/event/endpoint/self-reference tuple prevents replay identity drift. `replay_requests` has project-scoped key uniqueness and a deferred lineage foreign key to the exact replay child, allowing the transaction to reserve the idempotency command before it inserts that child. V11 also adds the project delivery and project/event delivery keyset indexes used by owner history; they are query-shape support, not performance claims.

Phase 2B Slice 3 adds V13 retry-schedule audit columns to `delivery_attempts`.
They have positive-delay, paired-nullability, bounded-source, and terminal
status constraints. The finalization/recovery SQL continues to use
`CURRENT_TIMESTAMP + selected milliseconds` for `deliveries.due_at`; it stores
the selected delay/source beside the immutable terminal attempt observation,
not the raw `Retry-After` field.

Phase 2B Slice 5 adds V14 `endpoint_circuit_breakers`; V15 corrects its
closed-state streak constraint. Its primary key is `endpoint_id`; a missing row
still means `CLOSED`. Phase 2B Slice 6 joins that relation into the fair claim
candidate query and uses the primary-key circuit lookup plus existing V8
pending/claimed delivery indexes. A 128-row PostgreSQL 17.10 local
`EXPLAIN (ANALYZE, BUFFERS)` fixture found no evidence for a new breaker index;
this is a small warm fixture, not a production capacity claim.

Group 14 adds V12 retention indexes and the bounded PostgreSQL-time cleanup described in section 9. It deletes records only after the complete graph lock/recheck. Retention has no configuration-table cascade and no owner-facing deletion API.

## 11. Required future test evidence

PostgreSQL Testcontainers tests prove or must eventually prove:

1. concurrent equivalent publish commands create one event and one complete delivery set;
2. same publish key with different content conflicts without mutation;
3. event with zero routes commits and remains queryable;
4. original `(event, endpoint)` delivery is unique while replay children remain allowed;
5. cross-project event/endpoint/replay relationships are rejected;
6. every delivery state satisfies its due/token/lease/terminal column rules;
7. concurrent workers cannot create two valid current tokens for one delivery;
8. attempt start atomically increments count and inserts the matching unique attempt number;
9. a sixth attempt and a second unfinished attempt are rejected;
10. terminal attempt observation cannot be rewritten;
11. recovery makes `STARTED` immutable `UNKNOWN` and schedules correctly;
12. stale completion cannot change delivery and can create at most one separate late diagnostic;
13. replay idempotency converges under concurrent requests and conflicts on another source;
14. replay retains project/event/endpoint identity and leaves source history unchanged;
15. blocked receiver flow holds no database transaction during HTTP;
16. retention removes a complete expired terminal replay graph (including attempts, late diagnostics, and replay idempotency) and an expired no-route event while preserving configuration; it never deletes nonterminal work or a graph with a pending replay child;
17. cursor queries have no duplicate/omitted rows when multiple records share the same timestamp.
18. an eligible receiver hint can select a persisted bounded retry delay/source
    while PostgreSQL computes the matching due-time; shorter/equal hints and
    unknown recovery retain `BACKOFF`, and exhaustion stores no retry data.
19. three qualifying failures open one endpoint while a healthy endpoint still
    receives available fair-claim capacity; and
20. concurrent workers create exactly one cooldown-expired `HALF_OPEN` probe,
    whose success closes the circuit and whose expired `UNKNOWN` attempt
    reopens it without leaving a probe fence.

## 12. Decisions deferred to implementation slices

- Production migration ownership and compatibility validation during rollout. Flyway, a PostgreSQL 17 minimum, the pinned `17.10-alpine` integration-test image, and the `public` schema are already fixed by the Phase 1 persistence foundation.
- Finalization/recovery-after-attempt constraints, SQL, indexes, and lock modes beyond the current initial claim/recovery, attempt-start, and retention cleanup choices.
- JPA entity boundaries, repository ports, projections, and fetch plans.
- JSON canonicalization implementation and fingerprint version format.
- Response-preview encoding and UI redaction.
- Cursor token encoding and signing.

Physical design may evolve with evidence, but it must preserve atomic acceptance, project integrity, append-only attempts, conditional claim fencing, immutable unknown outcomes, replay idempotency, and at-least-once behavior.
