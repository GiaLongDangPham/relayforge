# RelayForge Portfolio v1 Requirements

Status: Reviewed Phase 0 baseline; U1.1 follow-on accepted
Last updated: 2026-09-01

## 1. Product definition

RelayForge is a developer-facing outbound webhook delivery platform. It accepts authenticated business events for a project, determines the active webhook endpoints subscribed to each event type, and asynchronously delivers a signed HTTP request to every matching endpoint.

RelayForge records every delivery attempt, retries bounded transient failures, exposes delivery history, and allows an owner to replay an exhausted delivery. Its purpose is reliable and observable delivery, not exactly-once processing.

## 2. Problem being solved

An application that sends webhooks directly inside a user-facing request inherits several failure modes:

- a slow receiver increases publisher latency;
- a receiver outage can lose an event or block the publisher;
- a timeout leaves the sender unsure whether the receiver processed the request;
- an application restart can lose in-memory retry state;
- multiple workers can compete for the same work;
- retries can create duplicate business effects at the receiver;
- failures are difficult to inspect without delivery history and metrics.

RelayForge separates event acceptance from outbound delivery and persists delivery intent before asynchronous processing begins.

## 3. Portfolio v1 outcome

Portfolio v1 is successful when one developer can demonstrate the following end-to-end flow locally and in a temporary cloud environment:

```text
Owner creates a project, API key, and subscribed endpoint
    -> publisher submits an event with an idempotency key
    -> RelayForge creates the expected deliveries
    -> workers claim and send those deliveries
    -> successful attempts stop
    -> transient failures retry with bounded backoff
    -> exhausted deliveries remain inspectable and replayable
```

The implementation must make correctness and failure behavior observable through tests, delivery history, logs, and metrics.

Portfolio v1 has two explicit gates so the 80-96 hour timebox remains controllable:

- **Core MVP gate:** the complete local delivery flow, ownership, API-key publishing, idempotency, fan-out, leased worker claims, bounded retry, history, replay, critical security controls, automated correctness tests, Docker, and a minimal dashboard.
- **Portfolio hardening gate:** a maximum of 16 hours for one CI workflow, one low-cost single-environment AWS deployment without high availability, one fixed load-test baseline, one JFR recording, and concise architecture/runbook evidence. It is not a production-hardening program.

The core gate is implemented first. Portfolio v1 is not called complete until both gates pass, but hardening work must not weaken core correctness to meet the calendar target. Features outside both gates are cut first.

## 4. Actors

### 4.1 Project owner

A human user who authenticates through the dashboard and owns one or more projects. The owner manages project resources and can inspect or replay deliveries only inside owned projects.

### 4.2 Publisher client

An application that authenticates with a project API key and publishes events for that project. It supplies a stable idempotency key for every publish operation.

### 4.3 Webhook receiver

An external HTTPS endpoint that receives signed webhook requests. It must tolerate duplicate delivery because RelayForge provides at-least-once delivery.

### 4.4 Portfolio operator

The developer running RelayForge locally or in the cloud. The operator deploys API and worker processes, observes health and backlog, and diagnoses failed delivery without modifying database rows manually.

## 5. Core vocabulary

- **Project:** ownership and authentication boundary for publisher resources.
- **API key:** project credential used only by publisher clients.
- **Endpoint:** destination URL, signing secret, enabled state, and exact event-type subscriptions.
- **Event:** immutable business fact accepted from a publisher.
- **Delivery:** the intent to send one event to one endpoint selected at event-acceptance time.
- **Attempt:** one durable dispatch cycle for a delivery. It normally performs one outbound HTTP request, but security validation may reject the destination before network I/O.
- **Replay:** an owner-requested new delivery for a previously exhausted delivery, linked to the original history.

These terms describe product behavior, not the future database schema.

## 6. Portfolio v1 use cases

### UC-01 - Authenticate as an owner

