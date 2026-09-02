# U1.5 — Compact landing hero follow-up

Status: Complete

## Outcome

Compacted the public landing hero in response to owner feedback, preserving its
approved story and first-viewport purpose while making the page easier to take
in as one overview.

## Changes

- Reduced desktop title maximum from 88px to 72px and loosened its oversized
  multi-line footprint without weakening the heading hierarchy.
- Reduced hero block padding maximum from 136px to 72px, grid gap maximum from
  96px to 56px, and header trailing space from 32px to 20px.
- Gave the text column proportionally more room and condensed the delivery-path
  card's padding, title, steps, and footnote spacing. Body text and 44px action
  targets remain readable and unchanged in purpose.

## Verification

- At the same desktop browser setting, hero height reduced from about 1,072px
  to 654px; the workflow section begins about 430px earlier.
- `npm run lint` and elevated `npm run build` passed.
- `docker compose up -d --build --no-deps frontend` passed.
- Public browser check found one `h1`, one `main`, the named section navigation,
  no console entries, and no horizontal overflow at the embedded browser's
  narrow 640 CSS-pixel result for its 320-device setting.
- The Front-End Checklist MCP's `review_code` tool remains unavailable in this
  runtime. Manual layout, typography, accessibility, semantics, motion, and
  responsive review found no Critical or High issue.

## Next action

Analyze U3.1 — dashboard-health contract in three checkpoints: owner questions,
current safe reads, then any justified aggregate contract. No owner decision is
pending; recommend using existing reads first.
