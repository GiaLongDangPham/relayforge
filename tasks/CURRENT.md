# Current Task

Status: Complete

## Goal

Complete Group 3: the project-owned publisher API-key lifecycle from secure creation through owner management and publisher credential verification.

## Learning outcome

Use a public selector to find exactly one high-entropy credential record, then verify only a peppered HMAC digest. This separates browser-owner authorization from machine-publisher authentication while never persisting a raw API key.

## Scope completed

- Flyway V5 creates `project_api_keys` with restrictive project ownership, unique public hint/digest, a fixed 32-byte digest constraint, and the project keyset-list index.
- `project` owns generation of `rf_live_<uuid>.<32-byte-base64url-secret>`, peppered HMAC-SHA-256 persistence, owner-scoped create/list/revoke, owner/project-bound cursors, and a hash-free publisher-verification result.
- API-only owner routes create, list, and revoke keys under a project. They reuse the established session and CSRF boundary; raw key material is returned only by successful create.
- Verification parses one token, reads one candidate by UUID, compares fixed-length digests with `MessageDigest.isEqual`, and returns the same empty outcome for malformed, missing, mismatched, or revoked credentials.

## Decisions and trade-offs

- `RELAYFORGE_API_KEY_PEPPER` is required outside tests and is never stored in PostgreSQL or Git. A test-only value exists only in test resources.
- API keys use HMAC instead of password hashing because their 32-byte secrets are randomly generated and the public UUID permits one-record lookup. The pepper limits the usefulness of an isolated database leak.
- Revocation writes a PostgreSQL timestamp conditionally; a repeat returns the same metadata and cannot reactivate the key.
- A lost create response leaves an unrecoverable credential record. The correct v1 action is revoke-and-replace.

## Out of scope

- The publisher event endpoint, `Idempotency-Key`, events, endpoints/subscriptions, delivery workers, frontend, cloud, and distributed infrastructure.

## Actual verification

- JDK 25 focused regression passed 36/36 with PostgreSQL 17.10 Testcontainers: material tests (2), database foundation (19), project catalog (3), API-key catalog (1), project/API-key HTTP flow (1), owner browser security (1), API composition (1), worker composition (1), and architecture rules (7).
- A preliminary run exposed a test-resource configuration overlay that dropped Hikari's UTC initialization; restoring the inherited baseline made the same integration suite pass. This was test configuration only, not an API-key behavior failure.
- Independent review returned `READY` with no P0/P1 and reran the non-container material, API-composition, and architecture checks 10/10. The Testcontainers evidence above remains the primary execution evidence.
- `git diff --check` passed. No full suite was run.

## Verified behavior

- Persistence contains a fixed-size digest rather than raw key material; invalid foreign keys, display names, and digest lengths are rejected by PostgreSQL.
- Owner A cannot create, list, or revoke keys for Owner B's project, while an empty owned project remains distinguishable from a cross-owner project at the REST boundary.
- The HTTP flow proves CSRF on key creation, raw key only in a 201 response, metadata-only list response, 404 cross-owner access, and repeated revoke preserving the original `revokedAt`.
- A valid raw key verifies to its project/key identity; an altered, malformed, or revoked token does not verify. API and worker composition still start in their intended modes.

## Remaining and limitations

- The verifier is deliberately not installed as a Spring Security bearer filter yet: there is no publisher event route for it to protect. The next publish use case will consume its public contract and enforce path-project matching with 403.
- Pepper rotation is deferred by the accepted baseline because V1 has no digest-version column.

## Next recommended slice

Begin endpoint configuration: first choose the development/local HTTP policy and then implement endpoint creation with immutable generated signing material plus at least one exact event-type subscription in one transaction.
