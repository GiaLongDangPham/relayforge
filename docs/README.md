# RelayForge Documentation Index

Start with [AGENTS.md](../AGENTS.md), [Agent Context](AGENT_CONTEXT.md), and the active [task](../tasks/CURRENT.md). They explain how to work; they do not replace the sources below.

| Work area | Read |
| --- | --- |
| Product scope and acceptance criteria | [Requirements](REQUIREMENTS.md) |
| Architecture, modules, runtime modes, transactions | [Architecture Boundaries](ARCHITECTURE_BOUNDARIES.md), then relevant accepted [ADRs](adr/) |
| Delivery, retries, leases, worker capacity, timing | [Delivery Model](DELIVERY_MODEL.md), [Runtime Defaults](DELIVERY_RUNTIME_DEFAULTS.md), [ADR-002](adr/0002-postgresql-backed-delivery-jobs.md) |
| Database/persistence | [Database Model Part 1](DATABASE_MODEL_PART1.md), [Part 2](DATABASE_MODEL_PART2.md) |
| HTTP API and DTO boundaries | [API Contract](API_CONTRACT.md) |
| Authentication, authorization, secrets, SSRF, redaction | [Security Baseline](SECURITY_BASELINE.md) |
| Local Docker Compose demo and end-to-end smoke flow | [Local Docker Demo](LOCAL_DOCKER_DEMO.md) |
| Project progress and review handoff | [Project Status](../PROJECT_STATUS.md), [Phase 0 Handoff](PHASE_0_HANDOFF.md) |

Use [AGENTS.md](../AGENTS.md#source-of-truth-policy) when sources appear to conflict. Keep decisions in ADRs and detailed rules in their named source document; do not duplicate them here.
