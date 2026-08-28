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

- `deploy/backup-production-postgres.sh` and `deploy/apply-production-release.sh` passed `bash -n` through Git Bash. They were not run against EC2 because installation and production mutation require the owner's SSH access.
- `.github/workflows/ci.yml` passed Python YAML parsing and `git diff --check`. The local `actionlint` binary is unavailable; an external lint container was deliberately not given repository access because the workspace contains an ignored production environment file.
- ADR-006 and the runbook record the guarded release decision, dedicated SSH account, host-key verification, backup/rollback boundary, and DuckDNS recovery procedure. GitHub Environment secrets/variables and the EC2 installation remain pending owner setup.
