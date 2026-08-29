# Current Task

Status: In progress

## Goal

Add a production backup artifact, documented rollback procedure, and guarded GitHub Actions image publication/CD for the existing single-EC2 Compose deployment.

## Decisions

- Preserve immutable Git-SHA image tags; never introduce `latest`.
- Build and publish only after the existing backend/frontend/container checks pass on `main`.
- Gate the production job through GitHub's `production` environment. It is enabled only after the owner creates the required repository/environment secrets and sets an explicit enable variable.
- Keep backup archive creation separate from destructive restore. A release failure stops and reports; it does not attempt an unsafe automatic database rollback.

## Out of scope

Creating GitHub secrets/environment protection, uploading/installing host scripts, executing the first backup, and running the first automated deployment; these require the owner's external access. Performance/JVM work and DuckDNS auto-update remain out of scope.

## Evidence required

- Shell scripts pass static syntax checks and do not print or commit runtime secrets.
- GitHub workflow syntax passes static validation and release/deploy gates preserve test-before-push and API-before-worker ordering.
- The owner can follow a short external setup list without exposing a secret in chat or Git.

## Verification evidence

- `deploy/backup-production-postgres.sh` and `deploy/apply-production-release.sh` passed `bash -n` through Git Bash. The owner installed both as root-owned mode-700 scripts on EC2.
- `.github/workflows/ci.yml` passed Python YAML parsing and `git diff --check`. The local `actionlint` binary is unavailable; an external lint container was deliberately not given repository access because the workspace contains an ignored production environment file.
- The `relayforge-deploy` account has only the intended passwordless sudo permission. Its dedicated key successfully SSHed through `gialong.duckdns.org`; a same-tag invocation made no deployment change. The backup script produced and structurally validated the `d70776814883` PostgreSQL archive, checksum, and metadata file under `/opt/relayforge/backups`.
- The GitHub Environment is configured and the first guarded release reached EC2: images tagged `a75ef093bc8d` were published and API, worker, and gateway were recreated. Its workflow reported failure only because the original script checked HTTPS before Caddy had started listening. The corrected root-owned script now waits for local TLS/SNI readiness; a same-tag invocation verified all three running production services. The correction remains uncommitted, so a subsequent source commit and successful workflow run are still required to close this task.
