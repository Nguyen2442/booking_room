# UI end-to-end checks

Playwright scripts that drive the real UI of a **running** stack (`docker compose up --build` from the repo root, demo profile).

```bash
cd e2e && npm install && npx playwright install chromium
cd .. && node e2e/basic-flows.mjs      # login, grid booking, conflict + suggestions, find, cancel, admin rooms, mobile
node e2e/features.mjs                  # language switch, attendees, recurring series, ics, move room, account, room blocks
```

Environment: `BASE_URL` (default `http://localhost:8088`). Screenshots go to `e2e/screenshots/` (git-ignored).
The scripts clean up the data they create (titles prefixed `E2E`) using `docker compose exec db psql`, and
restore the demo password they temporarily change.