Portfolio v1 uses bootstrap owner accounts created through environment configuration or a controlled seed process. An owner can sign in, sign out, and access only resources owned by that account. Public registration, email verification, and password reset are not part of v1.

### UC-02 - Manage projects

The owner can create, view, and rename a project. Project deactivation is not part of v1 because it would require additional rules for publishing, in-flight attempts, scheduled retries, and reactivation.

### UC-03 - Manage publisher API keys

The owner can create, identify, and revoke project API keys. Raw key material is shown only when the key is created. A revoked key cannot publish new events.

### UC-04 - Manage subscribed webhook endpoints

The owner can create, view, update its name, destination URL and subscriptions, enable, and disable a project endpoint. Each endpoint subscribes to one or more exact event types. Wildcards, expression-based filters, and signing-secret rotation are not part of v1. The generated signing secret is immutable for the lifetime of a v1 endpoint.

Disabling an endpoint prevents new routing and pauses new attempts for its existing nonterminal deliveries. An already-started dispatch attempt may finish. Enabling the endpoint makes its paused deliveries eligible again.

### UC-05 - Publish an event idempotently

The publisher submits an event type, payload, and idempotency key for its project.

- The first valid request accepts one immutable event.
- Repeating an equivalent request with the same key returns the original logical result and creates no additional event or delivery.
- Reusing the key with different event content is rejected as a conflict.

### UC-06 - Fan out delivery intent

When an event is accepted, RelayForge takes a routing snapshot and creates one delivery for every enabled endpoint subscribed to the exact event type.

- Endpoint subscription changes after acceptance do not add or remove deliveries for the accepted event.
- A delivery retains the selected endpoint identity. Every new attempt atomically snapshots the endpoint URL configured when that attempt starts and uses the endpoint's immutable signing secret. Updating the URL does not change an already-started attempt.
- An event with no matching endpoint remains an accepted, queryable event with zero deliveries.

### UC-07 - Deliver asynchronously

A worker claims eligible delivery work without holding a database transaction during outbound HTTP. It sends a signed request containing stable event and delivery identifiers.

- Any HTTP 2xx response is a successful attempt.
- Redirects are not followed in v1.
- Delivery ordering is not guaranteed.

### UC-08 - Retry bounded transient failure

RelayForge retries network errors, timeouts, HTTP 408, HTTP 429, and HTTP 5xx responses with exponential backoff and jitter. An endpoint may opt into the bounded longer retry floor defined by ADR-012; it cannot make retries more aggressive or change the attempt budget.

- A delivery has at most five dispatch attempts, including the initial attempt. Destination security rejection can consume an attempt without sending an HTTP request.
- Every HTTP 4xx response except 408 and 429 is a permanent failure.
- If the fifth attempt has a retryable outcome, the attempt is recorded with that outcome and the delivery becomes exhausted without scheduling a sixth attempt.

### UC-09 - Inspect event and delivery history

The owner can inspect an event, its deliveries, and each attempt. Attempt history distinguishes successes, retryable failures, permanent failures, and exhausted delivery.

### UC-10 - Replay an exhausted delivery

The owner can request replay of an exhausted delivery using a replay idempotency key unique within the project. Replay creates a new delivery identifier linked to the original delivery, retains the original attempt history, keeps the same endpoint identity, and starts a fresh five-attempt budget. Each replay attempt follows the normal rule of atomically snapshotting the endpoint URL when that attempt starts.

- Repeating the same replay key for the same exhausted delivery returns the original replay result and creates no additional delivery.
- Reusing the replay key for a different original delivery is rejected as a conflict.

### UC-11 - Operate the system

The operator can determine whether the API and worker are healthy, inspect delivery backlog and attempt outcomes, and correlate application logs with project, event, delivery, and attempt identifiers where appropriate.

## 7. Product rules

