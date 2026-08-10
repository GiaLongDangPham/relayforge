# Current Task

Status: Completed

## Goal

Remove the duplicate runtime security principal and use the existing `VerifiedOwner` identity result directly as the authenticated principal.

## Learning outcome

Recognize when two data types with the same shape represent a real boundary and when one is speculative duplication. Here, a framework adapter can safely carry the existing immutable public identity result without making identity depend on Spring Security.

## Scope

- Delete `OwnerAuthenticationPrincipal`.
- Return the existing `VerifiedOwner` instance as the successful authenticated principal.
- Update only the focused provider test and project progress records.

## Decisions and trade-offs

- `VerifiedOwner` remains identity's immutable, hash-free public result; using it as an opaque Spring Security principal does not introduce Spring types into `identity`.
- A separate principal type will be introduced only if authentication context needs data or behavior that differs materially from `VerifiedOwner`.

## Out of scope

- Any change to credential verification, generic failure behavior, authorities, filter chain, session, HTTP, database, or runtime composition.

## Test evidence required

- The successful authentication token uses the exact `VerifiedOwner` returned by the verifier as its principal, with null credentials and no authorities.
- Focused provider tests pass.

## Definition of done

- The duplicate principal type is gone and project memory explains the deliberate reuse.
- No authentication behavior changes beyond the principal object identity/type.

## Actual verification

- Focused JDK 25 `OwnerAuthenticationProviderTests` passed 3/3.
- `git diff --check` passed.
- Independent read-only review returned `READY` with no P0/P1 findings.

## Verified behavior

- The authenticated token principal is the exact immutable `VerifiedOwner` instance returned by `OwnerCredentialVerifier`.
- No duplicate runtime principal, Spring type in identity, or stale source/test reference remains.

## Remaining and limitations

- The provider alone does not authenticate HTTP requests; no `SecurityFilterChain` is configured.
- Sessions, CSRF, session-fixation protection, logout, and cookie policy remain deferred even though the Phase 0 baseline has selected them.
- Hash corruption or an unsupported stored encoding remains an operational error policy for a later slice.

## Next recommended slice

Implement the first HTTP/security configuration slice: an API-only filter-chain design that uses this provider, remains deny-by-default, and establishes no session/CSRF behavior until those are intentionally scoped.
