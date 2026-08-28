# Current Task

Status: Completed

## Goal

Complete Group 15 CI: add one read-only GitHub Actions workflow that runs the required backend and frontend checks before building local-only container images.

## Decisions

- Use GitHub-hosted Ubuntu runners with least-privilege `contents: read` permission and cancellation of superseded runs.
- Run the full Maven test suite with JDK 25 and Testcontainers, plus `npm ci`, lint, and production frontend build with Node 24.
- Build backend, frontend, and demo-receiver images only after tests pass; use direct Docker builds so Compose's local runtime secrets are not required.
- Do not publish images, upload secrets, deploy, or add vulnerability/SBOM scanning in this group.

## Out of scope

Image publishing, GitHub environments/secrets, AWS/cloud deployment, vulnerability or SBOM scanning, automatic dependency updates, and changes to application behavior.

## Evidence required

- Workflow syntax validation proves the GitHub Actions configuration is parseable.
- Local equivalents of the backend suite, frontend checks, and all three Docker builds pass without using the ignored local `.env`.
- The running dashboard remains browser-accessible after verification.

## Verification evidence

- `actionlint` validated `.github/workflows/ci.yml` from a read-only workflow-only mount.
- Full backend Maven/Testcontainers suite passed (112 tests); frontend `npm ci`, lint, and production build passed.
- Direct local-only Docker builds passed for backend, frontend, and demo receiver; no image was pushed and no ignored `.env` was read.
- The running dashboard reloaded at `http://localhost:5173/`, rendered the authenticated owner workspace, and reported no console errors.