1. A project has exactly one owner in Portfolio v1.
2. Ownership authorization is enforced by the backend, never only by hiding frontend controls.
3. Accepted events and their routing snapshot survive API or worker restart.
4. RelayForge provides at-least-once delivery and documents possible duplicate delivery.
5. RelayForge does not claim that a successful HTTP response proves the receiver committed its business transaction.
6. An automatic retry keeps the same event and delivery identifiers while creating a new attempt.
7. A manual replay keeps the event identifier but creates a new, linked delivery identifier.
8. A stale or expired worker claim must not overwrite the result of a newer claim.
9. Production endpoints must use public HTTPS addresses. A development profile may explicitly allow local HTTP receivers.
10. Passwords, raw API keys, signing secrets, tokens, and sensitive payloads must not appear in application logs.
11. Endpoint disablement pauses new attempts but cannot undo an HTTP request that already started.
12. Every production attempt must resolve and validate its destination immediately before connecting; the actual connection target must not be loopback, private, link-local, multicast, reserved, or cloud-metadata address space.
13. Every successful claim receives a unique claim token. Completion or failure updates are accepted only when the submitted token still identifies the delivery's current claim.
14. Endpoint URL changes apply to future attempts and replays, while historical attempts retain an auditable destination fingerprint. A v1 endpoint signing secret is immutable and never appears in history.

## 8. Explicit non-goals for Portfolio v1

- Inbound webhook gateway.
- Organization, team invitation, or complex RBAC.
- Exactly-once delivery.
- Strict global or per-endpoint ordering.
- Wildcard subscriptions or payload-based routing expressions.
- Arbitrary payload transformation or custom user code.
- RabbitMQ, Kafka, SQS, or another message broker without measured justification.
- Redis, distributed cache, or distributed lock.
- Multi-region delivery or disaster-recovery automation.
- Kubernetes or microservices.
- Billing, paid plans, or public SaaS onboarding. The fixed durable
  per-project daily publish quota in ADR-011 is the sole exception.
- Public account registration, email verification, password reset, or project deactivation.
- Signing-secret version history or zero-downtime secret rotation.
- A custom domain as a prerequisite for deployment.
- A production uptime SLA.

## 9. Portfolio v1 acceptance criteria

Sections 9.1 through 9.4 are the Core MVP gate. Section 9.5 is the Portfolio hardening gate.

### 9.1 Functional behavior

- **AC-F01:** A bootstrap owner can complete the project, API key, endpoint subscription, delivery-inspection, and replay workflow through the minimal dashboard; a documented publisher command or client can publish the event.
- **AC-F02:** Publishing one event creates exactly one delivery for each matching enabled endpoint and none for disabled or nonmatching endpoints.
- **AC-F03:** Publishing with no matching endpoint accepts a queryable event and creates zero deliveries.
- **AC-F04:** Retrying an equivalent publish request with the same idempotency key creates no additional event or delivery.
- **AC-F05:** Reusing an idempotency key with different content is rejected and leaves the original event unchanged.
- **AC-F06:** A successful 2xx attempt stops automatic processing for that delivery.
- **AC-F07:** A retryable failure schedules another attempt until success or the five-attempt limit.
- **AC-F08:** A permanent failure becomes terminal without consuming unnecessary retry attempts.
- **AC-F09:** Repeated or concurrent submission using one replay idempotency key creates one linked delivery, preserves the original history, keeps the original endpoint identity, and receives a fresh attempt budget. Its attempts snapshot the endpoint URL at their normal attempt-start boundary. Reusing that key for another original delivery is a conflict.
- **AC-F10:** Disabling an endpoint prevents new attempts after any already-started attempt completes; enabling it makes paused nonterminal deliveries eligible again.

### 9.2 Correctness and failure handling

