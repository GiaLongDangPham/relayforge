# UX1.2 — Restore selected project after reload

Status: Complete.

## Scope

Preserve the owner's selected project across a dashboard reload without
persisting any raw secret, form draft, onboarding progress, event or delivery
selection. Keep owner-scoped pagination, the compact project picker, and the
existing project-local reset boundary.

Out of scope: browser storage, backend/API changes, deep links into events or
deliveries, restoring project-local drafts, or changing onboarding behavior.

## Design direction

- The safe project identifier lives in the private `/app?project=<id>` URL.
  It is owner-validated against the existing paginated project list; it never
  carries raw keys, secrets, drafts, guide state, payloads, or delivery state.
- A reload of a project beyond the first page progressively reads the existing
  project pages only until it finds that owned ID. It does not temporarily show
  the first project as the selected workspace.
- If the ID is no longer owned or no longer exists, the URL is replaced with the
  first available project (or cleared when none remain). This is a safe fallback,
  not an authorization decision; the API remains authoritative.

## Outcome

- Project selection is now represented only by the safe project ID in
  `/app?project=<id>`. Selecting a project or successfully creating one updates
  that URL, so a normal reload and the browser Back/Forward history preserve the
  observed workspace.
- A requested later-page project keeps the workspace unselected while the
  existing paginated owner read resolves it; the dashboard never flashes the
  first project as though it were selected. An absent or unauthorized ID safely
  replaces the URL with the first owned project after the finite list is
  exhausted.
- The existing `ProjectResources` project-ID key continues to reset raw-key
  reveal, forms and guide state when the project changes. Those values are not
  encoded in the URL or stored in browser storage.

## Acceptance evidence

- `npm run lint` passed. `node tests/history-read-state.test.mjs` passed (4/4).
- Docker rebuilt the production TypeScript/Vite artifact successfully with
  `docker compose up -d --no-deps --build frontend`.
- Built-artifact Playwright acceptance passed with 25 fixture projects. It
  selects project 24 from the second page, reloads and observes project 24
  again, then proves an unknown `project` query safely lands on project 0. The
  existing keyboard dialog, 320/683/1366/1440 reflow, error/retry and AX checks
  also remain green.
- Front-End Checklist `review_code` found no provable static issue (0 Critical,
  0 High) in `ProjectWorkspace.tsx`; its result remains a heuristic review, so
  built-artifact keyboard/AX evidence supplies the interaction check.

## Remaining manual compatibility limits

- Verify native browser zoom from browser UI and spoken output with the team's target screen reader before a public release. Automated reflow/AX-tree evidence exists, but it is not a substitute for those two manual checks.

## Next checkpoint

Analyze U5.1 — safe portfolio/demo evidence. Expected 3 steps: (1) select
public-safe artifacts and redaction boundary, (2) create the documentation/demo
script, (3) run public/private smoke checks. No owner decision is pending for
analysis; an interactive public demo would need a separate isolated-dataset and
security decision first.
