# EC2 Compose Deployment Baseline

## Purpose and scope

This is the active Group 16 deployment baseline following
[ADR-005](adr/0005-single-ec2-compose-deployment.md). It defines the temporary
single-host EC2 shape and the manual deployment boundary. It does not open web
ports, create a domain, push an image, create production secrets, or start a
RelayForge container.

## Current server baseline

| Area | Chosen state | Why |
| --- | --- | --- |
| Region | AWS Tokyo (`ap-northeast-1`) | The owner launched the temporary host there. |
| Host | Ubuntu 26.04 amd64 EC2 `t3.small` | Two GiB is the minimum practical memory for PostgreSQL plus separate API and worker JVMs. |
| Disk | 20 GiB encrypted EBS root volume | Holds OS, image layers, PostgreSQL volume, and bounded logs for a short demo. |
| Host access | Termius SSH as `ubuntu` with a private key | The owner can inspect every deployment step. |
| AWS inbound rule | TCP 22 from the owner's current `/32` only | Prevents Internet-wide SSH access. |
| Host software | Docker Engine and Compose plugin | The owner has already verified both. |

Stopping EC2 stops compute charges but EBS and public-address resources can
still incur charges. A stopped instance normally receives a different public IP
when started again; allocate an Elastic IP only when the domain step needs a
stable address, then release it during teardown.

## Intended production Compose shape

| Service | Runtime | Published host ports | Required relationship |
| --- | --- | --- | --- |
| `gateway` | Caddy plus static dashboard | 80, 443 only | Serves React and proxies `/api/*` internally. |
| `api` | Shared backend image, `RELAYFORGE_RUNTIME=api` | None | Runs Flyway and owner/publisher API. |
| `worker` | Same backend image, `RELAYFORGE_RUNTIME=worker` | None | Starts only after API migration/readiness. |
| `postgres` | PostgreSQL 17 | None | Owns a persistent named volume; reachable only to API and worker. |

The `gateway` container needs no database credential. API and worker receive
only the database credentials and RelayForge secret values required by their
runtime. Docker Compose's internal network is not a replacement for AWS
Security Group rules, but it removes accidental host exposure when no port is
published.

## Network and TLS sequence

1. Keep only SSH 22 from the owner's IP while provisioning.
2. Build and locally validate the production Compose configuration before it
   reaches the host.
3. Allocate and associate a stable Elastic IP, then create the DNS record for a
   domain controlled by the owner.
4. Add AWS Security Group inbound TCP 80 and 443 from anywhere.
5. Caddy obtains and renews the certificate; set the dashboard origin to the
   exact `https://` domain and enable secure cookies.

Do not expose 5432, 8080, or 8082 in either AWS Security Group or Compose. Do
not use a raw IP plus HTTP as the final browser deployment because the existing
session security model requires secure cookies and HTTPS.

## Image, secret, and deployment boundary

The future CI workflow produces explicit Docker Hub tags such as a Git commit
SHA. The owner's Docker Hub namespace is `gialong1416`, with the intended
repositories `gialong1416/relayforge-backend` and
`gialong1416/relayforge-gateway`. A manual deployment selects one tag and
records it in the host deployment directory. Image values are safe to store in
Compose configuration; runtime secrets are not.

```text
Repository / CI
  -> test
  -> build backend and dashboard images
  -> push Docker Hub images tagged by Git SHA
  -> operator SSHs to EC2
  -> pull selected tag
  -> start PostgreSQL and API; wait for API health/migrations
  -> start worker
  -> gateway serves dashboard and API only after domain/TLS setup
```

`/opt/relayforge/.env.production` is created directly on EC2 and ignored by
Git. It will contain database password, API-key pepper, endpoint encryption
key, and bootstrap values only for the controlled initial seed. It must have
owner-only read permission and its values must never be copied into Docker Hub,
GitHub workflow output, screenshots, or issue comments.

## Production Compose artifact

The deployable topology is defined locally before it reaches EC2:

- [`deploy/compose.production.yml`](../deploy/compose.production.yml) references
  explicit prebuilt backend and gateway image tags. It does not build source on
  the host and it publishes only Caddy's ports 80 and 443.
- [`deploy/Dockerfile.gateway`](../deploy/Dockerfile.gateway) builds the React
  dashboard with a required public API origin, then serves the static result
  from Caddy. The backend image is built separately from
  [`backend/Dockerfile`](../backend/Dockerfile).
- [`deploy/Caddyfile`](../deploy/Caddyfile) serves the dashboard, proxies only
  `/api/*` to the internal API container, and leaves Actuator management routes
  unexposed.
- [`deploy/.env.production.example`](../deploy/.env.production.example) is a
  committed key/template only. Copy it on the host to
  `/opt/relayforge/.env.production`, replace every placeholder, and never
  commit that copy. The root `.dockerignore` prevents that runtime file from
  entering the gateway image build context.

API is the only container with Flyway enabled. Compose starts worker only after
API's readiness endpoint succeeds; worker then starts with Flyway disabled.
This is a deployment order, not a database-locking substitute: a failed
migration keeps API unhealthy and deliberately prevents the worker from
processing jobs against an unknown schema.

The combined container memory caps are 1,280 MiB: 512 MiB API, 384 MiB worker,
256 MiB PostgreSQL, and 128 MiB gateway. This intentionally leaves a margin for
Ubuntu, Docker, filesystem cache, and burst behavior on the temporary
`t3.small`; it is a conservative demo boundary rather than a capacity claim.

## Manual image build and publication

Run the local helper from the repository root with the final public HTTPS
origin. It derives one Git-SHA tag and builds both Docker Hub images under
`gialong1416` without registry mutation by default:

```powershell
.\deploy\build-production-images.ps1 -PublicOrigin https://relayforge.example.com
```

The gateway build receives the origin as `VITE_API_ORIGIN`; Vite embeds that
value into static browser code. Therefore, do not publish a gateway image for a
placeholder, raw IP address, or a domain that is not the final HTTPS origin.
After the owner has logged Docker into their Docker Hub account and reviewed
the exact tag, the same command with `-Push` publishes both images. This is a
deliberate external action and is not part of the no-push validation slice. The
helper refuses a dirty Git working tree so an image tag can never falsely claim
to represent an older commit.

## Operational limits and rollback

- Compose restart policies restore containers after a host reboot; they do not
  make a failed release healthy.
- A deployment updates API first, checks its health, then starts worker. If the
  API fails, keep worker on the prior working image and restore the prior
  explicit tag after diagnosing the failure.
- PostgreSQL is backed by an EBS-hosted Docker volume. Before destructive
  upgrade, pruning, `down -v`, or instance termination, take an intentional
  logical backup/snapshot. None is automatic in this baseline.
- The owner is responsible for stopping/terminating the instance and releasing
  Elastic IPs, EBS volumes, snapshots, and other billable resources after the
  demo.

## Deferred

- Docker Hub repository, CI image build/push, and automated CD.
- Elastic IP, domain/DNS, certificate issuance, and public HTTP/HTTPS rules.
- Database backup/restore drill, external log shipping, and AWS SSM-based
  administration.
- Multi-host orchestration, managed PostgreSQL, Kubernetes, and high
  availability.
