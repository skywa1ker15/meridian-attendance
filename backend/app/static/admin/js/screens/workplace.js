// Workplace configuration: workplaces (geofence + Wi-Fi), attendance policy, departments.

import { api } from '../api.js';
import { session } from '../session.js';
import { el, replace, pageHead, button, card, field, input, select, textarea, switchInput, checkbox, formError, showFormError, setBusy, toast, handleError, skeleton, errorState, emptyState, dataTable, boolChip, openModal, pillTabs, icon } from '../ui.js';
import { fmtDateTime, titleCase } from '../format.js';
import { replaceQuery } from '../router.js';
import { normaliseBssid, invalidateDepartments, getDepartments, cleanQuery } from '../data.js';

const TABS = [{ key: 'workplaces', label: 'Workplaces' }, { key: 'policy', label: 'Attendance policy' }, { key: 'departments', label: 'Departments' }];

export function render(container, { setTitle, query }) {
  setTitle('Workplace configuration', 'Geofences, Wi-Fi verification, policy and departments');
  const canWrite = session.can('config:write');
  let tab = TABS.some((t) => t.key === query.tab) ? query.tab : 'workplaces';
  const body = el('div', { class: 'stack', style: { gap: '20px' } });
  const tabs = pillTabs(TABS, tab, (k) => { tab = k; replaceQuery({ tab: k === 'workplaces' ? '' : k }); show(); });
  replace(container, pageHead('Workplace configuration', canWrite ? '' : 'Read-only: your role cannot change configuration', []), tabs, body);

  function show() {
    if (tab === 'workplaces') renderWorkplaces(body, canWrite, query.workplace_id);
    else if (tab === 'policy') renderPolicy(body, canWrite);
    else renderDepartments(body, canWrite);
  }
  show();
  return () => {};
}

// ---------------- Workplaces ----------------
const WP_DEFAULTS = { name: '', address: '', latitude: '', longitude: '', coordinates_confirmed: false, radius_m: 150, accuracy_threshold_m: 100, entry_detection_enabled: true, exit_detection_enabled: true, dwell_seconds: 120, exit_debounce_seconds: 300, wifi_verification_enabled: false, wifi_ssid: '', wifi_bssids: [], wifi_required: false, timezone: 'Africa/Johannesburg', active: true };

async function renderWorkplaces(body, canWrite, selectedId) {
  replace(body, el('div', { class: 'card' }, skeleton({ rows: 3, cols: 2, tall: true })));
  let items;
  try { const data = await api.get('/admin/workplaces'); items = data.items || []; }
  catch (err) { replace(body, errorState(err, () => renderWorkplaces(body, canWrite, selectedId))); return; }

  let selected = items.find((w) => w.id === selectedId) || items[0] || null;
  let creating = false;
  const list = el('div', { class: 'workplace-list', role: 'listbox', 'aria-label': 'Workplaces' });
  const formHost = el('div');

  function renderList() {
    replace(list, items.length ? items.map((w) => el('div', { class: 'workplace-item', role: 'option', tabindex: 0, 'aria-selected': String(!creating && selected?.id === w.id), onClick: () => { creating = false; selected = w; renderList(); renderForm(); }, onKeydown: (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); creating = false; selected = w; renderList(); renderForm(); } } },
      el('div', { class: 'row between' }, el('strong', {}, w.name), el('div', { class: 'row', style: { gap: '4px' } }, w.active ? el('span', { class: 'chip green' }, 'Active') : el('span', { class: 'chip grey' }, 'Inactive'), !w.coordinates_confirmed ? el('span', { class: 'chip orange' }, 'Coordinates unconfirmed') : null)),
      el('div', { class: 'wp-meta' }, `${w.address || 'No address'} · radius ${w.radius_m} m${w.wifi_verification_enabled ? ' · Wi-Fi' : ''}`)))
      : emptyState('No workplaces configured', canWrite ? 'Create the first workplace to enable geofence detection.' : 'An administrator must create a workplace.'));
    if (creating) list.appendChild(el('div', { class: 'workplace-item', 'aria-selected': 'true' }, el('strong', {}, 'New workplace')));
  }

  function renderForm() {
    if (!selected && !creating) { replace(formHost, el('div', { class: 'card' }, emptyState('Select a workplace', 'or create a new one.'))); return; }
    replace(formHost, workplaceForm(creating ? { ...WP_DEFAULTS } : selected, canWrite, creating, async (saved) => {
      await reload(saved?.id);
    }));
  }
  async function reload(id) {
    try { const data = await api.get('/admin/workplaces'); items = data.items || []; } catch (e) { handleError(e); return; }
    creating = false;
    selected = items.find((w) => w.id === id) || items[0] || null;
    renderList(); renderForm();
  }

  const newBtn = canWrite ? button('New workplace', { icon: 'plus', size: 'sm', onClick: () => { creating = true; renderList(); renderForm(); }, attrs: { 'data-testid': 'wp-new' } }) : null;
  renderList(); renderForm();
  replace(body, el('div', { class: 'grid', style: { gridTemplateColumns: 'minmax(240px, 1fr) minmax(0, 2fr)' } },
    card('Workplaces', list, { actions: newBtn }), formHost));
  if (window.matchMedia('(max-width: 900px)').matches) body.firstChild.style.gridTemplateColumns = '1fr';
}

