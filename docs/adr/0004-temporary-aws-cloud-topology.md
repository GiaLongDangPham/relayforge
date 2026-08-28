# ADR-004: Temporary AWS cloud topology

- Status: Superseded by ADR-005
- Date: 2026-08-28

## Context

RelayForge Portfolio v1 must demonstrate one low-cost, temporary AWS-oriented
environment without Kubernetes or high availability. The system already has one
backend image with explicit `api` and `worker` runtime modes, PostgreSQL is the
source of truth, and the frontend is a thin owner dashboard.

The cloud topology must preserve the existing process boundary, keep PostgreSQL
off the public internet, inject secrets without committing them, and make a
short-lived demonstration affordable. A custom domain is explicitly not a
Portfolio v1 prerequisite.

## Decision

Use one temporary AWS environment in `ap-southeast-1` with this topology:

```text
Browser -- HTTPS --> CloudFront default hostname
                         | /          \
                         | /api/*      \ static dashboard
                         v              v
                    public ALB         private S3 bucket
                         |
                         v
                  ECS Fargate API task (same RelayForge image)

ECS Fargate worker task (same RelayForge image) ---> public receiver Internet
           |                         |
           +-----------+-------------+
                       v
              private RDS PostgreSQL
```

- CloudFront supplies browser-facing HTTPS using its generated hostname. It
  routes `/api/*` to the API ALB and all other paths to the private S3 frontend
  origin. The ALB accepts traffic only from the AWS-managed CloudFront
  origin-facing prefix list.
- API and worker are separate Fargate services, each with desired count one,
  but use the same immutable backend image tag and distinct
  `RELAYFORGE_RUNTIME` values. They never call each other over HTTP.
- RDS PostgreSQL is single-AZ, non-public, and reachable on port 5432 only from
  the API and worker task security groups. It is a deliberately non-HA demo
  database, not a production availability design.
- Fargate tasks use public subnets and public IPs only to avoid a NAT Gateway
  in the temporary demo. Security groups admit no direct Internet ingress to
  either task: the API accepts only the ALB and the worker accepts none. This
  is a bounded cost trade-off, not the preferred production subnet design.
- CloudFront-to-ALB traffic is HTTP for this no-custom-domain demo. Browser
  traffic remains HTTPS and the application has secure cookies enabled. A later
  custom domain plus ACM certificate is the upgrade path for HTTPS to the ALB
  origin as well.
- API is the only migration owner: it runs Flyway before its service becomes
  healthy; worker tasks set `SPRING_FLYWAY_ENABLED=false` and are deployed only
  after API readiness. This avoids two runtime modes competing to define the
  deployment migration boundary.
- Secrets live in AWS Secrets Manager and are injected by the ECS task
  execution role. The application task role has no AWS permissions in v1.
  The RDS master password is AWS-managed; the API-key pepper and endpoint
  encryption key are pre-created secret values, never Terraform variables.
- Logs go to bounded-retention CloudWatch log groups. Worker management health
  remains private and is inspected with ECS Exec only during the controlled
  demo; it is not routed through CloudFront or the ALB.

The environment is created for a controlled demo window and destroyed after
verification. The cost target is USD 5-15 for a single eight-hour demo, with a
hard owner budget alert at USD 20. This is a planning estimate, not a pricing
guarantee; the AWS Pricing Calculator must be checked immediately before the
first apply.

## Consequences

### Benefits

- Demonstrates container deployment, managed PostgreSQL, IAM, secret custody,
  HTTPS at the browser edge, logs, and separate API/worker operations without
  Kubernetes.
- Preserves ADR-001: one build artifact, one image, separate processes.
- Makes database exposure and runtime-secret boundaries explicit and testable.
- Avoids recurring NAT Gateway and high-availability costs during a portfolio
  demonstration.

### Costs and risks

- Public-IP Fargate tasks have a larger egress/reachability surface than private
  tasks behind a NAT Gateway. Security groups prevent inbound public access but
  do not make this a production network posture.
- CloudFront-to-ALB HTTP is a no-domain compromise. Do not claim end-to-end
  origin TLS until a domain and ACM certificate are added.
- RDS, ALB, Fargate, CloudFront, log storage, and ECR can incur charges while
  they exist. Budget alerts do not stop spending, so destroy is an operational
  step, not optional cleanup.
- A worker deployed before API migration readiness can fail validation. The
  deployment procedure must preserve API-before-worker ordering.

## Alternatives considered

### Kubernetes

Rejected. It adds a cluster, control-plane and networking complexity without a
measured scaling or orchestration need.

### One combined API/worker Fargate task

Rejected. It would reverse the accepted runtime isolation and couples worker
failure or scaling directly to the dashboard API.

### Private Fargate tasks with NAT Gateway

Deferred. It is the stronger standard posture, but NAT hourly and data charges
are disproportionate for a single short-lived portfolio demo. Revisit when the
environment becomes persistent.

### Public RDS for easier database inspection

Rejected. DBeaver access is not sufficient reason to expose the system of
record. Use controlled AWS access paths or a local restored copy instead.

### Custom domain before any cloud work

Rejected. Requirements explicitly keep a custom domain out of the deployment
prerequisites. CloudFront's generated HTTPS hostname is sufficient for the
temporary demonstration.

## Evidence and revisit triggers

The cloud deployment is complete only after a documented create, health,
delivery, log/metric, and destroy procedure succeeds. Revisit this ADR when a
custom domain, persistent environment, higher availability, private egress,
or measured scale requirement is accepted.