- **AC-C01:** An accepted event and its expected deliveries remain queryable after API and worker process restart.
- **AC-C02:** A repeatable concurrency test runs multiple workers against the same eligible jobs without creating two valid active claims for one delivery.
- **AC-C03:** A worker crash after claim is recoverable after lease expiry without manual database editing.
- **AC-C04:** A test demonstrates the ambiguous-outcome scenario and confirms that duplicate HTTP delivery is allowed and documented.
- **AC-C05:** Every claim has a unique token, and a worker can persist completion or failure only through a conditional update matching the current token. Tests prove that a stale token cannot overwrite a newer claim's outcome.
- **AC-C06:** In a blocked-receiver integration test, the worker has committed its claim before waiting on HTTP, no claim transaction remains open, and another worker can claim unrelated eligible work.

### 9.3 Security

- **AC-S01:** Cross-owner access attempts are rejected for project, key, endpoint, event, delivery, and attempt resources.
- **AC-S02:** Revoked API keys cannot publish, and raw API keys are not recoverable from normal persistence or logs.
- **AC-S03:** Every outbound request contains a verifiable HMAC signature and timestamp.
- **AC-S04:** On every production attempt, RelayForge resolves the destination immediately before connecting and rejects any non-HTTPS scheme or loopback, private, link-local, multicast, reserved, or cloud-metadata target. Redirects are disabled, and tests cover a hostname whose resolution changes to a prohibited address.
- **AC-S05:** Automated checks confirm that representative logs contain no password, token, raw API key, signing secret, or full sensitive payload.

### 9.4 Engineering verification gate

- **AC-T01:** Pure retry, routing, and state-transition rules have unit tests.
- **AC-T02:** PostgreSQL-specific transaction, locking, idempotency, and claim behavior use Testcontainers rather than H2.
- **AC-T03:** Critical authentication, ownership, validation, and error mapping have API-level tests.
- **AC-T04:** Worker tests cover success, timeout, retryable failure, permanent failure, exhaustion, duplicate, crash recovery, endpoint pause/resume, and graceful shutdown.
- **AC-T05:** CI compiles the project and runs the required automated test suite before producing a backend image.

### 9.5 Portfolio hardening gate - maximum 16 hours

- **AC-O01:** Docker starts PostgreSQL, API, worker, a controllable demo receiver, and the minimum frontend needed for the core workflow.
- **AC-O02:** Structured logs allow an operator to correlate event, delivery, and attempt processing without exposing secrets.
- **AC-O03:** Metrics expose at least delivery backlog, attempt outcome counts, retry counts, worker throughput, and outbound latency.
- **AC-O04:** API readiness becomes unavailable when PostgreSQL is required but unreachable. During graceful shutdown, a worker stops claiming new work and either finishes active attempts within a configured deadline or leaves them recoverable by lease expiry.
- **AC-O05:** A temporary AWS-oriented environment has a documented create, health-verification, and destroy procedure; its stated demo-usage estimate does not exceed USD 20 per month and it has no Kubernetes dependency.
- **AC-O06:** A documented load workload reports throughput, error rate, p50, p95, and p99; no optimization claim is made without before/after evidence.
- **AC-O07:** At least one JFR or equivalent JVM profiling session is documented with its observation and conclusion.
- **AC-O08:** README, architecture documentation, ADRs, and a failure runbook are sufficient for a reviewer to reproduce the core demo and explain the major trade-offs.

## 10. Bounded resource defaults

- An event payload is at most 64 KiB before persistence.
- An attempt stores at most 8 KiB of receiver response-body preview and records whether truncation occurred.
- Terminal event, delivery, and attempt history is retained for 30 days in a running environment. Nonterminal work is never removed by retention cleanup.
- Exact cleanup scheduling is an implementation decision, but Portfolio v1 must demonstrate that expired terminal history can be removed safely.

These are conservative portfolio defaults, not claims about universal webhook-platform limits.

## 11. Downstream resolution of intentionally deferred choices

Focused Phase 0 documents now resolve the behavioral choices that did not belong in product requirements:

- owner dashboard authentication: `SECURITY_BASELINE.md`;
- retry, jitter, timeout, lease, polling, and concurrency defaults: `DELIVERY_RUNTIME_DEFAULTS.md`;
- HMAC canonicalization and security headers: `SECURITY_BASELINE.md`;
- delivery-history pagination and API errors: `API_CONTRACT.md`;
- conceptual persistence: `DATABASE_MODEL_PART1.md` and `DATABASE_MODEL_PART2.md`.

The concrete IP-pinning HTTP adapter, cloud/database topology, and numerical performance targets remain implementation/measurement decisions. Keeping those details out of this file preserves the separation between product requirements and technical design.

## 12. Follow-on product clarity contract (U1)

This section extends the portfolio presentation after the v1 core and does not
change delivery behavior, authentication, or the security model. Anonymous
visitors receive a public explanation of RelayForge. Owner projects and
operational data remain available only through authenticated dashboard APIs.

### 12.1 Audience and visitor questions

The primary anonymous visitor is a technical reviewer, recruiter, or engineer
who has not used RelayForge. The secondary visitor is an invited owner/operator
who needs to reach sign-in. The public page is not self-serve SaaS onboarding.

Without credentials, the page must answer:

1. What problem RelayForge solves and who uses it.
2. How an authenticated publisher event becomes durable delivery intent and a
   signed request to a subscribed receiver.
3. What "reliable" means here: PostgreSQL-backed intent, asynchronous delivery,
   bounded retry, inspectable history, and manual replay.
4. Which guarantees are deliberately absent: exactly-once processing, delivery
   ordering, an uptime SLA, and proof that a receiver committed its transaction.
5. What the visitor can do next: understand the architecture or sign in to an
   invited owner's private dashboard.

### 12.2 Truthful public promise

The approved headline is:

> Reliable outbound webhooks, visible from acceptance to final attempt.

The approved supporting statement is:

> RelayForge accepts publisher events, persists delivery intent in PostgreSQL,
> sends signed requests asynchronously with bounded retries, and gives
> authenticated owners safe history and replay.

Public copy may describe at-least-once delivery and the possibility of
duplicates. It must not describe RelayForge as exactly-once, ordered,
guaranteed real-time, production-ready multi-tenant SaaS, or covered by an SLA.

### 12.3 Public information architecture

The public landing page presents these sections in order:

1. A header with the RelayForge identity, in-page links to workflow,
   reliability, and architecture, plus a private sign-in action.
2. A hero containing the approved promise, supporting statement, a primary
   `Sign in to dashboard` action, and a secondary `See how delivery works`
   action.
3. A workflow explanation: publisher authenticates and submits an event;
   RelayForge persists the event and routing snapshot; a worker sends the
   signed request; the owner inspects attempts and may replay an exhausted
   delivery.
4. Three capability explanations: durable acceptance, secure signed dispatch,
   and bounded failure recovery.
5. An operator-visibility explanation covering safe event, delivery, and
   attempt history plus replay.
6. Architecture evidence using the accepted Java/Spring, PostgreSQL, and
   separate API/worker runtime model, alongside the material limitations.
7. A final private sign-in call to action.
8. A small footer that may link only to approved public repository or
   documentation locations.

The U2 first-owner path remains: create project, configure subscribed endpoint,
create publisher API key, publish a test event, then inspect its delivery. U1
may preview that path but must not implement onboarding or weaken one-time
secret handling.

### 12.4 Public/private boundary

The public surface may contain static product copy, generic architecture and
workflow diagrams, truthful aggregate evidence already approved for portfolio
use, and explicit limitation language. Rendering it must not require a
business-data API request.

The public surface must not contain or fetch owner credentials, bootstrap owner
identifiers, project/endpoint/event/delivery identifiers, event payloads, API
keys, signing secrets, session data, private history, or privileged health and
metrics URLs. It must not offer public registration, a shared demo account, or
anonymous mutation. Authentication and backend ownership checks remain the
authority for every private resource.