function num(v) { const n = Number(v); return Number.isFinite(n) ? n : null; }

function workplaceForm(w, canWrite, isNew, onSaved) {
  const f = {
    name: input({ value: w.name || '', required: true, 'data-testid': 'wp-name' }),
    address: input({ value: w.address || '', 'data-testid': 'wp-address' }),
    latitude: input({ type: 'number', step: 'any', min: -90, max: 90, value: w.latitude ?? '', required: true, 'data-testid': 'wp-lat' }),
    longitude: input({ type: 'number', step: 'any', min: -180, max: 180, value: w.longitude ?? '', required: true, 'data-testid': 'wp-lng' }),
    radius_m: input({ type: 'number', min: 30, max: 5000, step: 1, value: w.radius_m ?? 150, 'data-testid': 'wp-radius' }),
    accuracy_threshold_m: input({ type: 'number', min: 5, max: 2000, step: 1, value: w.accuracy_threshold_m ?? 100 }),
    dwell_seconds: input({ type: 'number', min: 0, max: 3600, step: 1, value: w.dwell_seconds ?? 120 }),
    exit_debounce_seconds: input({ type: 'number', min: 0, max: 7200, step: 1, value: w.exit_debounce_seconds ?? 300 }),
    wifi_ssid: input({ value: w.wifi_ssid || '', placeholder: 'e.g. Meridian-Staff', maxlength: 64 }),
    timezone: input({ value: w.timezone || 'Africa/Johannesburg' }),
  };
  const sw = {
    coordinates_confirmed: checkbox('Coordinates confirmed by management', { checked: Boolean(w.coordinates_confirmed), 'data-testid': 'wp-coords-confirmed' }),
    entry_detection_enabled: switchInput('Entry (arrival) detection', { checked: w.entry_detection_enabled !== false }),
    exit_detection_enabled: switchInput('Exit (departure) detection', { checked: w.exit_detection_enabled !== false }),
    wifi_verification_enabled: switchInput('Wi-Fi verification', { checked: Boolean(w.wifi_verification_enabled), 'data-testid': 'wp-wifi' }),
    wifi_required: switchInput('Wi-Fi match required (otherwise event goes under review)', { checked: Boolean(w.wifi_required) }),
    active: switchInput('Workplace active', { checked: w.active !== false }),
  };
  const bssids = Array.isArray(w.wifi_bssids) ? [...w.wifi_bssids] : [];
  const bssidInput = input({ placeholder: 'aa:bb:cc:dd:ee:ff', class: 'input mono', 'aria-label': 'Add BSSID' });
  const bssidAdd = button('Add', { kind: 'secondary', size: 'sm', onClick: addBssid });
  const bssidList = el('div', { class: 'tag-list' });
  function renderBssids() {
    replace(bssidList, bssids.length ? bssids.map((b, i) => el('span', { class: 'tag' }, b, canWrite ? el('button', { type: 'button', 'aria-label': `Remove ${b}`, onClick: () => { bssids.splice(i, 1); renderBssids(); } }, '×') : null)) : el('span', { class: 'muted small' }, 'No BSSIDs — SSID match only'));
  }
  function addBssid() {
    const n = normaliseBssid(bssidInput.value);
    if (!n) { toast('Enter a BSSID like aa:bb:cc:dd:ee:ff', 'warning'); bssidInput.focus(); return; }
    if (!bssids.includes(n)) bssids.push(n);
    bssidInput.value = ''; renderBssids();
  }
  bssidInput.addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); addBssid(); } });
  renderBssids();

  const warn = el('div', { class: 'banner warn coords-warning', role: 'alert', 'data-testid': 'coords-warning' }, icon('warning', 20),
    el('div', {}, el('strong', {}, 'Coordinates not confirmed — placeholder values'), el('div', { class: 'small' }, 'The latitude/longitude have not been confirmed by management. Geofence detection uses these values; verify the real site coordinates, update them and tick "Coordinates confirmed".')));
  const updateWarn = () => warn.classList.toggle('hidden', sw.coordinates_confirmed.input.checked);
  sw.coordinates_confirmed.input.addEventListener('change', updateWarn); updateWarn();

  const wifiSection = el('div', { class: 'stack' }, field('SSID', f.wifi_ssid), el('div', { class: 'field' }, el('span', { class: 'label' }, 'BSSIDs (access point MAC addresses)'), bssidList, canWrite ? el('div', { class: 'row' }, bssidInput, bssidAdd) : null), sw.wifi_required.el);
  const updateWifi = () => { wifiSection.style.opacity = sw.wifi_verification_enabled.input.checked ? '1' : '0.5'; };
  sw.wifi_verification_enabled.input.addEventListener('change', updateWifi); updateWifi();

  const err = formError('');
  const submit = button(isNew ? 'Create workplace' : 'Save changes', { type: 'submit', attrs: { 'data-testid': 'wp-save' } });
  const form = el('form', { class: 'stack', novalidate: true },
    warn,
    el('div', { class: 'form-grid' }, field('Name', f.name), field('Address', f.address),
      field('Latitude', f.latitude, { hint: '-90 … 90' }), field('Longitude', f.longitude, { hint: '-180 … 180' }),
      el('div', { class: 'full' }, sw.coordinates_confirmed.el),
      field('Geofence radius (m)', f.radius_m, { hint: '30 – 5000' }), field('Accuracy threshold (m)', f.accuracy_threshold_m, { hint: 'Fixes less accurate than this are not trusted' }),
      field('Dwell before arrival (s)', f.dwell_seconds, { hint: 'Avoids events for people driving past' }), field('Exit debounce (s)', f.exit_debounce_seconds, { hint: 'Location re-checked before a departure' }),
      el('div', {}, sw.entry_detection_enabled.el), el('div', {}, sw.exit_detection_enabled.el),
      field('Timezone', f.timezone), el('div', {}, sw.active.el)),
    el('div', { class: 'policy-section' }, el('h4', {}, 'Wi-Fi verification'), sw.wifi_verification_enabled.el, wifiSection),
    err,
    !isNew && w.updated_at ? el('p', { class: 'muted small' }, `Last updated ${fmtDateTime(w.updated_at)}`) : null,
    canWrite ? el('div', { class: 'form-actions' }, submit) : el('div', { class: 'form-info' }, 'Your role can view but not change workplace configuration.'));
  if (!canWrite) form.querySelectorAll('input, select, textarea, button').forEach((n) => { n.disabled = true; });

  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    showFormError(err, '');
    const payload = {
      name: f.name.value.trim(), address: f.address.value.trim() || null,
      latitude: num(f.latitude.value), longitude: num(f.longitude.value), coordinates_confirmed: sw.coordinates_confirmed.input.checked,
      radius_m: num(f.radius_m.value), accuracy_threshold_m: num(f.accuracy_threshold_m.value),
      entry_detection_enabled: sw.entry_detection_enabled.input.checked, exit_detection_enabled: sw.exit_detection_enabled.input.checked,
      dwell_seconds: num(f.dwell_seconds.value), exit_debounce_seconds: num(f.exit_debounce_seconds.value),
      wifi_verification_enabled: sw.wifi_verification_enabled.input.checked, wifi_ssid: f.wifi_ssid.value.trim() || null, wifi_bssids: bssids,
      wifi_required: sw.wifi_required.input.checked, timezone: f.timezone.value.trim() || 'Africa/Johannesburg', active: sw.active.input.checked,
    };
    if (!payload.name) { showFormError(err, 'Name is required.'); return; }
    if (payload.latitude === null || payload.longitude === null) { showFormError(err, 'Latitude and longitude are required.'); return; }
    if (payload.wifi_required && !payload.wifi_verification_enabled) { showFormError(err, 'Enable Wi-Fi verification before requiring a Wi-Fi match.'); return; }
    setBusy(submit, true, 'Saving…');
    try {
      let saved;
      if (isNew) saved = await api.post('/admin/workplaces', payload);
      else {
        const diff = {};
        Object.entries(payload).forEach(([k, v]) => { if (JSON.stringify(v) !== JSON.stringify(w[k] === undefined ? null : w[k])) diff[k] = v; });
        if (!Object.keys(diff).length) { toast('No changes to save.', 'info'); return; }
        saved = await api.patch(`/admin/workplaces/${w.id}`, diff);
      }
      toast(isNew ? 'Workplace created. Devices receive the new configuration on their next sync.' : 'Workplace saved. Configuration version bumped; devices update on next sync.', 'success', 6000);
      onSaved(saved);
    } catch (ex) { showFormError(err, ex); } finally { setBusy(submit, false, 'Saving…'); }
  });
  return card(isNew ? 'New workplace' : w.name, form, { className: 'stack' });
}

