// Shared data helpers: cached lookups and reusable pickers.

import { api } from './api.js';
import { el, input, select, debounce, icon, field, button } from './ui.js';
import { fullName, DATE_PRESETS, todayLocal, addDays } from './format.js';

let departmentsCache = { at: 0, items: [] };

export async function getDepartments(force = false) {
  if (!force && departmentsCache.items.length && Date.now() - departmentsCache.at < 5 * 60 * 1000) return departmentsCache.items;
  const data = await api.get('/admin/departments');
  const items = Array.isArray(data) ? data : (data.items || []);
  departmentsCache = { at: Date.now(), items };
  return items;
}
export function invalidateDepartments() { departmentsCache = { at: 0, items: [] }; }

export async function departmentSelect(attrs = {}, value = '', { allLabel = 'All departments', activeOnly = true } = {}) {
  let items = [];
  try { items = await getDepartments(); } catch (e) { items = []; }
  const options = [{ value: '', label: allLabel }, ...items.filter((d) => !activeOnly || d.active !== false).map((d) => ({ value: d.id, label: d.name }))];
  return select(options, attrs, value);
}

export async function searchEmployees(search, extra = {}) {
  const data = await api.get('/admin/employees', { search, page: 1, page_size: 10, ...extra });
  return data.items || [];
}

/**
 * Employee picker: text input with live results. onSelect(user|null).
 * Returns { el, value(), clear() }.
 */
export function employeePicker({ placeholder = 'Search by name, number or email', onSelect, initial = null } = {}) {
  let selected = initial;
  const inp = input({ placeholder, autocomplete: 'off', 'aria-label': 'Search employees', role: 'combobox', 'aria-expanded': 'false' });
  const results = el('div', { class: 'search-results hidden', role: 'listbox' });
  const chip = el('div', { class: 'row', style: { marginTop: '6px' } });
  const wrap = el('div', { class: 'stack', style: { gap: '4px' } }, inp, results, chip);

  function renderSelected() {
    chip.replaceChildren();
    if (selected) {
      const clearBtn = el('button', { type: 'button', class: 'btn secondary sm', onClick: () => { selected = null; inp.value = ''; renderSelected(); onSelect && onSelect(null); } }, icon('x', 14), 'Clear');
      chip.append(el('span', { class: 'chip blue' }, `${fullName(selected)} · ${selected.employee_number || ''}`), clearBtn);
    }
  }
  const run = debounce(async () => {
    const q = inp.value.trim();
    if (q.length < 2) { results.classList.add('hidden'); inp.setAttribute('aria-expanded', 'false'); return; }
    try {
      const items = await searchEmployees(q);
      results.replaceChildren();
      if (!items.length) results.appendChild(el('div', { class: 'muted small', style: { padding: '6px 8px' } }, 'No matches'));
      items.forEach((u) => {
        const b = el('button', { type: 'button', role: 'option', 'aria-selected': 'false' }, `${fullName(u)} · ${u.employee_number || ''}`, el('span', { class: 'muted' }, u.department ? ` · ${u.department.name}` : ''));
        b.addEventListener('click', () => { selected = u; inp.value = fullName(u); results.classList.add('hidden'); inp.setAttribute('aria-expanded', 'false'); renderSelected(); onSelect && onSelect(u); });
        results.appendChild(b);
      });
      results.classList.remove('hidden');
      inp.setAttribute('aria-expanded', 'true');
    } catch (e) { results.classList.add('hidden'); }
  }, 250);
  inp.addEventListener('input', () => { selected = null; run(); });
  inp.addEventListener('keydown', (e) => { if (e.key === 'Escape') results.classList.add('hidden'); });
  if (initial) { inp.value = fullName(initial); renderSelected(); }
  return { el: wrap, value: () => selected, clear: () => { selected = null; inp.value = ''; renderSelected(); }, input: inp };
}


// ---------- Pure helpers (unit-tested in admin-dashboard/tests/unit) ----------

/** Drop empty / null / undefined values so they never become "?x=" query params.
 *  Keeps `false` and `0` so explicit boolean/numeric filters are sent. */
export function cleanQuery(obj = {}) {
  const out = {};
  Object.entries(obj).forEach(([k, v]) => {
    if (v === undefined || v === null || v === '') return;
    out[k] = v;
  });
  return out;
}

/** Parse a page number from a query string value (defaults to 1). */
export function parsePage(v) {
  const n = parseInt(v, 10);
  return Number.isFinite(n) && n > 0 ? n : 1;
}

