# Current Task

Status: Completed

## Goal

Create and validate the local production Compose/Caddy configuration for the accepted EC2 deployment without pushing images, creating secrets, opening web ports, or deploying to EC2.

## Decisions

- Production Compose uses a prebuilt backend image twice and a prebuilt Caddy/dashboard image once; it never builds source on EC2.
- PostgreSQL, API, and worker publish no host ports. Caddy is the only service declaring `80:80` and `443:443`.
- API owns Flyway and must pass readiness before worker starts with `SPRING_FLYWAY_ENABLED=false`.
- Container limits reserve 512 MiB for API, 384 MiB for worker, 256 MiB for PostgreSQL, and 128 MiB for Caddy; Java heap follows an explicit 60% maximum within each application limit.

## Out of scope

Opening public web ports, Elastic IP/DNS/certificate setup, Docker Hub image push, production secret creation, Compose application deployment, automated CD, backup drill, and Java/business behavior change.

## Evidence required

- `docker compose --env-file deploy/.env.production.example -f deploy/compose.production.yml config` accepts the topology without resolving any image or secret.
- The gateway Dockerfile builds the production dashboard with an explicit public API origin and Caddy validates its configuration.
- The backend image builds with the readiness-check client; no container is pushed or deployed.

## Verification evidence

- `docker compose --env-file deploy/.env.production.example -f deploy/compose.production.yml config` passed without pulling, pushing, or running a production service. Its rendered topology has only gateway ports 80/443.
- `docker build --tag relayforge-backend:production-check backend` passed. The verified image contains `curl` for health checks and runs as the non-root `relayforge` user.
- `docker build --build-arg VITE_API_ORIGIN=https://relayforge.example.com --file deploy/Dockerfile.gateway --tag relayforge-gateway:production-check .` passed after the root `.dockerignore` limited the build context to the required files. Caddy configuration validation passed using the built image and placeholder domain values.
- Local Compose was started only for the required dashboard smoke check. `curl.exe` returned success for both `http://localhost:5173/` and API readiness at `http://localhost:8080/actuator/health/readiness`. The embedded browser's initial request saw the dashboard offline before Compose started; its subsequent reload was blocked by the browser URL policy, so no browser DOM/console assertion was available.
