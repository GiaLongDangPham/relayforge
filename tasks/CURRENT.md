# Current Task

Status: In progress

## Goal

Restore the revoked publisher API-key invariant exposed by CI, then prepare the exact immutable Docker Hub release for manual EC2 Compose deployment without starting the production stack.

## Decisions

- Treat the CI failure as a regression of the revoke security invariant; retain a focused integration test rather than weakening its assertion.
- Use the existing immutable `ee51d6114a8b` backend and gateway release only; Slice 1 copies Compose configuration and the ignored environment file, validates it, and pulls images without starting containers.
- The owner runs the required EC2 commands in Termius because this agent has no SSH session to the instance.

## Out of scope

Opening 80/443, starting production containers, changing AWS networking, automatic CD, and unrelated Java behavior.

## Evidence required

- The narrow API-key integration test passes and proves a revoked raw key is rejected.
- EC2 Compose configuration validates and the two immutable image tags pull successfully without printing secrets or starting services.

## Verification evidence

- The original focused `ProjectApiKeyIntegrationTests` passed before and after the change with PostgreSQL 17.10 Testcontainers. The source checkout could not reproduce the reported CI failure, so the revoke assertion remains strict.
- `JpaProjectApiKeyStore.revoke` now uses a JPQL bulk update against the mapped entity, still uses database `CURRENT_TIMESTAMP`, and clears the persistence context before rereading the owner-scoped result.
- Local Docker rebuilt API and worker successfully; API readiness returned `{"status":"UP"}` and the unchanged dashboard returned HTTP 200 at `http://localhost:5173/`. The in-app browser controller is not available in this task, so no DOM/console assertion was performed.
- EC2 Slice 1 is pending a fresh immutable release tag because the previously pushed `ee51d6114a8b` image predates this source change.
