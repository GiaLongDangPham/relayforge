# RelayForge Database Model Part 1: Identity, Project, and Endpoint Configuration

Status: Phase 0 baseline
Last updated: 2026-08-10

## 1. Purpose and boundary

This document defines the first bounded part of the PostgreSQL model:

- bootstrap owner accounts;
- projects and their single-owner relationship;
- publisher API-key lifecycle;
- webhook endpoint configuration;
- exact event-type subscriptions.

It defines conceptual tables, column types, ownership, constraints, transaction rules, and future test evidence. It does not provide migration SQL or JPA mappings.

Event, publish-idempotency, delivery, attempt, late-diagnostic, replay, claim, retry, and retention tables are explicitly outside Part 1 and defined by [Database Model Part 2](DATABASE_MODEL_PART2.md). API behavior and security mechanisms are defined by [API Contract](API_CONTRACT.md) and [Security Baseline](SECURITY_BASELINE.md); their migrations and code remain deferred.

## 2. Shared persistence conventions

### 2.1 Identifiers

Aggregate and credential records use PostgreSQL `uuid` identifiers generated as UUIDv4 by the application before persistence.

Application generation allows an identifier to exist before a transaction or ORM flush and requires no PostgreSQL extension. Random UUIDs have poorer index locality than time-ordered identifiers, but the expected Portfolio v1 configuration volume is small. UUIDv7 is reconsidered only if measured index behavior justifies another generator or dependency.

Identifiers are opaque. Neither API clients nor authorization code may infer ownership from an identifier.

### 2.2 Timestamps and time zones

Persisted instants use PostgreSQL `timestamptz` and map to Java `Instant`. PostgreSQL time supplies `created_at`, `updated_at`, and revocation timestamps.

The database session and application display policy may use different time zones, but persisted instants are compared as absolute time. No local date-time without offset is used for lifecycle decisions.

### 2.3 Names and bounded text

Human names are trimmed, nonblank, and bounded. They are labels rather than identifiers unless this document explicitly defines uniqueness.

Project, API-key, and endpoint display names are not unique. This avoids inventing a business rule that the requirements do not need; the UI and API use stable UUIDs to disambiguate them.

### 2.4 Optimistic versioning

Mutable aggregate roots use a nonnegative `bigint version`, initially zero. A successful mutation matches the expected version and increments it once.

Optimistic versioning detects lost updates in owner, project, and endpoint management. It does not replace the worker's lease, claim token, or database locking rules.

### 2.5 No hard deletion in Portfolio v1

Owner, project, API-key, and endpoint records are not hard-deleted through v1 product use cases:

- projects have no deactivation or deletion workflow;
- API keys are revoked by setting `revoked_at` once;
- endpoints are paused with `enabled = false` rather than deleted;
- bootstrap owner accounts have no public deletion workflow.

Parent foreign keys therefore use restrictive deletion behavior. A later deletion feature requires a separate data-lifecycle decision covering accepted events and nonterminal deliveries.

## 3. Module and table ownership

| Module | Conceptual table | Responsibility |
| --- | --- | --- |
| `identity` | `owner_accounts` | Bootstrap owner login identity and password hash. |
| `project` | `projects` | Project identity, name, and exactly one owner. |
| `project` | `project_api_keys` | Publisher credential identifier, nonrecoverable digest, display hint, and revocation. |
| `endpoint` | `webhook_endpoints` | Current endpoint URL, enabled state, and retrievable encrypted signing material. |
| `endpoint` | `endpoint_subscriptions` | Exact case-sensitive event types subscribed by an endpoint. |

Module ownership means other modules use public contracts rather than importing another module's entity or repository. Foreign keys preserve relational integrity but do not grant cross-module repository access.

## 4. `owner_accounts`

### 4.1 Columns

| Column | Conceptual PostgreSQL type | Rule |
| --- | --- | --- |
| `id` | `uuid` | Primary key; application-generated UUIDv4. |
| `login_name` | `varchar(100)` | Required canonical lowercase login; trimmed and nonblank. |
| `password_hash` | `varchar(255)` | Required self-describing password hash; plaintext is never stored. |
| `version` | `bigint` | Required, nonnegative, initially zero. |
| `created_at` | `timestamptz` | Required; assigned from PostgreSQL time once. |
| `updated_at` | `timestamptz` | Required; assigned from PostgreSQL time and advanced on mutation. |

### 4.2 Constraints and lifecycle

