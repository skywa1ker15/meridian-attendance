# Design system — Meridian Attendance

Direction taken from the supplied reference images: a dark **navy** workspace with
soft "glass" cards (translucent surfaces, faint inner highlight, rounded corners),
pill‑shaped controls and bold stat tiles — but using Meridian's required palette
(navy / white / professional blue, green for success, orange for warnings) rather
than the red‑orange of the references. The employee app is Material 3 with the same
tokens; the dashboard is a dark glass workspace with a left rail, stat tiles,
charts and dense tables.

The official Meridian Group logo was **not supplied**; a neutral monogram
placeholder is used and must be replaced (`docs/RELEASE.md` §Branding).

## Colour tokens

| Token | Hex | Use |
|-------|-----|-----|
| `navy-950` | `#070F1F` | Dashboard page background (gradient start) |
| `navy-900` | `#0B1A33` | Dashboard background (gradient end), app dark surfaces |
| `navy-800` | `#10264D` | Cards (dark), app primary container |
| `navy-700` | `#163A6E` | **Primary** (app), sidebar active |
| `navy-600` | `#1F4C8F` | Primary hover |
| `blue-500` | `#2F6FED` | **Accent** / primary actions on dark |
| `blue-300` | `#7FA6F5` | Accent text, links on dark |
| `blue-100` | `#DCE7FB` | Light primary container |
| `white` | `#FFFFFF` | Light surfaces, text on navy |
| `grey-100` | `#F3F6FB` | Light app background |
| `grey-300` | `#CBD5E3` | Dividers (light) |
| `text-dark` | `#0E1A2B` | Text on light |
| `text-light` | `#E8EEF9` | Text on dark |
| `text-muted-dark` | `#9FB0CC` | Secondary text on dark |
| `text-muted-light` | `#5B6B82` | Secondary text on light |
| `green-500` | `#1FA463` | Success / clocked in / confirmed |
| `green-100` | `#DFF5E8` | Success container (light) |
| `orange-500` | `#F28C28` | Warning / pending / late |
| `orange-100` | `#FDEBD8` | Warning container (light) |
| `red-500` | `#E5484D` | Error / rejected / revoked |
| `glass` (dark) | `rgba(255,255,255,0.06)` fill, `rgba(255,255,255,0.12)` 1px border, highlight `linear-gradient(180deg, rgba(255,255,255,0.10), rgba(255,255,255,0))` | Cards on navy |
| `glass` (light) | `rgba(255,255,255,0.72)` fill, `rgba(14,26,43,0.08)` border, `blur(16px)` | Cards in the app's light theme |

Status mapping (consistent everywhere): `confirmed/closed/clocked_in` → green,
`pending/under_review/late/missing_clock_out` → orange, `rejected/expired/revoked/inactive`
→ red, `dismissed/not_started/clocked_out` → neutral grey‑blue.

## Typography
* Android: Roboto (system) via Material 3 type scale; display numerals use
  `headlineLarge` with `FontWeight.SemiBold`.
* Web: `Inter, "Segoe UI", Roboto, system-ui, -apple-system, sans-serif` (no font
  downloads — the dashboard must work on an intranet without internet).
* Scale (web): 12 / 13 / 14 (body) / 16 / 20 / 28 / 36 px; weights 400/500/600/700.

## Shape & spacing
* Radius: cards 20 (app) / 16 (web), controls 12, pills 999.
* Spacing scale: 4, 8, 12, 16, 24, 32.
* Shadows (dark): `0 10px 30px rgba(0,0,0,0.35)`; (light): `0 6px 20px rgba(14,26,43,0.08)`.

## Components (shared vocabulary)
* **StatusHero** (app dashboard): large status pill + clock‑in/out times + worked hours ring.
* **StatTile** (web): icon chip, label, big number, delta/sub‑label.
* **GlassCard**, **PillButton** (primary = blue‑500 on dark / navy‑700 on light; success = green; danger = red; ghost), **StatusChip**, **DataTable** (sticky header, zebra rows, row actions), **FilterBar**, **EmptyState**, **Toast**, **ConfirmDialog**, **Drawer/Rail**.
* Charts: line (trend), bars (department breakdown), ring (hours), all SVG with the
  palette above; axes in `text-muted-dark`.

## Accessibility
* Minimum contrast 4.5:1 for text (checked for every pairing above on its background).
* Touch targets ≥ 48dp (app) / 40px (web). Status never conveyed by colour alone —
  always with a label/icon.
