# Current Task

Status: Complete

## Goal

Complete Phase 3 Slice 4.4: use the existing owner SSE stream only as a
visible-Delivery-workspace TanStack Query invalidation hint.

## Decisions

- The dashboard opens at most one credentialed `EventSource` for the visible
  selected project and closes it on workspace/project teardown, including
  logout.
- A stream open, reconnect/error, or valid `delivery.changed` message only
  invalidates active project history query keys; it never mutates cached
  delivery state from the hint.
- Existing five-second REST polling remains enabled and is the recovery path
  when SSE is unavailable or lossy.

## Out of scope

Backend contract/bridge changes, migrations, Redis/broker, delivery-state
machine changes, WebSocket, ordering, RBAC, a connection-status UI,
production/EC2 measurements, and performance/capacity claims.

## Evidence required

- Only the mounted Delivery workspace creates a stream, and cleanup closes it
  when the selected project/workspace is gone.
- Valid hints/open/reconnect/error invalidate the existing history queries;
  malformed or wrong-project messages do not change cache state.
- Frontend lint/build pass, the existing backend bridge regression remains
  green, and the rebuilt local dashboard smoke has no console errors.

## Completion evidence

- `DeliveryOperations` mounts the sole `EventSource` only while its Delivery
  tab and selected project are visible; React cleanup closes it on tab,
  project, or authenticated-app teardown.
- Stream open, error/reconnect, and validated same-project `delivery.changed`
  messages invalidate only the pre-existing history query families. The hook
  rejects malformed or wrong-project message data and never writes a delivery
  state from SSE.
- Frontend lint and TypeScript/Vite production build passed. The rebuilt local
  dashboard reached its sign-in screen with no browser console warning/error;
  the committed backend SSE bridge remains covered by Slice 4.3's focused
  Testcontainers HTTP evidence.

## Next action

Phase 3's polling/SSE initiative is complete. Select the next evidence-gated
Phase 3 initiative only when its own scope and trade-offs are reviewed.
