# Current Task

Status: Completed

## Goal

Perform the first owner-led dashboard acceptance workflow at the live HTTPS origin: create production configuration, publish one routed event, and inspect its asynchronous result.

## Decisions

- The owner explicitly approved dashboard mutations for this acceptance run.
- Start from the already authenticated owner session and use only the dashboard rather than exposing internal service ports.
- Use one public HTTPS receiver returning HTTP 204, with one subscribed event type, to demonstrate the normal successful path without exercising failure/retry/replay behavior.

## Out of scope

Failure/retry/replay behavior, database inspection, production data cleanup, automatic CD, and unrelated Java behavior.

## Evidence required

- The live dashboard loads as the existing owner without browser console errors.
- The dashboard can read the owner-scoped project list over the public HTTPS route.
- The accepted event creates one delivery and the worker's terminal result is visible in the owner history.
- The session, configuration metadata, event, and delivery survive a dashboard reload.

## Verification evidence

- Read-only browser acceptance passed at `https://gialong.duckdns.org`: the existing `owner` session loaded, the owner-scoped project list returned empty as expected for a new deployment, and no browser console error was captured after reload.
- Created the `Production Acceptance` project, a publisher API key, and one enabled `HTTPBin 204 Acceptance` endpoint subscribed to `invoice.paid`. One-time raw key and signing-secret values were redacted from acceptance output and were not copied outside the browser flow.
- The dashboard published one `invoice.paid` event with the new publisher key. It was accepted with one routed delivery; the worker completed one attempt with HTTP 204 and terminal `SUCCEEDED` status.
- A subsequent dashboard reload retained the owner session and exposed only API-key metadata, not the one-time raw key. No browser console error was captured during the workflow.
- The verified production Compose hardening and deployment evidence were recorded in local Git; this did not rebuild an image, push a release, or change the EC2 runtime.
