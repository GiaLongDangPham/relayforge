# Current Task

Status: Completed

## Goal

Create an ignored local production environment file for the accepted DuckDNS hostname, with generated infrastructure secrets and the owner-selected bootstrap credentials, without pushing an image or deploying to EC2.

## Decisions

- Use `https://gialong.duckdns.org` as the public dashboard/API origin and `longdpg.t1.2023@gmail.com` for Caddy's ACME account contact.
- Generate the database password, API-key pepper, and AES-256-GCM key from 32 random bytes each; keep them only in the ignored file.
- Enable exactly one controlled owner bootstrap using the owner-selected temporary credentials. Their values exist only in the ignored environment file and must not be copied into tracked documentation, logs, or chat.
- Keep the image tag as an explicit placeholder until a clean Git commit has been built and pushed to Docker Hub.

## Out of scope

Docker Hub login, repository creation, image push, EC2 secret installation, Elastic IP/DNS/certificate setup, Compose deployment, automated CD, and Java/business behavior change.

## Evidence required

- The ignored file has every required Compose variable except the image tag, which intentionally remains a fail-fast placeholder until a pushed release exists.
- `docker compose ... config` accepts the file without printing secret values or contacting a registry.

## Verification evidence

- `docker compose --env-file deploy/.env.production -f deploy/compose.production.yml config --quiet` passed without pulling, pushing, or starting any production service.
- A non-secret structural check confirmed that all required values are present, the endpoint encryption key decodes to exactly 32 bytes, and the image tag remains the intentional fail-fast placeholder.