- `login_name` is globally unique in its stored canonical form.
- Portfolio v1 accepts a conservative ASCII login alphabet and stores it lowercase, avoiding locale-dependent case-folding inside authorization queries.
- Bootstrap seeding is idempotent by canonical login name.
- A repeated seed with the same login does not silently replace an existing password hash. Credential rotation requires an explicit controlled operation.
- The security baseline chooses BCrypt cost 12 initially; the stored self-describing value supports verification and later measured cost changes.

Email is not stored in v1 because registration, email verification, and password reset are non-goals.

### 4.3 Physical implementation status

Flyway V2 creates `public.owner_accounts` with an application-supplied UUID primary key, globally unique `login_name`, nonnegative version defaulting to zero, and PostgreSQL-owned lifecycle timestamp defaults. The login constraint uses the exact `^[a-z0-9][a-z0-9._-]*$` policy under `C` collation so its ASCII meaning does not change with deployment collation. Encoded password hashes must be bounded, nonempty, and contain no ASCII whitespace.

The Phase 1 JPA mapping keeps `OwnerAccountEntity` internal to `identity.persistence`. Hibernate validates rather than creates the Flyway schema, maps assigned UUID and boxed `Long @Version`, maps `timestamptz` to `Instant`, and asks PostgreSQL to generate creation/update timestamps. Integration evidence covers persistence-context identity, detach/reload, dirty checking, one version increment, and rejection of a stale detached merge.

The owner-bootstrap use case canonicalizes and validates the login, computes a BCrypt cost-12 hash before opening a short `READ COMMITTED` transaction, and uses `INSERT ... ON CONFLICT (login_name) DO NOTHING RETURNING id` followed by a separate read for the losing path. This preserves the first committed hash and lets concurrent callers converge on one owner without continuing a PostgreSQL transaction after a unique-constraint error. PostgreSQL integration evidence covers first creation, repeated and case-variant idempotency, concurrent convergence, winner-hash preservation, and an API-only opt-in startup followed by an idempotent restart.

Credential verification now uses a JPQL constructor projection to read only owner ID, canonical login, and password hash inside a short read-only transaction. The detached internal projection has no field-rendering `toString`; BCrypt verification runs after the transaction closes, and the public result exposes only verified ID/login or an empty invalid outcome. Full Spring Security authentication and sessions remain outside this implemented boundary.

## 5. `projects`

### 5.1 Columns

| Column | Conceptual PostgreSQL type | Rule |
| --- | --- | --- |
| `id` | `uuid` | Primary key; application-generated UUIDv4. |
| `owner_id` | `uuid` | Required foreign key to `owner_accounts.id`; restrictive parent deletion. |
| `name` | `varchar(120)` | Required, trimmed, and nonblank; not unique. |
| `version` | `bigint` | Required, nonnegative, initially zero. |
| `created_at` | `timestamptz` | Required; PostgreSQL time. |
| `updated_at` | `timestamptz` | Required; PostgreSQL time, advanced on rename. |

### 5.2 Constraints and authorization boundary

- Non-null `owner_id` gives every project exactly one owner in v1.
- There is no join table for organization membership or roles.
- A foreign key proves that the owner exists; it does not prove that the current requester is that owner.
- Owner-facing queries and mutations must scope by both authenticated owner identity and project identity, preventing IDOR even when a valid project UUID is guessed.
- Concurrent renames use the project version and allow one winner; stale versions return a conflict rather than overwriting silently.

## 6. `project_api_keys`

### 6.1 Columns

| Column | Conceptual PostgreSQL type | Rule |
| --- | --- | --- |
| `id` | `uuid` | Primary key; application-generated UUIDv4 and stable credential record identifier. |
| `project_id` | `uuid` | Required foreign key to `projects.id`; restrictive parent deletion. |
| `display_name` | `varchar(120)` | Required, trimmed, and nonblank; not unique. |
| `key_hint` | `varchar(24)` | Required nonsecret display prefix or hint; globally unique. |
| `secret_digest` | `bytea` | Required nonrecoverable digest of the presented credential; globally unique. |
| `created_at` | `timestamptz` | Required; PostgreSQL time. |
| `revoked_at` | `timestamptz` | Nullable; null means active, non-null means permanently revoked. |

### 6.2 Credential rules

