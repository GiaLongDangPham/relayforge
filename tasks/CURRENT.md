# Current Task

Status: Complete

## Goal

Complete Group 8: resolve and pin outbound destinations, perform one bounded signed HTTP request, and expose a worker-only dispatch result boundary.

## Decisions

- Slice 1 remains the existing UTF-8 body/HMAC construction boundary; it is refactored only as needed to expose a delivery public contract to the worker adapter.
- Apache HttpClient 5 will be used as a direct Java library, rather than adding infrastructure or a service. Its per-request DNS-resolver seam permits a validated selected address to be the actual socket target while the original target host remains available to normal HTTP/TLS handling.
- Every production dispatch resolves all answers before connecting, rejects the entire destination when any answer is prohibited, and uses the one selected validated address for the HTTP client connection. Explicit development local HTTP may connect only to a loopback destination.
- The worker-only dispatcher applies the 2-second connection timeout and 10-second outer deadline; it disables redirects, automatic retries, cookies, compression, and connection reuse. It produces an in-memory outcome but cannot touch delivery persistence.
- No worker scheduler starts in Group 8: no loop may create `STARTED` attempts until Group 9 adds conditional finalization.

## Out of scope

Attempt finalization, retry scheduling, post-attempt `UNKNOWN` recovery, worker polling/scheduling, replay, history endpoints, retention, cloud, and frontend work.

## Evidence required

- Existing Slice 1 known-vector and byte-lifecycle evidence remains valid.
- Address-policy tests prove forbidden IPv4/IPv6 classes and mixed public/prohibited DNS answers are rejected before any connection; a bound receiver fixture proves the client uses the selected address rather than resolving the host again.
- HTTP fixture tests prove exact body/identity/HMAC headers, 2xx/retryable/permanent observation, disabled redirect following, the bounded 8 KiB preview, and no persisted state mutation during dispatch.
- Worker runtime composition proves the HTTP dispatcher exists only in worker mode and remains an adapter over the `delivery` public contract.

## Verification evidence

- 2026-08-14: clean JDK 25 focused verification passed 19/19: message signing (3), API composition (1), destination resolution (2), address policy (2), pinned HTTP dispatch (4), and architecture boundaries (7). `git diff --check` passed.
- 2026-08-14: Docker/Testcontainers `WorkerRuntimeApplicationTests` passed 1/1. It proves the packaged worker remains non-web and composes exactly one outbound dispatcher while API composition exposes none.
- 2026-08-14: independent review initially found a P1 IPv4-compatible IPv6 SSRF bypass. The policy now normalizes both `::a.b.c.d` and `::ffff:a.b.c.d` before applying the IPv4 deny list; the regression test passed and re-review returned `READY` with no P0/P1.