// ---------------- Policy ----------------
const POLICY_FIELDS = [
  { section: 'Working day', fields: [
    ['workday_start_local', 'Workday start (local)', 'time'], ['workday_end_local', 'Workday end (local)', 'time'], ['late_after_local', 'Late after (local)', 'time'],
    ['grace_minutes', 'Grace minutes', 'number', { min: 0, max: 240 }], ['standard_day_minutes', 'Standard day (minutes)', 'number', { min: 60, max: 1440 }],
    ['break_minutes', 'Break (minutes)', 'number', { min: 0, max: 480 }], ['overtime_after_minutes', 'Overtime after (minutes)', 'number', { min: 60, max: 1440 }]] },
  { section: 'Confirmations & timing', fields: [
    ['pending_expiry_hours', 'Pending expiry (hours)', 'number', { min: 1, max: 168 }], ['missing_clock_out_flag_local', 'Flag missing clock-out at (local)', 'time'],
    ['max_future_skew_seconds', 'Max future skew (s)', 'number', { min: 0, max: 3600 }], ['max_offline_age_hours', 'Max offline age (hours)', 'number', { min: 1, max: 720 }],
    ['online_skew_tolerance_seconds', 'Online skew tolerance (s)', 'number', { min: 0, max: 86400 }], ['duplicate_window_seconds', 'Duplicate window (s)', 'number', { min: 0, max: 86400 }],
    ['official_time_source', 'Official time source', 'select', { options: ['server_received', 'device_detected'] }],
    ['min_verification_score_for_auto_confirm', 'Min verification score for auto-confirm', 'number', { min: 0, max: 1, step: 0.05 }]] },
  { section: 'Devices', fields: [
    ['max_active_devices_per_user', 'Max active devices per user', 'number', { min: 1, max: 5 }],
    ['device_replacement_requires_approval', 'Device replacement requires approval', 'bool'], ['require_integrity_verdict', 'Require Play Integrity verdict', 'bool']] },
  { section: 'Retention (days)', fields: [
    ['retention_days_attendance', 'Attendance records', 'number', { min: 30, max: 3650 }], ['retention_days_location', 'Location data', 'number', { min: 1, max: 3650 }], ['retention_days_audit', 'Audit logs', 'number', { min: 30, max: 3650 }]] },
];
const DAYS = [[1, 'Mon'], [2, 'Tue'], [3, 'Wed'], [4, 'Thu'], [5, 'Fri'], [6, 'Sat'], [7, 'Sun']];

