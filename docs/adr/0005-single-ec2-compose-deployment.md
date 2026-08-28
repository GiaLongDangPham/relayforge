# ADR-005: Temporary single-EC2 Docker Compose deployment

- Status: Accepted
- Date: 2026-08-28
- Supersedes: ADR-004

## Context

RelayForge needs one low-cost temporary cloud demonstration, not a highly
available production platform. The owner has selected an EC2 deployment in
Tokyo and has provisioned an Ubuntu 26.04 amd64 `t3.small` instance with a
20 GiB encrypted EBS root volume. Docker Engine and the Docker Compose plugin
are installed, and the EC2 security group permits SSH only from the owner's
current public IPv4 address.

The earlier Fargate/RDS/CloudFront topology was technically valid but created
too many AWS concepts before the owner could operate the system. The deployed
topology must retain the important RelayForge boundaries: one backend image,
separate API and worker processes, PostgreSQL as the source of truth, no public
database, and no secret in source control or an image.

## Decision

Run one temporary EC2 host using Docker Compose:

```text
Browser -- HTTPS --> Caddy container
                         |-- static React dashboard
                         `-- /api/* --> API container

Worker container ----> public webhook receiver
API container --------> PostgreSQL container
Worker container -----> PostgreSQL container
```

- Caddy is the only container that will publish host ports: HTTP 80 and HTTPS
  443 after a controlled domain is available. It serves the dashboard and
  reverse-proxies `/api/*` to the API over the internal Compose network.
- API and worker start from the same immutable backend Docker Hub image, with
  `RELAYFORGE_RUNTIME=api` and `RELAYFORGE_RUNTIME=worker` respectively. The
  worker has no published port. API runs Flyway and becomes healthy before the
  worker starts with Flyway disabled.
- PostgreSQL 17 runs in a container with a Compose-managed persistent volume on
  the EC2 EBS disk. It publishes no host port and accepts connections only from
  the internal Compose network. This intentionally trades managed backups and
  availability for a simpler learning deployment.
- CI will eventually build and push backend and dashboard images to Docker Hub
  with immutable Git-SHA tags. The Compose deployment selects an explicit tag;
  it must never deploy `latest`. The first deployment is operator-driven over
  SSH. Automated CD is deferred until the manual workflow and rollback are
  understood.
- Runtime secrets are provisioned only in an owner-controlled
  `/opt/relayforge/.env.production` on EC2, with restrictive owner permissions.
  They are not Docker Hub variables, GitHub source, image layers, or committed
  Compose files.
- AWS Security Group remains the public network boundary: SSH 22 from the
  owner's current IP only; HTTP 80 and HTTPS 443 open only when Caddy and a
  domain are ready. Ports 5432, 8080, and 8082 are never public. UFW is a
  second host layer, but Docker published-port behavior must not be relied on
  as the primary boundary.
- The instance is a temporary, single-host demo. It has no high availability,
  zero-downtime deployment claim, automatic database backup, or automatic
  failover. The owner stops or terminates it after the demonstration and
  deliberately handles any remaining EBS, Elastic IP, snapshot, or domain
  charges.

## Consequences

### Benefits

- Lets the owner learn Docker Compose, reverse proxying, TLS, Linux operations,
  Docker image delivery, persistence volumes, and rollback on a familiar EC2
  foundation.
- Retains the reliability-relevant API/worker/PostgreSQL process separation.
- Keeps the cloud environment small enough to inspect with ordinary Docker,
  systemd, and AWS EC2 tools.

### Costs and risks

- One host is a single point of failure. EC2 reboot, disk failure, or a bad
  Compose deployment can make dashboard, API, worker, and PostgreSQL
  unavailable together.
- PostgreSQL data is durable only to the EBS volume until an explicit backup is
  taken. A `docker compose down -v` or instance termination can destroy it.
- A GitHub Actions SSH deployment would require a deployment key and CI secret.
  It is deferred; the initial manual SSH deployment is more transparent.
- Caddy cannot obtain a publicly trusted certificate until a domain points to a
  stable public address. The system must not be called a production HTTPS
  deployment before that prerequisite is met.

## Alternatives considered

### ECS Fargate plus RDS, CloudFront, ECR, and Terraform

Superseded. It reduces host operations but adds AWS networking, IAM, registry,
managed database, edge, and infrastructure-as-code concepts at once. ADR-004
records that earlier decision and remains as historical context.

### One combined API and worker container

Rejected. It removes the existing runtime boundary and makes worker failure or
resource pressure directly affect owner-facing API behavior.

### Public PostgreSQL port for DBeaver

Rejected. Direct database inspection is not sufficient reason to expose the
system of record. Production Compose keeps PostgreSQL internal; a later
controlled tunnel can support operator inspection if needed.

### Docker Hub `latest` tag

Rejected. It does not identify exactly what was deployed and makes rollback
ambiguous. Git-SHA tags provide traceability.

## Evidence and revisit triggers

The deployment is complete only after a domain-backed HTTPS dashboard, API and
worker health, one signed delivery, logs/metrics, restart persistence, a
documented rollback, and a controlled teardown are demonstrated. Revisit this
decision when the system needs persistent availability, managed backups,
multiple hosts, or AWS-native deployment automation.
