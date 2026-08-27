# Current Task

Status: In progress

## Goal

Add a frontend-only Test events publisher simulator for the selected RelayForge project: accept a user-pasted project API key only in component memory, publish through the existing publisher API, and lead the owner to the existing delivery history without changing API contracts or delivery behavior.

## Decisions

- Keep the existing React/Vite CSS-module architecture, TanStack Query only for safe endpoint metadata/history invalidation, and no new dependency.
- Send publisher requests with `Authorization: Bearer`, `Idempotency-Key`, `cache: no-store`, and `credentials: omit`; never use the owner CSRF mutation helper for publisher authentication.
- Extend the dashboard-origin CORS header allowlist with `Authorization`, which is already required by the publisher API contract; this permits browser preflight without changing authentication or delivery behavior.
- Raw API keys live only in the mounted Test events component state. They are never put in a query, URL, storage, log, result, error, or last-request record.
- Fetch safe endpoint metadata across its cursor pages to present the complete subscription union, but never create, enable, or alter endpoints from this tab.
- Follow-up UI refinement keeps the selected project as compact context, moves low-frequency project settings behind a native disclosure, puts the primary Test events command before optional guidance, and labels the delivery inspection sequence without altering data or controls.

## Out of scope

Cloud deployment, Kubernetes, CI/CD, production TLS, metrics/tracing, history retention, API-contract changes, automatic endpoint configuration, and changes to delivery semantics.

## Evidence required

- Frontend lint and production build pass.
- The rebuilt Compose frontend serves the changed source at localhost.
- Browser checks cover missing/cleared key, invalid JSON, endpoint subscription guidance, success result, delivery handoff, repeat-idempotency, responsive layout, and a clean console.

## Verification evidence

- `npm run lint` and `npm run build` passed; the frontend Compose image was rebuilt and restarted without dependencies.
- Browser verification passed for the Test events missing-key, invalid-JSON, endpoint-guidance, project-change reset, narrow-viewport, and clean-console states. Publisher acceptance/replay remains pending a user-provided raw project API key.
- `OwnerBrowserAuthenticationIntegrationTests` passed after its CORS preflight now requests and verifies `Authorization`, `Content-Type`, and `Idempotency-Key`.
- The rebuilt local API answered a real publisher-route preflight from `http://localhost:5173` with `200` and those three explicitly allowed request headers.
- UI refinement lint and production build passed. Rebuilt local frontend browser review confirmed the compact project context; clear Endpoints, Test events, and Deliveries hierarchy; no console warnings/errors; and no horizontal overflow in the narrow-viewport check.
