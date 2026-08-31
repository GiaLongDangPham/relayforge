# RelayForge Documentation Index

Start with [AGENTS.md](../AGENTS.md), [Agent Context](AGENT_CONTEXT.md), and the active [task](../tasks/CURRENT.md). They explain how to work; they do not replace the sources below.

| Work area | Read |
| --- | --- |
| Product scope and acceptance criteria | [Requirements](REQUIREMENTS.md) |
| Architecture, modules, runtime modes, transactions | [Architecture Boundaries](ARCHITECTURE_BOUNDARIES.md), then relevant accepted [ADRs](adr/) |
| Delivery, retries, leases, worker capacity, timing, endpoint fairness, receiver retry hints, circuit-breaker behavior | [Delivery Model](DELIVERY_MODEL.md), [Runtime Defaults](DELIVERY_RUNTIME_DEFAULTS.md), [ADR-002](adr/0002-postgresql-backed-delivery-jobs.md), [ADR-007](adr/0007-work-conserving-endpoint-fair-dispatch.md), [ADR-008](adr/0008-bounded-retry-after-scheduling.md), [ADR-009](adr/0009-postgresql-endpoint-circuit-breaker.md) |
| Database/persistence | [Database Model Part 1](DATABASE_MODEL_PART1.md), [Part 2](DATABASE_MODEL_PART2.md) |
| HTTP API and DTO boundaries | [API Contract](API_CONTRACT.md) |
| Authentication, authorization, secrets, SSRF, redaction | [Security Baseline](SECURITY_BASELINE.md) |
| Local Docker Compose demo and end-to-end smoke flow | [Local Docker Demo](LOCAL_DOCKER_DEMO.md) |
| Operator health, metrics, logs, and failure diagnosis | [Operations Runbook](OPERATIONS_RUNBOOK.md) |
| Local load testing, Prometheus/Grafana, JVM diagnostics, and measured tuning | [Performance Runbook](PERFORMANCE_RUNBOOK.md) |
| Recorded local performance comparison point | [Performance Baseline](PERFORMANCE_BASELINE.md) |
| Local retry, timeout, crash, and exhaustion evidence | [Failure and Recovery Runbook](FAILURE_RECOVERY_RUNBOOK.md) |
| Isolated restore, image-compatibility, rollback, and DuckDNS procedures | [Recovery Drill Runbook](RECOVERY_DRILL_RUNBOOK.md) |
| Measured Group 18–19 failure and recovery evidence | [Resilience Evidence](RESILIENCE_EVIDENCE.md) |
| Portfolio summary, CV bullets, interview questions, and demo script | [Portfolio Playbook](PORTFOLIO_PLAYBOOK.md) |
| Temporary EC2 Compose deployment, host boundary, production Compose/Caddy configuration, and manual rollout | [EC2 Deployment Baseline](EC2_DEPLOYMENT_BASELINE.md), then [ADR-005](adr/0005-single-ec2-compose-deployment.md) |
| EC2 backup, guarded GitHub Actions release, rollback, and DuckDNS recovery | [Production Release Runbook](PRODUCTION_RELEASE_RUNBOOK.md), then [ADR-006](adr/0006-guarded-github-actions-ec2-release.md) |
| Project progress and review handoff | [Project Status](../PROJECT_STATUS.md), [Phase 0 Handoff](PHASE_0_HANDOFF.md) |

Use [AGENTS.md](../AGENTS.md#source-of-truth-policy) when sources appear to conflict. Keep decisions in ADRs and detailed rules in their named source document; do not duplicate them here.
