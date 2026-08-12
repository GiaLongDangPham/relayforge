# Current Task

Status: Complete

## Completed outcome

Group 4 implemented owner-managed webhook endpoint configuration: V6 creates the endpoint and exact-subscription tables; the endpoint module owns encrypted one-time signing material, configuration lifecycle, and owner-safe metadata; API mode exposes the CSRF-protected owner routes; worker mode remains non-web.

## Decisions applied

- Endpoint creation accepts an explicit enabled state so an owner can safely preconfigure a disabled receiver.
- Production accepts HTTPS only. Development local HTTP requires the explicit `RELAYFORGE_ENDPOINT_ALLOW_LOCAL_HTTP=true` flag and a literal loopback host.
- A 32-byte `whsec_` secret is returned only on creation and is stored as AES-256-GCM ciphertext using project/endpoint identifiers as authenticated additional data.
- A complete configuration replacement first conditionally increments the endpoint version, then replaces subscriptions in the same transaction. This fences subscription-only races as well as URL/name changes.
- Enable/disable is idempotent: a repeat desired state returns current metadata without consuming another version; an actual stale state transition returns 409.

## Evidence

- JDK 25 focused Docker/Testcontainers regression passed 42/42, including V1-V6 Flyway migration, real owner HTTP/CSRF behavior, worker non-web composition, encrypted-secret AAD checks, and concurrent subscription replacement with one winner and one conflict.
- Independent review found and then verified the fix for subscription-only optimistic-lock fencing. Final verdict: `READY`, no P0/P1 findings.
- `git diff --check` passed.

## Deliberately not implemented

Publisher event acceptance, idempotency keys, event routing, delivery records, worker claims, outbound HTTP/HMAC dispatch, retries, replay, KMS/cloud key custody, and frontend work.

## Next recommended outcome

Start one bounded `delivery` slice: publisher event acceptance with API-key authentication, request idempotency, and atomic initial routing to matching enabled endpoints. Do not add worker/HTTP dispatch yet.
