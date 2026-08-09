# Current Task

Status: Completed

## Goal

Start Phase 1 by turning the accepted RelayForge business-module boundaries into an executable Java package skeleton and architecture tests.

## Learning outcome

Understand how a modular monolith keeps capability dependencies enforceable at build time even though every module shares one Maven artifact and JVM.

## Scope

- Choose the backend base package and align the existing Spring Boot application identity.
- Create package anchors for `identity`, `project`, `endpoint`, and `delivery`.
- Add ArchUnit as a test-only dependency.
- Enforce the approved business dependency graph, cycle freedom, and cross-module public-API rule.
- Compile and run the backend test suite.

## Out of scope

- Runtime-mode selection or conditional Spring components.
- Module business interfaces, commands, DTOs, domain behavior, or persistence.
- Security, controllers, database migrations, JPA/JDBC, workers, Docker, and frontend work.

## Small implementation steps

1. Use `com.gialong.relayforge` as the product-specific base package while retaining personal ownership in the namespace.
2. Rename the generic Maven/application identity to RelayForge.
3. Add one behavior-free package marker per capability.
4. Add architecture rules that inspect compiled production bytecode.
5. Run focused architecture tests, then the complete backend test suite.
6. Record only verified results in project status.

## Test evidence

- Spring context still starts after the package/application rename.
- All four business capability packages are imported by ArchUnit.
- Only `endpoint -> project` and `delivery -> project, endpoint` are permitted between business modules.
- Business modules contain no dependency cycle.
- A cross-module dependency may target only the other module's `api` package.
- Types in an `api` package cannot depend on internal/persistence code or expose repository-named types.

## Definition of done

- `./mvnw test` passes with the architecture rules active.
- No production behavior or infrastructure concern was introduced.
- Project memory names the next small Phase 1 slice.

## Actual verification

- Focused `ModuleBoundaryTests`: 5 tests passed on Java 25.0.1.
- Full backend Maven suite: 6 tests passed, including Spring context startup.
- Independent code review found one P1 stale runtime application name; correction aligned it to `relayforge-backend`.
- Independent re-review returned `READY` with no remaining P0/P1.
- `git diff --check` passed.
- The Spring test stack emitted a non-failing Mockito/Byte Buddy warning about future dynamic-agent loading behavior; no Mockito configuration was added because this slice does not use mocking directly.

## Remaining scope

- No runtime-mode implementation, business contract, controller, persistence, security, worker, Docker, or frontend behavior exists yet.
- The default terminal still selects JDK 21; verification explicitly used the installed JDK 25 required by the project.

## Next task

Implement and test the explicit `relayforge.runtime=api|worker` startup contract without adding real API or worker behavior.
