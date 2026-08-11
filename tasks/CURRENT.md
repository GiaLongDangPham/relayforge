# Current Task

Status: Complete

## Goal

Complete Group 2: the `project` capability, owner-scoped project access, rename conflict handling, and its owner-session HTTP adapter.

## Learning outcome

Model one mutable aggregate owned by an opaque UUID, then enforce ownership and optimistic concurrency consistently in the database, application use case, and browser API without leaking identity persistence into `project`.

## Scope completed

- Flyway V4 creates `projects` with its restrictive owner foreign key, bounded normalized name, optimistic version, database timestamps, and owner-list keyset index.
- The local JPA aggregate and direct JPQL adapter support create, owner-scoped find/list, rename, and keyset cursor pagination. Spring Data remains deferred.
- The public `project` contract returns immutable hash-free details and an opaque cursor; its application service owns short read/write transactions.
- API-only REST adapters expose authenticated owner create/list/get/rename routes, require the existing CSRF boundary for mutations, hide cross-owner resources as 404, and surface stale versions as 409.
- Focused Testcontainers evidence covers schema, persistence, HTTP security, and worker/API runtime separation.

## Decisions and trade-offs

- `project` refers to its owner only by UUID; there is no identity entity, repository, or credential dependency.
- The `project` service explicitly scopes every read and mutation by owner UUID plus project UUID. A foreign key alone cannot stop IDOR.
- Rename uses the client-supplied expected version. One concurrent update wins; a stale request receives a conflict instead of silently overwriting the winner.
- The cursor contains owner UUID plus the deterministic `(created_at, id)` position. It is opaque to clients but validated against the authenticated owner so it cannot be reused across owners.
- Database-only tests run in worker mode because an API runtime deliberately owns servlet security; HTTP behavior is separately proved in a servlet test.

## Out of scope

- Publisher API keys, endpoint configuration/subscriptions, event publication, delivery workers, frontend, cloud, and distributed infrastructure.

## Actual verification

- JDK 25 Testcontainers: `PostgreSqlFoundationTests` 18/18 and `ProjectCatalogIntegrationTests` 3/3 passed against PostgreSQL 17.10.
- JDK 25 focused Group 2 verification passed 13/13: `ProjectCatalogServiceTests` (2), `ModuleBoundaryTests` (7), `ApiRuntimeApplicationTests` (1), `WorkerRuntimeApplicationTests` (1), `OwnerBrowserAuthenticationIntegrationTests` (1), and `ProjectHttpIntegrationTests` (1).
- Independent code review returned `READY` with no P0/P1 findings; its non-container re-run passed 10/10. Docker was unavailable to that reviewer, so the container-backed results above remain the primary execution evidence.
- `git diff --check` passed. No full suite was run.

## Verified behavior

- PostgreSQL enforces project ownership, normalized bounded names, nonnegative version/default, timestamps, and the owner-list index.
- The application creates with a UUID, hides valid cross-owner UUIDs, lists without duplicate/unstable cursor results, and preserves the winning rename after stale conflict.
- The browser flow proves login/session ownership, CSRF protection, 404 cross-owner access, version increment on rename, and 409 stale write behavior through real HTTP.
- Worker mode remains non-web and excludes the API composition.

## Remaining and limitations

- No API-key lifecycle, endpoint or delivery behavior has been implemented.
- Cursor tampering and more detailed response validation remain generic 400 behavior; they do not add a separate error taxonomy in this slice.

## Next recommended slice

Begin Group 3 with publisher API-key creation and one-time secret return. It must first decide and document the selector/token format, peppered digest boundary, and revocation semantics.
