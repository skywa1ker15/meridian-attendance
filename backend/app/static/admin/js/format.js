// Formatting helpers. All timestamps from the API are UTC ISO-8601; company-facing
// display uses Africa/Johannesburg.

export const TZ = 'Africa/Johannesburg';

const dtf = (opts) => new Intl.DateTimeFormat('en-ZA', { timeZone: TZ, ...opts });
const fmtDateTimeF = dtf({ year: 'numeric', month: 'short', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false });
const fmtDateF = dtf({ year: 'numeric', month: 'short', day: '2-digit' });
const fmtTimeF = dtf({ hour: '2-digit', minute: '2-digit', hour12: false });
const fmtTimeSecF = dtf({ hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false });

function toDate(v) {
  if (!v) return null;
  if (v instanceof Date) return v;
  const d = new Date(v);
  return Number.isNaN(d.getTime()) ? null : d;
}

export function fmtDateTime(v) { const d = toDate(v); return d ? fmtDateTimeF.format(d).replace(',', '') : '—'; }
export function fmtDate(v) { const d = toDate(v); return d ? fmtDateF.format(d) : '—'; }
export function fmtTime(v) { const d = toDate(v); return d ? fmtTimeF.format(d) : '—'; }
export function fmtTimeSec(v) { const d = toDate(v); return d ? fmtTimeSecF.format(d) : '—'; }

/** Format a local calendar date string YYYY-MM-DD as "Mon 16 Nov 2026". */
export function fmtWorkDate(s) {
  if (!s) return '—';
  const [y, m, d] = String(s).split('-').map(Number);
  if (!y || !m || !d) return s;
  const date = new Date(Date.UTC(y, m - 1, d, 12));
  return new Intl.DateTimeFormat('en-ZA', { timeZone: 'UTC', weekday: 'short', day: '2-digit', month: 'short', year: 'numeric' }).format(date);
}

export function fmtMinutes(min) {
  if (min === null || min === undefined || Number.isNaN(Number(min))) return '—';
  const m = Math.max(0, Math.round(Number(min)));
  const h = Math.floor(m / 60);
  const r = m % 60;
  if (h === 0) return `${r}m`;
  return `${h}h ${String(r).padStart(2, '0')}m`;
}

export function fmtHoursDecimal(min) {
  if (min === null || min === undefined) return '—';
  return (Number(min) / 60).toFixed(2);
}

export function fmtNumber(n) {
  if (n === null || n === undefined) return '—';
  return new Intl.NumberFormat('en-ZA').format(n);
}

export function fmtDistance(m) {
  if (m === null || m === undefined) return '—';
  const v = Number(m);
  return v >= 1000 ? `${(v / 1000).toFixed(2)} km` : `${Math.round(v)} m`;
}

export function fmtRelative(v) {
  const d = toDate(v);
  if (!d) return '—';
  const diff = (Date.now() - d.getTime()) / 1000;
  if (diff < 60) return 'just now';
  if (diff < 3600) return `${Math.floor(diff / 60)} min ago`;
  if (diff < 86400) return `${Math.floor(diff / 3600)} h ago`;
  return fmtDateTime(d);
}

export function titleCase(s) {
  if (!s) return '';
  return String(s).replace(/_/g, ' ').replace(/\b\w/g, (c) => c.toUpperCase());
}

export function fullName(u) {
  if (!u) return '—';
  const n = [u.first_name, u.last_name].filter(Boolean).join(' ');
  return n || u.email || u.employee_number || '—';
}

export function initials(u) {
  if (!u) return '?';
  const a = (u.first_name || '').trim()[0] || '';
  const b = (u.last_name || '').trim()[0] || '';
  return (a + b).toUpperCase() || (u.email || '?')[0].toUpperCase();
}

/** Today's local calendar date (Africa/Johannesburg) as YYYY-MM-DD. */
export function todayLocal() {
  return localDateOf(new Date());
}

