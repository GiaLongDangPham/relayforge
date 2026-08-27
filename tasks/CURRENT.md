# Current Task

Status: Completed — awaiting owner review

## Goal

Complete Group 12 as one cohesive local-operability milestone: build one backend image, start it separately in API and worker modes beside PostgreSQL, serve the React dashboard, provide a bounded demo webhook receiver, and prove the integrated workflow.

## Decisions

- API and worker use the same immutable backend image and differ only through `RELAYFORGE_RUNTIME` plus mode-specific bootstrap configuration.
- PostgreSQL remains the only durable source of truth. Compose uses a named volume so ordinary process/container restarts do not erase data.
- Local secrets come from the ignored repository-root `.env`; `.env.example` contains placeholders only, and Docker build contexts exclude every `.env` file.
- The worker shares the receiver fixture's network namespace. This makes `http://localhost:8081` genuinely loopback from the worker and preserves the accepted local-only HTTP/SSRF policy without adding a Docker-hostname exception.
- The receiver is a bounded Java fixture, not another RelayForge business service. It can return success, failure, or a slow response, retain at most 100 sanitized observations, and optionally verify the v1 HMAC after receiving the one-time endpoint secret through a local-only configuration endpoint.
- Container liveness/readiness beyond PostgreSQL startup and explicit smoke waiting stays in Group 13; Group 12 does not prematurely add Actuator or a worker management server.

## Out of scope

Cloud deployment, Kubernetes, CI/CD, production TLS, metrics/tracing, history retention, and changes to delivery semantics.

## Evidence required

- Docker Compose configuration resolves without embedding ignored secrets into images.
- Backend, frontend, and receiver images build.
- PostgreSQL, API, worker, frontend, and receiver start together.
- A smoke flow logs in, creates project/key/endpoint, configures receiver verification, publishes idempotently, and observes one signed successful delivery through the owner history API.
- Restart evidence proves API and worker use the same image and committed PostgreSQL state survives process restart.

## Verification evidence

- `docker compose config` resolved the Compose model with the ignored `.env` outside every build context.
- `docker compose build --pull` built the backend, frontend, and bounded Java receiver images.
- Focused JDK 25 worker regression passed: `DestinationAddressPolicyTests`, `AttemptDestinationResolverTests`, and `PinnedOutboundWebhookDispatcherTests`.
- The actual Compose stack started with PostgreSQL healthy, API/worker using the same backend image, and every application container running as a non-root user.
- `group12-smoke.ps1 -VerifyRestart` proved owner login and CSRF-protected mutations, API-key and endpoint creation, one-time signing-secret receiver configuration, idempotent publisher acceptance, one valid HMAC-signed successful delivery, and session/event persistence across API/worker restart.
- The frontend returned HTTP 200 from `/health`; browser acceptance reached the login screen with no console errors. Authenticated dashboard browser actions remain an owner-controlled review step because entering a password in browser automation requires an immediate confirmation.
