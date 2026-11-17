# Administrator dashboard

A single-page application built from vanilla ES2020 modules. It has no build step, no
CDN and no font downloads, so it works on an intranet with no internet access. The backend
serves it at `/admin/` from `backend/app/static/admin/`. All data comes from `/api/v1`
(see `docs/API.md`). The dashboard shows no numbers of its own: where the API returns
nothing, the screen shows an empty state.

## Layout

```
backend/app/static/admin/
├── index.html
├── css/app.css         design tokens (docs/DESIGN.md), shell, components, responsive rules
├── css/screens.css     screen-specific styles
└── js/
    ├── app.js          shell: NAV (permission-gated rail), routing gate, session expiry, logout
    ├── router.js       hash router (#/path/:param?query)
    ├── session.js      tokens + user in sessionStorage (cleared when the tab closes)
    ├── api.js          fetch client: bearer token, single-flight refresh rotation, ApiError, file downloads
    ├── ui.js           DOM helpers, toasts, modals, confirm dialogs, drawers, tables, skeletons, states
    ├── charts.js       hand-drawn SVG line/bar/ring charts
    ├── data.js         cached lookups, pickers, date range control, pure helpers
    ├── format.js       Africa/Johannesburg date/time formatting, status colours/labels
    └── screens/        one module per screen (render(container, ctx) → cleanup)
```

## Screens and permissions

The server enforces every permission. The rail hides what the server would refuse.
If the server still returns `403`, the screen shows a toast or an inline error and does
not crash.

| Route | Screen | Needs | Write actions need |
|-------|--------|-------|--------------------|
| `#/login` | Sign in (no device), forgot password. Only `manager`/`admin`/`superadmin` may enter | – | – |
| `#/reset-password?token=` | Set a new password from the emailed link | – | – |
| `#/change-password` | Forced change when `must_change_password` is set | signed in | – |
| `#/overview` | 10 stat tiles, 14-day trend, department breakdown, recent activity, date picker | `admin:read` | – |
| `#/employees` | Directory: search, department/status/role filters, pagination, create (one-time password with copy) | `admin:read` | `employees:write` |
| `#/employees/:id` | Details edit, devices approve/revoke, 30-day stats, today, deactivate/reactivate (reason), reset password, change role, POPIA export, erase (type the employee number) | `admin:read` | `employees:write`, `devices:write`, `roles:write` (`roles:superadmin` for superadmin and erase) |
| `#/live` | Today's status per employee, status chips, department filter, auto-refresh every 30 s | `admin:read` | – |
| `#/records` | Sessions: date range/employee/department/status/late/manual filters, detail drawer with event timeline, correction (reason required), add manual session | `admin:read` | `attendance:review` |
| `#/pending` | Pending + under-review events with verification signals and expiry countdown; approve/reject with note; manual requests | `attendance:review` | `attendance:review` |
| `#/exceptions` | Exception kinds filter, date range, department | `admin:read` | – |
| `#/reports` | 11 report types, filters, JSON preview table + summary, CSV/XLSX/PDF downloads (fetched with the bearer token) | `admin:read` | `reports:export` |
| `#/workplaces` | Workplaces (geofence, Wi-Fi SSID/BSSIDs, detection timings, "coordinates not confirmed" warning), Policy (every §8 field, labelled as defaults for management review), Departments | `admin:read` | `config:write` |
| `#/devices` | Device list with status/search filters, approve/revoke | `admin:read` | `devices:write` |
| `#/roles` | Permission matrix with user counts from `GET /admin/roles`; change a user's role via employee search | `admin:read` | `roles:write` / `roles:superadmin` |
| `#/audit` | Filters for actor/action/entity/entity id/date/search; before/after JSON diff drawer | `admin:read` | – |
| `#/notifications-broadcast` | Send an inbox/push broadcast to everyone or one department | `notifications:broadcast` (admin) | – |

## Global behaviour

* Toasts, confirm dialogs (with a required note or typed confirmation where needed),
  loading skeletons, empty states, and error states with a retry button.
* Keyboard: skip link, focus trap in dialogs, Esc closes dialogs and drawers, Enter opens
  table rows, sortable headers work by keyboard. Status is never shown by colour alone.
* Responsive down to 360 px: the rail collapses to icons below 900 px and becomes an
  off-canvas drawer below 640 px.
* Session: on `401` the client rotates the refresh token once and retries the request.
  If refresh fails, the current route is saved, a toast appears and you return to login.
  After sign-in you go back to the saved route.
* Times are shown in Africa/Johannesburg. `datetime-local` inputs are read as SAST and
  sent as UTC `Z` timestamps.

## Tests

All tests live in `admin-dashboard/tests/`. See `admin-dashboard/tests/e2e/README.md` for full instructions.

```bash
cd admin-dashboard/tests && npm install
npm run check       # import every module (syntax/export errors)
npm run test:unit   # node --test unit tests (format, router, data helpers)
npm run smoke       # Chromium against static files only, API down: no JS errors on any route
ADMIN_IDENTIFIER=… ADMIN_PASSWORD=… npm run test:e2e   # Playwright against a live backend
```