export function localDateOf(date) {
  const parts = new Intl.DateTimeFormat('en-CA', { timeZone: TZ, year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(date);
  const get = (t) => parts.find((p) => p.type === t).value;
  return `${get('year')}-${get('month')}-${get('day')}`;
}

export function addDays(ymd, n) {
  const [y, m, d] = ymd.split('-').map(Number);
  const dt = new Date(Date.UTC(y, m - 1, d + n, 12));
  return dt.toISOString().slice(0, 10);
}

export function startOfWeek(ymd) {
  const [y, m, d] = ymd.split('-').map(Number);
  const dt = new Date(Date.UTC(y, m - 1, d, 12));
  const dow = (dt.getUTCDay() + 6) % 7; // Monday = 0
  return addDays(ymd, -dow);
}

export function startOfMonth(ymd) { return ymd.slice(0, 8) + '01'; }
export function endOfMonth(ymd) {
  const [y, m] = ymd.split('-').map(Number);
  const last = new Date(Date.UTC(y, m, 0, 12));
  return last.toISOString().slice(0, 10);
}

export const DATE_PRESETS = [
  { key: 'today', label: 'Today', range: () => { const t = todayLocal(); return [t, t]; } },
  { key: 'yesterday', label: 'Yesterday', range: () => { const t = addDays(todayLocal(), -1); return [t, t]; } },
  { key: 'this_week', label: 'This week', range: () => { const t = todayLocal(); return [startOfWeek(t), t]; } },
  { key: 'last_week', label: 'Last week', range: () => { const s = addDays(startOfWeek(todayLocal()), -7); return [s, addDays(s, 6)]; } },
  { key: 'last_7', label: 'Last 7 days', range: () => { const t = todayLocal(); return [addDays(t, -6), t]; } },
  { key: 'this_month', label: 'This month', range: () => { const t = todayLocal(); return [startOfMonth(t), t]; } },
  { key: 'last_month', label: 'Last month', range: () => { const s = startOfMonth(addDays(startOfMonth(todayLocal()), -1)); return [s, endOfMonth(s)]; } },
  { key: 'last_30', label: 'Last 30 days', range: () => { const t = todayLocal(); return [addDays(t, -29), t]; } },
];

/** Convert a datetime-local input value (interpreted in Africa/Johannesburg) to UTC ISO.
 *  Uses Intl to resolve the timezone offset for the given date, so it handles any
 *  future DST changes without a hardcoded offset. */
export function localInputToUtcIso(value) {
  if (!value) return null;
  const m = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})/.exec(value);
  if (!m) return null;
  const [, y, mo, d, h, mi] = m.map(Number);
  // Build a Date in UTC, then ask Intl what the wall-clock hour is in the target TZ.
  // The difference between the UTC hour and the TZ hour gives the offset.
  const guess = Date.UTC(y, mo - 1, d, h, mi);
  const parts = new Intl.DateTimeFormat('en-CA', { timeZone: TZ, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false }).formatToParts(new Date(guess));
  const get = (t) => Number(parts.find((p) => p.type === t).value);
  const tzH = get('hour') === 24 ? 0 : get('hour');
  const offsetMs = ((tzH - h) * 60 + (get('minute') - mi)) * 60000;
  const utc = guess - offsetMs;
  return new Date(utc).toISOString().replace(/\.\d{3}Z$/, 'Z');
}

/** Convert a UTC ISO timestamp to a datetime-local input value in Africa/Johannesburg. */
export function utcIsoToLocalInput(iso) {
  const d = toDate(iso);
  if (!d) return '';
  const parts = new Intl.DateTimeFormat('en-CA', { timeZone: TZ, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false }).formatToParts(d);
  const get = (t) => parts.find((p) => p.type === t).value;
  const hh = get('hour') === '24' ? '00' : get('hour');
  return `${get('year')}-${get('month')}-${get('day')}T${hh}:${get('minute')}`;
}

export function uuid() {
  if (window.crypto && typeof window.crypto.randomUUID === 'function') return window.crypto.randomUUID();
  const b = new Uint8Array(16);
  (window.crypto || {}).getRandomValues ? window.crypto.getRandomValues(b) : b.forEach((_, i) => { b[i] = Math.floor(Math.random() * 256); });
  b[6] = (b[6] & 0x0f) | 0x40;
  b[8] = (b[8] & 0x3f) | 0x80;
  const h = [...b].map((x) => x.toString(16).padStart(2, '0')).join('');
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}

export const STATUS_COLOR = {
  // event statuses
  confirmed: 'green', pending: 'orange', under_review: 'orange', rejected: 'red', expired: 'red', dismissed: 'grey',
  // session statuses
  open: 'green', closed: 'blue', missing_clock_out: 'orange',
  // today statuses
  not_started: 'grey', pending_arrival: 'orange', clocked_in: 'green', pending_departure: 'orange', clocked_out: 'blue',
  // users / devices / requests
  active: 'green', inactive: 'red', pending_approval: 'orange', revoked: 'red', approved: 'green', cancelled: 'grey',
  // misc
  late: 'orange', absent: 'red', present: 'green', ok: 'green', failed: 'red',
  // exception kinds
  late_arrival: 'orange', expired_confirmation: 'red', dismissed_detection: 'grey', rejected_event: 'red', outside_geofence: 'orange',
};

export const STATUS_LABEL = {
  not_started: 'Not started', pending_arrival: 'Pending arrival', clocked_in: 'Clocked in', pending_departure: 'Pending departure',
  clocked_out: 'Clocked out', missing_clock_out: 'Missing clock-out', under_review: 'Under review', pending_approval: 'Pending approval',
};

export function statusLabel(s) { return STATUS_LABEL[s] || titleCase(s); }