### 12.5 U1.1 acceptance and implementation handoff

U1.1 is accepted when this contract is recorded and is consistent with the v1
requirements and security baseline. U1.2 and later UI slices must prove that:

- the first viewport identifies the product, the core workflow, and the private
  sign-in action;
- a new visitor can restate the publisher-to-receiver flow after reading the
  page;
- at-least-once delivery, duplicate possibility, bounded retries, lack of
  ordering, and lack of SLA are represented accurately;
- unauthenticated page rendering performs no protected-data request and exposes
  no credential or owner data in the DOM, bundle, or browser network activity;
- the page uses semantic landmarks and heading order and remains usable by
  keyboard, at narrow viewport width, and at 200% zoom.

This contract does not approve a routing dependency, authentication change,
new API, analytics integration, public demo dataset, or delivery-runtime change.

## 13. First-owner onboarding contract (U2.1)

This contract makes the existing private owner workflow understandable without
changing the delivery protocol, identity model, API contract, or one-time
secret rules. It applies only after an authenticated owner reaches the existing
dashboard. It does not add public registration, shared/demo credentials,
persisted onboarding progress, a new endpoint, or an automatic publish.

### 13.1 State-derived guidance

Guidance is derived from successful owner-scoped reads and short-lived UI state;
it is not a durable workflow status. Loading, a failed request, an expired
session, and an incomplete paginated resource list are distinct operational
states and must not be presented as a missing resource or completed step.

There is no project deletion or project-list filter in v1. Therefore a
successful empty first page from `GET /projects` means the owner has no project
and is the first-project state. It is not a separate “returning owner with an
empty filtered list” case. For API keys and endpoints, the dashboard may say
that none exist only after a successful fully exhausted list; a non-null cursor
means it must load more or use neutral wording instead of claiming absence.

| Owner-visible state | Evidence required | Primary next action | Must not imply |
| --- | --- | --- | --- |
| First project | Owned-project list succeeds with no items | Create a project | That an account, project, or key was created automatically. |
| Project selected, no configured endpoint | Selected project's endpoint list succeeds and is exhausted with no items | Create a subscribed endpoint | That a later event will route before an endpoint is enabled. |
| Endpoints exist, none enabled | Endpoint list is complete and every endpoint is paused | Enable or create an enabled subscribed endpoint | That publishing will create a delivery. |
| Enabled subscribed endpoint exists, no usable raw key in hand | At least one enabled endpoint is visible; API-key metadata cannot reveal raw material | Create a publisher API key or paste an already-held key for this project | That a listed key can be copied/recovered. |
| Test event accepted with zero deliveries | Publish response has `deliveryCount: 0` | Inspect event type and enabled endpoint subscriptions | Receiver success or an eventual delivery. |
| Test event accepted with one or more deliveries | Publish response has `deliveryCount > 0` | Open Delivery history and inspect the asynchronous outcome | Synchronous receiver success, exactly-once processing, or ordering. |

The dashboard must keep an API/list error actionable in place (for example,
retry or refresh guidance) and must not overwrite it with onboarding copy. A
returning owner with existing projects begins from the selected project and sees
only the first unmet configuration step; completed steps stay compact rather
than forcing a linear wizard.

Within a selected project, the dashboard presents the first-success path as a
four-step walkthrough: **enabled endpoint**, **one-time API key**, **test
event**, and **delivery inspection**. It displays one current step with one
visually primary action and a short explanation; other steps remain visibly
pending or complete without competing primary actions. Opening a tab alone is
not progress. A step advances only after the relevant existing fact is known:
an enabled endpoint is read, an API key creation response succeeds, a publish
response succeeds, or the owner opens the existing Delivery view after a routed
publish. This transient guide state may live only in the selected-project React
tree and contains no raw key, signing secret, event payload, or credential.

