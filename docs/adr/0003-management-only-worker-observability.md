# ADR-003: Management-Only Worker Servlet Surface

- Status: Accepted
- Date: 2026-08-27
- Decision owners: RelayForge project

## Context

Portfolio v1 requires an operator to inspect API and worker health, backlog, attempt outcomes, capacity, and outbound latency. The original worker runtime was non-web, so it could not be scraped directly. Routing such signals through the API would obscure a failed worker and would couple two independently operated processes.

## Decision

Both modes use a servlet context. API mode serves its existing business routes plus the selected management endpoints. Worker mode has no business controllers, session repository, or owner/publisher security adapters; it exposes only `health` and `prometheus` beneath `/actuator`.

Worker security has an ordered management chain that permits health probes and Prometheus scraping, followed by a deny-all fallback. Docker Compose gives the worker internal port `8082`; production networking must permit that surface only from the platform health checker and metrics collector.

Micrometer metrics are exported through Prometheus. Metrics use only bounded state/outcome tags. Delivery identity and customer data remain in structured logs, never metric tags. API backlog gauges read a short cached delivery-owned snapshot rather than querying PostgreSQL for every scrape.

## Alternatives considered

1. Keep the worker non-web and expose no worker health/metrics: rejected because worker failure would be inferred indirectly.
2. Proxy worker signals through the API: rejected because API availability says nothing about worker availability and creates an unnecessary process dependency.
3. Add a separate management application or sidecar: rejected because it adds deployment components without improving the source-of-truth model.

## Consequences

- Operators can probe and scrape API and worker independently.
- The worker has a small HTTP attack surface, so network restriction and the deny-all chain are mandatory.
- No Grafana, Prometheus server, ELK, tracing backend, or dashboard metrics UI is added in this slice; the endpoints are the integration contract.
- Restarting a worker stops claiming new work through its lifecycle and leaves any unfinished lease recoverable under the existing delivery rules.

## Verification

The worker composition test starts PostgreSQL, verifies readiness and Prometheus output over HTTP, and verifies a business API request is forbidden. Compose acceptance verifies both runtime ports after rebuilding the shared image.

## References

- [Architecture Boundaries](../ARCHITECTURE_BOUNDARIES.md)
- [Runtime Defaults](../DELIVERY_RUNTIME_DEFAULTS.md)
- [Security Baseline](../SECURITY_BASELINE.md)
