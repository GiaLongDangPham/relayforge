# ADR-006: Guarded GitHub Actions release to the single EC2 host

- Status: Accepted
- Date: 2026-08-29
- Extends: [ADR-005](0005-single-ec2-compose-deployment.md)

## Context

RelayForge has a verified manual EC2 Compose deployment, immutable Docker Hub
image tags, and a public HTTPS dashboard. Repeating image publication and the
API-before-worker deployment order by hand is useful for learning but error
prone. The owner has requested automation after completing and understanding
the manual workflow.

The host still has one persistent PostgreSQL volume. A release can contain a
Flyway migration, so an image rollback cannot be treated as an automatic
database rollback. Runtime secrets must remain in the root-owned EC2 runtime
file and must not be copied to GitHub Actions or Docker Hub.

## Decision

- The existing CI jobs remain the quality gate. On a `main` push or explicit
  workflow dispatch, a production release job runs only after backend tests,
  frontend checks, and local image builds succeed.
- A `production` GitHub Environment guards the release job. A repository-level
  `PRODUCTION_DEPLOY_ENABLED=true` variable enables publication only after the
  owner installs the EC2 scripts and configures all required environment
  variables and secrets. The enable flag is repository-scoped because GitHub
  evaluates a job condition before environment-level variables are available.
- GitHub Actions builds and pushes backend and gateway images under the first
  12 lowercase hexadecimal characters of the commit SHA. It never publishes or
  deploys `latest`.
- Actions logs into Docker Hub with a scoped personal access token, then uses a
  dedicated SSH deployment user. The workflow verifies that SSH has the EC2
  host public key before connecting; it does not use `ssh-keyscan` during a
  deployment.
- The EC2 root-owned `apply-production-release.sh` changes only the selected
  image tag, validates Compose, pulls images, deploys API and checks its health,
  then deploys worker and gateway. It fails closed on an unhealthy service and
  does not automatically roll back a database.
- The root-owned `backup-production-postgres.sh` creates a PostgreSQL custom
  archive, validates it with `pg_restore --list`, and records a checksum. It
  does not restore production data.

## Consequences

### Benefits

- A deployment is traceable from Git commit to immutable Docker Hub tag to the
  tag selected on EC2.
- CI catches test/build failures before a registry or production mutation.
- The worker cannot run the new image before API readiness and Flyway migration
  success.
- A dedicated key plus a narrowly scoped `sudo` command reduces the blast
  radius compared with storing the owner's Termius key in GitHub.

### Costs and risks

- GitHub must store a Docker Hub token and a deployment private key as secrets.
  Repository write access must therefore be restricted.
- Auto-deploying a bad application version can still cause downtime. The
  `production` environment should require human approval.
- A successful image rollback is safe only when the selected older image is
  compatible with the current migrated database. Restore is a separately
  approved, destructive operation.
- DuckDNS must be updated after an EC2 stop/start before the workflow can SSH
  through the configured domain.

## Alternatives considered

### Keep all release steps manual

Rejected after the manual path was verified. It remains the fallback procedure,
but it repeats security-sensitive commands and does not consistently enforce
the desired release ordering.

### SSH as the `ubuntu` owner account

Rejected. A CI credential should be replaceable and restricted to the one
root-owned release command rather than being the owner's general SSH key.

### Automatic database restore or image rollback on failure

Rejected. Flyway migrations may be non-reversible; a script cannot infer a safe
database rollback. It must stop, retain diagnostics, and require an operator
decision backed by a verified backup.

## Evidence and revisit triggers

This decision is fully verified only after the owner installs the dedicated
deploy key and host scripts, creates a validated archive, enables the GitHub
Environment, and observes one approved workflow deployment. Revisit it for
multiple hosts, managed database backups, a zero-downtime requirement, or a
need for AWS-native release credentials.