U4.1 presentation: project selection uses a compact dialog with bounded scrolling
and existing pagination. The four workspace views precede optional guidance.
Existing projects open Deliveries with guidance collapsed; projects just created
open Endpoints with guidance expanded. Only the current step explains its action;
completed steps retain a compact label. Collapsing guidance never resets forms
or progress. Existing-key users can go directly to Test events; an accepted
publish also proves usable-key access for this walkthrough. A missing creation
fact must never be described as proof that the owner lacks a key.
Changing project resets project-local form, secret and walkthrough state;
renaming the same project does not. Guidance state remains transient.

The private workspace may represent only the selected project ID in the `/app`
query string so a reload restores the owner's context. The client validates that
ID through the existing owner-scoped paginated project read, requesting further
pages only while resolving that ID. It must not put raw material, form drafts,
guide progress, event or delivery selection in the URL; an unresolved ID falls
back to the first available owned project (or no selection when none exist).

### 13.2 Guided first-success path

The private dashboard guides one safe, optional local success path in this
order:

1. **Create project.** The owner supplies a project name; the existing owner
   session and CSRF-protected project API remain the authority.
2. **Configure endpoint.** The owner names a receiver, selects one or more
   event types, and enables the endpoint. The guide explains that only enabled
   matching subscriptions create deliveries. On successful creation, the
   signing secret is shown once so the owner can configure receiver verification
   before sending an event.
3. **Create publisher API key.** The owner assigns a display name and handles
   the one-time raw key. The guide explicitly says it will not be listed or
   recoverable later.
4. **Publish one test event.** The owner manually pastes that key into the
   existing password-type test input, chooses an event type subscribed by the
   enabled endpoint, and submits valid JSON. The dashboard generates the
   idempotency key for this one new-event action; a repeated request is an
   idempotency demonstration, not a second guided success step.
5. **Inspect delivery.** The owner follows the existing result handoff to
   Delivery history. A `202` acceptance proves durable acceptance and the
   number of created deliveries, not that the receiver has finished. History,
   existing REST polling, and the best-effort SSE invalidation hint expose the
   later outcome.

The path may show a deliberate “zero routes” experiment separately, but it is
never labeled the first delivery success path. It may point to the existing
local receiver guidance, but must not prefill an endpoint with a hidden shared
secret, mutate receiver configuration, or make an outbound request itself.

When a walkthrough CTA opens an existing workspace, the dashboard must bring
that workspace heading into view and move programmatic focus to it. This gives
both pointer and keyboard users an immediate, named destination; it is
orientation only and must not advance the guide by itself. Smooth scrolling
respects the user's reduced-motion preference.

### 13.3 Secret and ownership invariant

The guide must preserve the existing secret contract:

- raw API keys and endpoint signing secrets appear only in their successful
  creation responses and only in their existing component-local one-time
  presentation;
- no guide/progress object, query cache, route state, URL, browser storage,
  analytics event, error message, clipboard abstraction, or delivery-history
  record may retain either raw value;
- the dashboard must not automatically transfer a raw API key between the
  creation and test-event views; the owner explicitly pastes it, and the test
  input can be cleared;
- loss of a raw API key means create a replacement key; loss of an endpoint
  signing secret follows the existing disable-and-replace rule; and
- all guidance remains project-scoped and never converts owner UI state into
  publisher authorization. The existing API-key/path-project check remains the
  authority.

### 13.4 UI/API boundary and acceptance handoff

U2.2 through U2.4 may compose the existing project, endpoint, API-key, test-event, and
delivery-history calls and their response metadata. It must not add a
`firstRun` column, a progress API, a count endpoint, a privilege, an API client
store, or a backend mutation. A later slice may propose such a contract only if
state-derived guidance is demonstrably insufficient; that would be a material
product/data decision requiring owner approval.

U2 onboarding acceptance must prove:

