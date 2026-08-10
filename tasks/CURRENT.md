# Current Task

Status: Completed

## Goal

Add a controlled, disabled-by-default startup adapter that invokes the existing owner-bootstrap use case only in API runtime mode without exposing the bootstrap password.

## Learning outcome

Understand mode-specific Spring composition, startup hooks as inbound adapters, fail-fast configuration, the limits of secret erasure in Java, and how selective Lombok use can remove mechanical code without hiding security or transaction behavior.

## Scope

- Add an API-mode-only startup runner behind `relayforge.bootstrap.owner.enabled=true`.
- Read the configured login and password only when the runner executes.
- Convert the configured password to a temporary `char[]`, invoke `identity.api.OwnerBootstrap`, and clear the array in `finally`.
- Log only the bootstrap outcome and owner ID.
- Add Lombok as a compile-time annotation processor compatible with JDK 25 and use it for constructor/logger boilerplate.
- Add an enforceable Lombok policy: reject `@Data` and `@SneakyThrows`; warn on generated setters, `toString`, and equality methods that require review.
- Prove opt-in composition, API-only behavior, invocation, temporary-array clearing, and representative log redaction.

## Decisions and trade-offs

- Missing `enabled` means disabled. Enabling bootstrap without login or password fails startup rather than silently starting without the requested owner.
- The runner is an inbound runtime adapter and may depend only on the identity public contract, not its service, entity, or repository.
- Spring configuration values originate as immutable strings. Clearing the temporary `char[]` reduces accidental retention but cannot erase the original environment/property value or internal JVM allocations.
- Login is not a password, but this startup log does not need it; outcome and owner ID are sufficient operational evidence.
- Lombok is for mechanical boilerplate only. Java records remain preferred for simple immutable results, while transaction boundaries, validation, domain mutations, and concurrency SQL stay explicit.

## Out of scope

- Dashboard authentication, password verification or rotation, Spring Security filters, sessions, CSRF, and HTTP endpoints.
- Cloud secret manager integration, property encryption, Actuator environment sanitization, or Docker configuration.
- Project data, another migration, or changes to the race-safe bootstrap transaction.
- Retrofitting every existing class with Lombok.

## Test evidence required

- Disabled API configuration creates no startup runner.
- Enabled API configuration creates exactly one startup runner.
- Worker mode cannot create the runner even if the bootstrap flag is true.
- The runner invokes `OwnerBootstrap` with the configured values, clears its temporary password array, and logs no seeded password marker.
- Missing required credentials fail without echoing a configured secret.
- Focused tests and the full JDK 25 Maven suite pass.

## Definition of done

- Independent review has no unresolved P0/P1.
- Project memory records the verified startup behavior, Lombok policy, limitations, and next bounded slice.
- No authentication/session/HTTP behavior or unrelated persistence enters the slice.

## Actual verification

- Focused composition and runner tests passed 7/7 before the database startup test was added.
- Focused startup tests passed 6/6, including a real `postgres:17.10-alpine` application start and restart.
- The first start produced `CREATED`; restart with a different configured password produced `EXISTING`, retained one row, and preserved the original BCrypt hash.
- Captured logs contained safe outcome/owner ID fields but neither configured password marker nor the supplied login.
- Missing login and missing password each fail with the missing property key and without echoing a configured secret.
- The full JDK 25 Maven suite passed 44/44 with zero failures, errors, or skips.
- Independent read-only review returned `READY` with no P0/P1 findings.
- `git diff --check` passed.

## Remaining and limitations

- Spring `Environment` retains configuration values as immutable strings; clearing the runner's temporary `char[]` cannot erase that original value or every internal allocation.
- Supply the three enabled bootstrap values through environment/ignored configuration; do not commit the password or pass it as a visible command-line argument.
- Lombok 1.18.46 supports JDK 25 and is compile-time only, but javac currently emits its non-failing terminally-deprecated `sun.misc.Unsafe` warning.
- Dashboard authentication, password verification, sessions, HTTP, rotation, cloud secret management, and Actuator sanitization remain unimplemented.

## Runtime configuration

- `RELAYFORGE_BOOTSTRAP_OWNER_ENABLED=true`
- `RELAYFORGE_BOOTSTRAP_OWNER_LOGIN_NAME=<canonicalizable-login>`
- `RELAYFORGE_BOOTSTRAP_OWNER_PASSWORD=<secret>`

## Next recommended slice

Add an identity-owned credential lookup and password-verification use case using JPA for the canonical-login read, BCrypt verification behind a port, indistinguishable invalid-credential results, and no Spring Security/session/HTTP wiring yet.
