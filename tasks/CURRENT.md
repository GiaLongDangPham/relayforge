# Current Task

Status: Completed

## Goal

Implement the explicit `relayforge.runtime=api|worker` startup contract so one RelayForge artifact activates exactly one runtime composition.

## Learning outcome

Understand the difference between environment profiles and application roles, and how Spring configuration binding plus conditional configuration can make an invalid deployment fail before it serves traffic or claims work.

## Scope

- Model runtime mode as a bounded enum-backed configuration property.
- Reject missing and unsupported runtime values during context startup.
- Add behavior-free API and worker configuration markers selected by the property.
- Test valid API/worker modes, mutual exclusion, missing mode, invalid mode, and the main Spring context.

## Implementation choice

- Use constructor-bound `@ConfigurationProperties` for the required runtime value.
- Reject unsupported and noncanonical values in the immutable configuration constructor.
- Bind the raw value as a string and map only exact `api|worker` literals to the enum, preventing Spring's lenient enum conversion from disagreeing with property conditions.
- Enforce the non-null invariant in the immutable properties constructor so missing values fail too.
- Use `@ConditionalOnProperty` only for activating the matching composition marker; it does not validate the property by itself.
- Use `ApplicationContextRunner` for focused failure and conditional-bean evidence, while the existing `@SpringBootTest` proves component scanning in the real application.
- Treat Spring's normal property-source precedence as producing one effective scalar value; “ambiguous” means the effective value cannot select exactly one role, not that a lower-precedence source also declared the key.

## Out of scope

- API controllers, authentication, delivery polling, recovery, outbound HTTP, database access, health endpoints, Docker, and frontend behavior.
- Spring profiles as runtime role selectors.
- Runtime-specific thread pools, connection pools, or configuration beyond role selection.

## Test evidence

- `api` starts with API configuration present and worker configuration absent.
- `worker` starts with worker configuration present and API configuration absent.
- Missing `relayforge.runtime` fails with an actionable message.
- Unsupported runtime value fails binding and starts neither composition.
- The real application context starts in a deliberately specified test mode.
- Existing ArchUnit rules remain green.
- Business capabilities cannot depend on runtime composition, and runtime composition can target business capabilities only through their public API packages.

## Definition of done

- Full JDK 25 Maven suite passes.
- Independent code review has no unresolved P0/P1.
- No real API or worker behavior entered this slice.
- Project memory records the verified result and next bounded task.

## Actual verification

- Focused runtime suite passed 9/9 on Java 25.0.1: two real-application composition tests plus seven binding/selection cases.
- Final full Maven suite passed 16/16: nine runtime tests and seven architecture rules.
- Packaged JAR smoke tests: missing mode exited 1 with the actionable message; exact `api` and `worker` each exited 0; noncanonical `API` exited 1.
- Independent review found two P1 gaps: lenient enum binding could disagree with exact property conditions, and the original context test did not prove real component scanning.
- Strict literal parsing plus real API/worker `@SpringBootTest` coverage resolved both findings; re-review returned `READY` with no remaining P0/P1.
- A final architecture review found one P1 over-broad `..runtime..` selector; anchoring both rules to `com.gialong.relayforge.runtime..` resolved the false-fail risk and final re-review returned `READY`.
- `git diff --check` passed after all corrections.

## Remaining scope

- Runtime configurations are composition markers only; no owner/publisher endpoint or delivery worker component exists.
- Normal Spring property-source precedence chooses one effective scalar value. Lower-precedence duplicate declarations are not treated as ambiguity.
- The existing non-failing Mockito/Byte Buddy future dynamic-agent warning remains inherited from the Spring test stack.

## Next task

Establish the PostgreSQL persistence test foundation with a focused migration-tool decision, JDBC connectivity, and Testcontainers evidence before creating business tables.