- a successful empty project list offers project creation, while loading/error
  states remain visibly distinct;
- the next recommendation changes only after the required successful,
  owner-scoped data is available, including pagination safety for absence
  claims;
- no-enabled-endpoint and zero-delivery outcomes explain why they are not a
  delivery success;
- raw key/signing-secret values never appear in a list, query cache, route,
  browser storage, or after their one-time view is dismissed; and
- a project with a valid enabled route can complete the existing test publish
  and delivery-inspection handoff without changing publisher, worker, or SSE
  semantics; and
- each current-step CTA visibly reveals and programmatically focuses its named
  existing workspace, while opening the workspace alone leaves guide progress
  unchanged.

## 14. Owner delivery-health observation

The private Delivery workspace may show one safe, project-scoped aggregate
observation to orient an authenticated owner before they drill into paginated
event history. It distinguishes work due at enabled endpoints, persisted future
retries, in-flight claims, backlog paused by an endpoint disablement, and
retained exhausted deliveries. This does not change the delivery state machine,
create a worker command, expose operator telemetry, or promise that an
individual delivery can be claimed immediately: local worker admission and an
endpoint circuit may still defer due enabled work.

The observation is owner-authorized, REST/PostgreSQL-authoritative, and
aggregate-only. It must expose no event, delivery, endpoint, destination,
payload, token, secret, receiver, or global worker metric data. SSE can only
invalidate it for a REST refetch; five-second polling remains the dashboard's
recovery path. Individual delivery inspection and manual replay remain in the
existing history workflow.

The UI must say that an all-zero observation means no delivery currently needs
attention in the five counted categories; it does not mean that the project has
no succeeded or permanently failed outcomes. It must give concise meaning for
the existing owner-visible `PENDING`, `CLAIMED`, `RETRY_SCHEDULED`, `PAUSED`,
`SUCCEEDED`, `FAILED_PERMANENT`, and `EXHAUSTED` statuses. A paused delivery
may hand off to the existing Endpoints view because enabling its endpoint is an
available action. An exhausted delivery remains replayable only from its
individual history detail; this presentation must not create a bulk command,
new API, or state transition.

## 15. Private-dashboard data-state presentation

The private dashboard distinguishes an initial read from a valid empty result,
a background refresh/pagination read, and a failed read. It must not present an
empty-state claim while the authoritative read is pending or unavailable.
When an initial/list read fails, the owner sees resource-specific context and
an in-place retry. If previously loaded records remain available, they remain
visible and the failure must say that they may be stale; it must not discard
them or replace them with onboarding advice.

The dashboard may use small code-native React/CSS primitives for neutral
loading, valid empty, recoverable read error, and bounded operation-result
presentation. Those primitives carry presentation only: they do not introduce
a toast service, client-side global state, automatic retry, browser storage,
API call, or delivery-domain policy.

Resource-specific meaning remains local. In particular, raw API keys and
signing secrets remain in their existing one-time presentation; event-publish
acceptance continues to state its accepted event/delivery count rather than a
receiver result; and replay, endpoint state, and delivery guidance retain their
existing individual actions and wording. Form validation remains attached to
its field. A submit missing a project API key must explain the problem and move
focus to that input rather than silently disabling the only submit action.

Routine refreshes do not announce the whole workspace. Targeted non-urgent
operation results may use a polite local status; untied read/mutation failures
may use an alert and remain visible until the owner acts or navigates away.

The private dashboard's visual baseline preserves readable text at 4.5:1 or
higher against its rendered surface, and input boundaries plus visible focus
indicators at 3:1 or higher against their adjacent surface. Keyboard-visible
focus covers links, buttons, inputs, textareas, summaries, and programmatic
workspace targets; forced-colors mode uses a system focus color. Decorative
motion is opt-in under `prefers-reduced-motion: no-preference`; reduced-motion
users retain immediate functional state feedback without transition or scale
motion.
