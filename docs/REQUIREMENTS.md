# RelayForge Portfolio v1 Requirements

Status: Reviewed Phase 0 baseline
Last updated: 2026-08-09

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
