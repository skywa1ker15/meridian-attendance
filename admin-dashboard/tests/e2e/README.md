# Admin dashboard: end-to-end tests (Playwright)

These specs drive the real dashboard at `/admin/` against a **running backend**.
They create real records (employees named `E2E-…`/`AUD-…`/`DENY-…` with
`@e2e-test.example.co.za` emails, a pending arrival event, a manual role change). Run them
only against a development or staging database.

## Prerequisites

* Backend running and healthy: `curl -s http://127.0.0.1:8000/healthz` returns `{"status":"ok",…}`.
* An **admin or superadmin** account that has already changed its initial password.
* Node 20+ and Chromium. Playwright is pinned to **1.63.0**, which uses Chromium build 1243
  (pre-installed at `/opt/pw-browsers`). Elsewhere, run `npx playwright install chromium`.

## Run

```bash
cd admin-dashboard/tests
npm install
export PLAYWRIGHT_BROWSERS_PATH=/opt/pw-browsers   # set by default in playwright.config.js
export BASE_URL=http://127.0.0.1:8000/admin/        # default
export ADMIN_IDENTIFIER=admin@example.co.za
export ADMIN_PASSWORD='…'
npm run test:e2e
```

Tests that need credentials are **skipped** (not failed) when `ADMIN_IDENTIFIER` or
`ADMIN_PASSWORD` is not set. The HTML report is written to `admin-dashboard/tests/playwright-report/`.

Login is rate limited (10/min/IP), so the suite runs with one worker. It logs in through
the API once per account and injects the tokens into `sessionStorage`, as a real sign-in would.
Only `auth.spec.js` uses the login form.

## What is covered

| Spec | Covers |
|------|--------|
| `auth.spec.js` | Login form, bad credentials, forgot password, reset link without token, deep-link return after login, logout |
| `screens.spec.js` | Every navigation screen renders with no uncaught errors or error states; all 10 overview stat tiles; not-found route |
| `employees.spec.js` | Create an employee in the modal, one-time temporary password shown, profile opens, search finds the employee |
| `pending.spec.js` | API setup: new employee + device submits an arrival. UI: admin approves it with a note. API check: the event is `confirmed` |
| `reports.spec.js` | Report preview table, CSV download through `fetch` with the bearer token |
| `workplace.spec.js` | Edit the workplace radius, check the change persisted, restore it; unconfirmed-coordinates warning; policy note |
| `audit.spec.js` | An `employee.create` entry appears for a new user; the before/after diff drawer opens |
| `permissions.spec.js` | Manager: `POST /admin/employees` returns `403 forbidden`; no "New employee" button; broadcast screen denied |
| `responsive.spec.js` | At 360 px the rail becomes a drawer, navigation works, no horizontal overflow (`mobile-360` project) |

## Checks that need no backend

```bash
npm run check       # imports every SPA module in Node (syntax errors, missing exports)
npm run test:unit   # node --test: format.js, router.js, data.js
npm run smoke       # static server + Chromium: every route boots without JS errors while the API is down
```
