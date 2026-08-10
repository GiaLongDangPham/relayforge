# Current Task

Status: Completed

## Goal

Add an identity-owned credential-verification use case that reads owner credentials through JPA, verifies BCrypt outside the database transaction, and returns one indistinguishable invalid result for unknown login and wrong password.

## Learning outcome

Understand when ORM is the clearer persistence tool, how to keep CPU-heavy password verification outside a connection-holding transaction, why unknown users still receive dummy password work, and how public contracts avoid leaking hashes or account existence.

## Scope

- Add one identity public verifier contract returning either a verified owner identity or an empty invalid result.
- Reuse one canonical-login policy for bootstrap and verification.
- Add an identity-owned credential-read port and an internal record containing ID, canonical login, and password hash.
- Implement the read adapter with JPA/JPQL projection, not `JdbcTemplate`.
- Keep one short read-only application-owned transaction around only the credential query.
- Add an identity-owned password-verification port and BCrypt adapter.
- For unknown canonical login or malformed login, perform dummy BCrypt work before returning invalid.
- Copy the caller password for verification and clear only the internal copy in `finally`.
- Prove valid credentials, wrong password, unknown/malformed login, transaction placement, JPA lookup, dummy work, and absence of secret/hash in the public result.

## Decisions and trade-offs

- `Optional<VerifiedOwner>` is the complete public outcome: empty does not explain whether login lookup or password matching failed.
- Canonical unknown logins still query PostgreSQL; malformed logins skip the query but still perform dummy BCrypt work. This mitigates the dominant timing difference but does not claim perfectly constant-time end-to-end authentication.
- The password verifier receives an optional stored hash and always executes one BCrypt match, using an in-memory dummy cost-12 hash when the owner is absent.
- The dummy hash is generated once when the verifier adapter is constructed. This adds one BCrypt operation at process startup but avoids persisting or hard-coding a reusable encoded value.
- JPA returns a detached application projection rather than exposing `OwnerAccountEntity` or keeping a persistence context open during BCrypt.
- The caller remains responsible for clearing its input `char[]`; the use case clears its own defensive copy.

## Out of scope

- Spring Security `AuthenticationProvider`, filter chain, session rotation/persistence, CSRF, login rate limiting, HTTP endpoints, and error mapping.
- Password rotation, hash-cost upgrade-on-login, account lockout, audit events, or authentication metrics.
- Project data, another migration, or changes to bootstrap behavior.

## Test evidence required

- Valid mixed-case/padded login plus correct password returns owner ID and canonical login.
- Wrong password and unknown/malformed login return the same empty public outcome.
- Unknown and malformed logins still invoke the password verifier without a stored hash.
- Credential query runs inside a read-only transaction; BCrypt runs after it closes.
- The caller array remains unchanged while the internal copy is cleared.
- PostgreSQL integration proves the JPA projection reads the stored owner without changing its hash/version.
- Focused tests and the full JDK 25 Maven suite pass.

## Definition of done

- Independent review has no unresolved P0/P1.
- Project memory records verified ORM/security behavior, limitations, and the next bounded slice.
- No session/filter/HTTP behavior or unrelated persistence enters the slice.

## Actual verification

- Focused application/security/runtime tests initially passed 6/6.
- PostgreSQL/JPA integration tests passed 2/2 against `postgres:17.10-alpine`.
- The first full JDK 25 suite passed 50/50 before the hash-rendering hardening.
- After hardening the internal credential projection against hash-bearing `toString`, focused application plus integration tests passed 6/6.
- Independent read-only review of the hardened state returned `READY` with no P0/P1 findings.
- The final hardened-state JDK 25 suite passed 51/51 with zero failures, errors, or skips.
- `git diff --check` passed.

## Verified behavior

- Correct mixed-case/padded credentials return only owner ID and canonical login.
- Wrong password, unknown canonical login, malformed login, and null password all produce the same empty public outcome.
- Canonical unknown login performs the JPA query; absent/malformed credentials still execute the password-verifier dummy path.
- JPQL projects only ID, login, and hash into an internal explicit class; neither its default string form nor the public result renders the hash.
- The JPA read joins an application-owned read-only transaction; BCrypt runs after that transaction commits and releases its connection.
- Verification neither changes the stored hash nor increments the optimistic version.
- The caller's password array is unchanged; the service's defensive copy is cleared.

## Remaining and limitations

- Dummy BCrypt removes the large missing-hash cost difference but does not make database lookup plus password verification perfectly constant-time.
- The application contract is not yet connected to Spring Security, sessions, HTTP error mapping, CSRF, or login rate limiting.
- Hash corruption or an unsupported stored encoding is not treated as ordinary invalid credentials; operational handling remains for a later security adapter slice.

## Next recommended slice

Add a Spring Security authentication adapter that calls `OwnerCredentialVerifier`, returns a principal with owner ID/canonical login, maps empty verification to generic bad credentials, and adds no filter-chain/session/HTTP behavior yet.