/** Human countdown until `expiresAt` (ISO). Returns { label, expired, ms }. */
export function countdown(expiresAt, now = Date.now()) {
  if (!expiresAt) return { label: '—', expired: false, ms: null };
  const t = new Date(expiresAt).getTime();
  if (Number.isNaN(t)) return { label: '—', expired: false, ms: null };
  const ms = t - now;
  if (ms <= 0) return { label: 'Expired', expired: true, ms };
  const s = Math.floor(ms / 1000);
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const sec = s % 60;
  if (h >= 24) return { label: `${Math.floor(h / 24)}d ${h % 24}h`, expired: false, ms };
  if (h > 0) return { label: `${h}h ${String(m).padStart(2, '0')}m`, expired: false, ms };
  return { label: `${m}m ${String(sec).padStart(2, '0')}s`, expired: false, ms };
}

const BSSID_RE = /^([0-9a-f]{2}:){5}[0-9a-f]{2}$/;
export function normaliseBssid(v) {
  const s = String(v || '').trim().toLowerCase().replace(/-/g, ':');
  return BSSID_RE.test(s) ? s : null;
}

/** Summary counts keyed by status for live attendance rows. */
export function countByStatus(items = [], key = 'status') {
  const out = {};
  items.forEach((it) => { const k = it?.[key] || 'unknown'; out[k] = (out[k] || 0) + 1; });
  return out;
}

/** Which roles the acting user may assign (API.md §7 permission matrix). */
export function assignableRoles(permissions = []) {
  const roles = [];
  if (permissions.includes('roles:write')) roles.push('employee', 'manager', 'admin');
  if (permissions.includes('roles:superadmin')) roles.push('superadmin');
  return roles;
}

/** Resolve a date range from query params with a sensible default. */
export function rangeFromQuery(query = {}, defaultPreset = 'last_7') {
  const re = /^\d{4}-\d{2}-\d{2}$/;
  if (re.test(query.from || '') && re.test(query.to || '')) return { from: query.from, to: query.to };
  const preset = DATE_PRESETS.find((p) => p.key === defaultPreset) || DATE_PRESETS[0];
  const [from, to] = preset.range();
  return { from, to };
}

/** True when a..b is a valid ordered date range. */
export function validRange(from, to) {
  return Boolean(from && to && from <= to);
}

/** Convert the report JSON rows into CSV text (for client-side copy); not used for downloads. */
export function rowsToCsv(columns = [], rows = []) {
  const esc = (v) => {
    const s = v === null || v === undefined ? '' : String(v);
    return /[",\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
  };
  const head = columns.map((c) => esc(c.label || c.key)).join(',');
  const body = rows.map((r) => columns.map((c) => esc(r[c.key])).join(','));
  return [head, ...body].join('\n');
}

// ---------- Date range control ----------
/**
 * Date range filter with presets. onChange({from,to}) fires on valid changes.
 * Returns { el, value(), set({from,to}) }.
 */
export function dateRange({ from, to, onChange, label = 'Date range' } = {}) {
  const fromInp = input({ type: 'date', value: from || '', 'aria-label': 'From date', max: addDays(todayLocal(), 366) });
  const toInp = input({ type: 'date', value: to || '', 'aria-label': 'To date', max: addDays(todayLocal(), 366) });
  const preset = select([{ value: '', label: 'Custom' }, ...DATE_PRESETS.map((p) => ({ value: p.key, label: p.label }))], { 'aria-label': 'Preset range' }, '');
  const current = () => ({ from: fromInp.value, to: toInp.value });
  function fire() {
    const v = current();
    if (validRange(v.from, v.to)) { fromInp.removeAttribute('aria-invalid'); toInp.removeAttribute('aria-invalid'); onChange && onChange(v); }
    else { toInp.setAttribute('aria-invalid', 'true'); }
  }
  preset.addEventListener('change', () => {
    const p = DATE_PRESETS.find((x) => x.key === preset.value);
    if (!p) return;
    const [f, t] = p.range();
    fromInp.value = f; toInp.value = t;
    fire();
  });
  fromInp.addEventListener('change', () => { preset.value = ''; fire(); });
  toInp.addEventListener('change', () => { preset.value = ''; fire(); });
  const wrap = el('div', { class: 'row', style: { gap: '8px', alignItems: 'flex-end' } },
    field('Preset', preset), field(`${label} from`, fromInp), field('to', toInp));
  return { el: wrap, value: current, set: ({ from: f, to: t }) => { fromInp.value = f || ''; toInp.value = t || ''; } };
}

/** Link to an employee profile from a summary object. */
export function employeeLink(emp) {
  if (!emp) return el('span', { class: 'muted' }, '—');
  return el('a', { href: `#/employees/${emp.id}`, class: 'cell-primary', onClick: (e) => e.stopPropagation() }, fullName(emp),
    emp.employee_number ? el('span', { class: 'cell-sub' }, ` · ${emp.employee_number}`) : null);
}

/** Small reusable "refresh" page action. */
export function refreshButton(onClick) {
  return button('Refresh', { kind: 'secondary', size: 'sm', icon: 'refresh', onClick });
}