- Raw API-key material is generated from cryptographically secure randomness and returned only in the successful creation response.
- Raw API keys are never persisted, logged, or recoverable from `secret_digest`.
- The security baseline defines token format, peppered HMAC-SHA-256 digest, and constant-time verification. Persistence still exposes only opaque digest bytes and a nonsecret hint.
- `key_hint` is safe for owner-facing identification but is never sufficient for authentication.
- Revocation is monotonic: `revoked_at` may move only from null to a PostgreSQL timestamp and is never cleared in v1.
- Revocation uses a conditional update, so repeated revoke commands are idempotent and cannot reactivate a key.
- If database commit succeeds but the creation response containing the raw key is lost, the raw key cannot be reconstructed. The owner revokes the orphaned credential and creates another one.
- `last_used_at` is intentionally absent because updating it on every publish would add write contention without a v1 acceptance requirement.

## 7. `webhook_endpoints`

### 7.1 Columns

| Column | Conceptual PostgreSQL type | Rule |
| --- | --- | --- |
| `id` | `uuid` | Primary key; application-generated UUIDv4. |
| `project_id` | `uuid` | Required foreign key to `projects.id`; restrictive parent deletion. |
| `name` | `varchar(120)` | Required, trimmed, and nonblank; not unique. |
| `destination_url` | `varchar(2048)` | Required syntactically valid absolute URL string. |
| `enabled` | `boolean` | Required; initial value chosen by the create use case, normally true. |
| `signing_secret_ciphertext` | `bytea` | Required encrypted signing-secret envelope; plaintext is never stored. |
| `encryption_key_reference` | `varchar(128)` | Required identifier/version for resolving the external encryption key. |
| `version` | `bigint` | Required, nonnegative, initially zero. |
| `created_at` | `timestamptz` | Required; PostgreSQL time. |
| `updated_at` | `timestamptz` | Required; PostgreSQL time, advanced on any aggregate mutation. |

### 7.2 URL rules

- `destination_url` is the current configuration, not historical attempt evidence.
- URL updates use optimistic versioning and affect only attempts that start after the update commits.
- Storage validation never proves a destination safe. The persistence layer does not rewrite path, query, escaping, or trailing-slash semantics; every production attempt still parses, resolves, and validates the actual connection target immediately before connecting.
- Production policy requires public HTTPS and rejects user-info, loopback, private, link-local, multicast, reserved, and cloud-metadata destinations. Development-only local HTTP is controlled by application configuration, not a different table shape.
- Different endpoints may intentionally share the same URL, so URL uniqueness is not enforced.

### 7.3 Signing-secret rules

API-key secrets can be verified from a digest, but endpoint signing secrets must be recovered to compute an outbound HMAC. Therefore the endpoint stores an encrypted envelope rather than a hash or plaintext.

- The data-encryption/master key is not stored in this table or committed to the repository.
- The security baseline defines the authenticated envelope contract and local AES-256-GCM option; cloud key custody remains a deployment decision.
- The logical signing secret is immutable for the v1 endpoint lifetime.
- Infrastructure key rotation may re-encrypt the same logical secret and update ciphertext/key reference through a controlled maintenance path; this is not endpoint signing-secret rotation.
- Secret plaintext, ciphertext, and key reference never appear in normal logs, delivery history, or API responses after endpoint creation.

## 8. `endpoint_subscriptions`

### 8.1 Columns

| Column | Conceptual PostgreSQL type | Rule |
| --- | --- | --- |
| `endpoint_id` | `uuid` | Foreign key to `webhook_endpoints.id`; part of the primary key; restrictive parent deletion. |
| `event_type` | `varchar(200)` | Exact case-sensitive event type; trimmed and nonblank; part of the primary key. |
| `created_at` | `timestamptz` | Required; PostgreSQL time. |

### 8.2 Subscription rules

- Composite primary key `(endpoint_id, event_type)` prevents duplicate subscriptions for one endpoint.
- `event_type` comparison is exact and case-sensitive. Wildcards and expression filters are absent.
- `project_id` is not duplicated in this table; endpoint ownership supplies it. Routing joins through the endpoint public persistence contract.
- An endpoint must have at least one subscription after a create or update transaction commits.
- That “at least one” rule is an aggregate invariant rather than a simple row check. The endpoint application service replaces subscriptions and increments the endpoint version in one transaction.
- Concurrent endpoint configuration/subscription changes match one expected endpoint version, so one complete aggregate update wins rather than interleaving row changes.

## 9. Transaction boundaries

