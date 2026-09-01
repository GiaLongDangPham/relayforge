# RelayForge dashboard

The dashboard is a deliberately small React + Vite client for the RelayForge owner API. It does not contain delivery rules or authentication tokens: PostgreSQL-backed server sessions remain owned by the backend.

For the complete PostgreSQL/API/worker/receiver/frontend stack, follow the repository [Local Docker Demo](../docs/LOCAL_DOCKER_DEMO.md). This directory's commands are for frontend-only development.

## Local development

1. Start the RelayForge API in `api` mode at `http://localhost:8080` with a bootstrap owner configured.
2. From this directory, run `npm run dev`.
3. Open the Vite address, normally `http://localhost:5173`.

The default API origin is `http://localhost:8080`. Set `VITE_API_ORIGIN` only when the API is served elsewhere, for example:

```text
VITE_API_ORIGIN=https://api.example.test npm run dev
```

The browser includes the HttpOnly `RF_SESSION` cookie with each API request. It obtains a fresh CSRF token immediately before an owner mutation; neither value is written to local storage.

## Dashboard scope

The dashboard is intentionally an operational demo rather than a second implementation of RelayForge rules. It supports project configuration, one-time API-key and endpoint-secret presentation, endpoint enablement/configuration, and owner history/replay inspection.

- TanStack Query caches only safe server metadata. Raw API keys and signing secrets are local component state, are shown once after creation, and are cleared when dismissed.
- The visible deliveries tab opens one credentialed `EventSource` to ADR-013's best-effort project SSE endpoint. Stream open/reconnect/error and valid delivery hints invalidate the existing history cache; they never write delivery state directly. Bounded REST polling stays enabled as the recovery path, and the stream closes when the tab/project/authenticated workspace unmounts. WebSocket remains out of scope.
- Payloads and receiver previews are rendered as ordinary React text. No dashboard view injects receiver-controlled content as HTML.
- Each replay button retains an idempotency UUID only in the open component instance, allowing a user to retry a failed browser request without requesting a second replay.