async function renderPolicy(body, canWrite) {
  replace(body, el('div', { class: 'card' }, skeleton({ rows: 5, cols: 3, tall: true })));
  let p;
  try { p = await api.get('/admin/policy'); } catch (err) { replace(body, errorState(err, () => renderPolicy(body, canWrite))); return; }
  const controls = {};
  const sections = POLICY_FIELDS.map((s) => el('div', { class: 'policy-section' }, el('h4', {}, s.section), el('div', { class: 'policy-grid' }, s.fields.map(([key, label, type, opts = {}]) => {
    let c;
    if (type === 'bool') { c = switchInput(label, { checked: Boolean(p[key]) }); controls[key] = { get: () => c.input.checked }; return el('div', { style: { alignSelf: 'end' } }, c.el); }
    if (type === 'select') { c = select(opts.options.map((o) => ({ value: o, label: titleCase(o) })), {}, p[key]); controls[key] = { get: () => c.value }; return field(label, c); }
    c = input({ type, value: p[key] ?? '', min: opts.min, max: opts.max, step: opts.step || (type === 'number' ? 1 : undefined), 'data-policy': key });
    controls[key] = { get: () => (type === 'number' ? num(c.value) : c.value) };
    return field(label, c);
  }))));
  const dayBoxes = DAYS.map(([n, lab]) => { const cb = checkbox(lab, { checked: (p.working_days || []).includes(n), value: n }); return cb; });
  const quiet = textarea({ value: p.notification_quiet_hours ? JSON.stringify(p.notification_quiet_hours) : '', placeholder: 'null (disabled) or JSON such as {"start": "22:00", "end": "06:00"}', rows: 2, class: 'input mono' });
  const err = formError('');
  const submit = button('Save policy', { type: 'submit', attrs: { 'data-testid': 'policy-save' } });
  const form = el('form', { class: 'stack', novalidate: true },
    el('div', { class: 'banner warn' }, icon('warning', 20), el('div', {}, el('strong', {}, 'Defaults for management review'), el('div', { class: 'small' }, 'These values are system defaults, not an assertion of Meridian Group employment rules. Review and adjust them with management before relying on late/overtime figures.'))),
    sections,
    el('div', { class: 'policy-section' }, el('h4', {}, 'Working days'), el('div', { class: 'days' }, dayBoxes.map((d) => d.el))),
    el('div', { class: 'policy-section' }, el('h4', {}, 'Notification quiet hours'), field('Quiet hours (JSON)', quiet, { hint: 'Leave empty to disable quiet hours.' })),
    err,
    el('p', { class: 'muted small' }, `Last updated ${p.updated_at ? fmtDateTime(p.updated_at) : 'never'}${p.updated_by ? ` by ${p.updated_by}` : ''}`),
    canWrite ? el('div', { class: 'form-actions' }, submit) : el('div', { class: 'form-info' }, 'Your role can view but not change the policy.'));
  if (!canWrite) form.querySelectorAll('input, select, textarea, button').forEach((n) => { n.disabled = true; });
  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    showFormError(err, '');
    const payload = {};
    Object.entries(controls).forEach(([k, c]) => { payload[k] = c.get(); });
    payload.working_days = dayBoxes.filter((d) => d.input.checked).map((d) => Number(d.input.value));
    if (!payload.working_days.length) { showFormError(err, 'Select at least one working day.'); return; }
    const q = quiet.value.trim();
    if (!q || q === 'null') payload.notification_quiet_hours = null;
    else { try { payload.notification_quiet_hours = JSON.parse(q); if (typeof payload.notification_quiet_hours !== 'object') throw new Error(); } catch (x) { showFormError(err, 'Quiet hours must be valid JSON object or empty.'); return; } }
    const diff = {};
    Object.entries(payload).forEach(([k, v]) => { if (v === null && typeof p[k] === 'number') return; if (JSON.stringify(v) !== JSON.stringify(p[k] === undefined ? null : p[k])) diff[k] = v; });
    if (!Object.keys(diff).length) { toast('No changes to save.', 'info'); return; }
    setBusy(submit, true, 'Saving…');
    try { await api.patch('/admin/policy', diff); toast('Policy saved.', 'success'); renderPolicy(body, canWrite); }
    catch (ex) { showFormError(err, ex); } finally { setBusy(submit, false, 'Saving…'); }
  });
  replace(body, card('Attendance policy', form));
}

