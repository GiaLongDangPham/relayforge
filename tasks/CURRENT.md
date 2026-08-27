# Current Task

Status: Waiting for owner review

## Goal

Complete every remaining Group 11 dashboard slice: project-scoped API-key management, endpoint configuration, event/delivery/attempt inspection, and idempotent exhausted-delivery replay.

## Decisions

- The dashboard uses React + Vite + TypeScript and calls the API origin from `VITE_API_ORIGIN`, defaulting to local API development at `http://localhost:8080`. It relies on the backend's explicit credentialed CORS allowlist rather than adding a frontend authentication mechanism.
- The browser never reads or stores the server-side session identifier. Every request uses `credentials: "include"`; the client requests a fresh CSRF token before each owner mutation and keeps it only for that request.
- Slice 1 intentionally models authentication as a small local React state machine instead of adding routing or global state dependencies before multiple actual screens exist.
- TanStack Query is now justified for REST-backed project pages and mutations. It owns cached server state and refetching after create/rename; selected-project UI state remains local React state, so Zustand is not introduced.
- Deprecated React `FormEvent` uses `SubmitEvent`; the active deprecated publisher APIs use their Spring/Jackson replacements without changing HTTP behavior.
- Raw API keys and endpoint signing secrets are creation-only local component state. They are never put in TanStack Query, browser storage, logs, or a URL; closing their one-time reveal clears the component state.
- The dashboard uses existing bounded REST polling/refresh controls rather than SSE/WebSocket. A replay keeps one `crypto.randomUUID()` idempotency key in component memory so an explicit retry after a transient browser failure represents the same command.

## Out of scope

Frontend routing, retention, metrics/health, cloud, and Docker.

## Evidence required

- Frontend build and lint evidence proves all Group 11 TypeScript/React screens compile and lint correctly.
- Manual local browser evidence will prove one-time secret handling, API-key revocation, atomic endpoint configuration, owner history isolation, bounded attempt display, CSRF, and replay idempotency against the existing API.

## Verification evidence

- `npm run lint` passed.
- `npx tsc -b` passed.
- `npm run build` passed (TypeScript plus Vite production bundle).
- Docker/Testcontainers verification passed: `DeliveryHistoryReplayIntegrationTests` (2/2) and `DeliveryHistoryHttpIntegrationTests` (1/1).
- Manual browser acceptance is intentionally deferred to Group 12 because the repository does not yet provide a local PostgreSQL/API/worker/receiver demo stack. It is not a blocker for the completed Group 11 implementation.