| Workflow | Required atomic behavior |
| --- | --- |
| Bootstrap owner | Insert one canonical login or return the existing record without replacing its hash implicitly. |
| Create project | Persist one project linked to the authenticated owner. |
| Rename project | Match owner and expected version, then update name, version, and `updated_at` together. |
| Create API key | Generate raw material, persist only identifier/hint/digest, commit, then return raw material once. |
| Revoke API key | Conditionally set `revoked_at` only when the key belongs to the owner's project and is still active. |
| Create endpoint | Persist endpoint configuration, encrypted signing material, and at least one subscription in one transaction. |
| Update endpoint configuration | Match project ownership and expected version; update name/URL/subscription set, increment version once, and retain the logical signing secret. |
| Disable or enable endpoint | Match project ownership and expected version; change `enabled`, version, and `updated_at` in one transaction. |

Controllers and security filters do not own these transactions. Module application use cases do. No transaction in Part 1 performs outbound HTTP or calls an external key service after database mutation has begun; required cryptographic material is prepared before the persistence transaction.

## 10. Deletion and foreign-key behavior

| Parent | Child | V1 behavior |
| --- | --- | --- |
| `owner_accounts` | `projects` | Restrict deletion. |
| `projects` | `project_api_keys` | Restrict deletion; revoke keys instead. |
| `projects` | `webhook_endpoints` | Restrict deletion; disable endpoints instead. |
| `webhook_endpoints` | `endpoint_subscriptions` | Restrict parent deletion; individual subscription rows may change inside an endpoint transaction. |

No cascade from configuration tables to future event or delivery history is allowed. A future account/project/endpoint erasure feature must distinguish configuration removal, secret destruction, audit retention, terminal history, and nonterminal work before changing these rules.

## 11. Required future query patterns

Exact indexes are deferred, but migrations must later support and verify these access patterns:

1. find one owner by canonical login;
2. list and fetch projects by authenticated owner and project identity;
3. find a publisher key verification candidate without scanning raw secrets;
4. list active and revoked key metadata for one owned project;
5. list and fetch endpoints by owned project;
6. select enabled endpoints for one project and exact event type;
7. check enabled state for a batch of endpoint identifiers during claim;
8. read one endpoint's enabled state, current URL, and signing material at attempt start.

The database-design implementation slice must use representative data and `EXPLAIN ANALYZE` before claiming an index is effective. An index is not added only because a column appears in a foreign key or filter.

## 12. Required future test evidence

Implementation must eventually prove:

1. concurrent bootstrap attempts for one canonical login create one owner and do not overwrite its password hash;
2. case variants of a login cannot create separate canonical accounts;
3. a project always references exactly one existing owner;
4. repository and API tests reject cross-owner project, key, and endpoint access even with valid UUIDs;
5. API-key persistence contains digest and hint but no raw credential;
6. repeated revocation is idempotent and a revoked key never becomes active again;
7. an API-key creation response lost after commit cannot reveal the raw key and is handled by revoke-and-replace;
8. endpoint creation rolls back completely when its subscription set is invalid;
9. duplicate exact subscriptions are rejected while different case remains a distinct event type;
10. concurrent endpoint updates using one version produce one winner and one conflict, including subscription replacement;
11. URL update does not change the logical signing secret;
12. endpoint persistence and representative logs contain no signing-secret plaintext;
13. disable/enable updates are visible to the future routing, claim-eligibility, and attempt-start queries under their reviewed transaction rules;
14. attempted parent deletion is restricted and does not cascade into configuration or future delivery history.

PostgreSQL constraint and transaction behavior must use Testcontainers rather than H2 when implemented.

The Phase 1 persistence foundation now fixes these physical conventions without yet implementing a Part 1 table:

- versioned PostgreSQL SQL migrations use Flyway;
- the pinned integration-test image is PostgreSQL `17.10-alpine`, and V1 rejects servers older than PostgreSQL 17;
- Portfolio v1 uses the `public` schema; module ownership remains enforced by Java package, repository, and transaction boundaries rather than separate database schemas;
- local and test application startup may run Flyway automatically, while the single production migration owner remains a deployment decision.

## 13. Decisions deferred to the next database slices

- Physical implementation of the Part 2 event, delivery, attempt, replay, diagnostic, token, lease, and due-time model.
- Claim/recovery SQL, indexes, lock modes, and isolation evidence.
- Production migration ownership and compatibility validation during rollout.
- JPA mappings, repository contracts, fetch plans, and pagination.
- Spring Session JDBC infrastructure migration and security adapter mappings.
- Cloud endpoint-secret key provider and rotation operations.
- Audit events beyond bounded lifecycle timestamps.

Later slices may refine physical representation, but they must preserve the ownership, secrecy, authorization, optimistic-concurrency, and no-hard-delete rules in this Part 1 baseline unless a focused decision supersedes them.