// ---------------- Departments ----------------
async function renderDepartments(body, canWrite) {
  replace(body, el('div', { class: 'card' }, skeleton({ rows: 4, cols: 3 })));
  let items;
  try { items = await getDepartments(true); } catch (err) { replace(body, errorState(err, () => renderDepartments(body, canWrite))); return; }
  const table = dataTable([
    { key: 'name', label: 'Name', render: (d) => el('span', { class: 'cell-primary' }, d.name) },
    { key: 'code', label: 'Code', render: (d) => el('span', { class: 'mono' }, d.code) },
    { key: 'active', label: 'Active', render: (d) => boolChip(d.active !== false, 'Active', 'Inactive') },
    { key: 'employee_count', label: 'Employees', numeric: true, render: (d) => (d.employee_count ?? d.user_count ?? '—') },
  ], {
    rows: items, caption: 'Departments', emptyText: 'No departments yet.',
    rowActions: canWrite ? (d) => [
      button('Edit', { kind: 'secondary', size: 'sm', icon: 'edit', onClick: () => departmentModal(d, () => renderDepartments(body, canWrite)) }),
      button(d.active === false ? 'Activate' : 'Deactivate', { kind: 'secondary', size: 'sm', onClick: async () => { try { await api.patch(`/admin/departments/${d.id}`, { active: d.active === false }); invalidateDepartments(); toast('Department updated.', 'success'); renderDepartments(body, canWrite); } catch (e) { handleError(e); } } }),
    ] : null,
  });
  replace(body, card('Departments', el('div', {}, table), { actions: canWrite ? button('New department', { icon: 'plus', size: 'sm', onClick: () => departmentModal(null, () => renderDepartments(body, canWrite)), attrs: { 'data-testid': 'dept-new' } }) : null }));
}

function departmentModal(d, done) {
  const name = input({ value: d?.name || '', required: true, 'data-testid': 'dept-name' });
  const code = input({ value: d?.code || '', required: true, maxlength: 20, class: 'input mono', 'data-testid': 'dept-code' });
  const err = formError('');
  const submit = button(d ? 'Save' : 'Create', { type: 'submit', attrs: { 'data-testid': 'dept-save' } });
  const cancel = button('Cancel', { kind: 'secondary' });
  const form = el('form', { class: 'stack', novalidate: true }, field('Name', name), field('Code', code, { hint: 'Short unique code, e.g. FIN' }), err, el('div', { class: 'form-actions' }, cancel, submit));
  const m = openModal({ title: d ? `Edit ${d.name}` : 'New department', body: form });
  cancel.addEventListener('click', () => m.close());
  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    const payload = { name: name.value.trim(), code: code.value.trim().toUpperCase() };
    if (!payload.name || !payload.code) { showFormError(err, 'Name and code are required.'); return; }
    setBusy(submit, true, 'Saving…');
    try {
      if (d) await api.patch(`/admin/departments/${d.id}`, cleanQuery({ name: payload.name !== d.name ? payload.name : '', code: payload.code !== d.code ? payload.code : '' }));
      else await api.post('/admin/departments', payload);
      invalidateDepartments(); m.close(); toast(d ? 'Department saved.' : 'Department created.', 'success'); done();
    } catch (ex) { showFormError(err, ex); } finally { setBusy(submit, false, 'Saving…'); }
  });
}
