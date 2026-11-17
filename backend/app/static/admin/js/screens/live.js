// Live attendance: today's status for every employee, auto-refreshed every 30 s.

import { api } from '../api.js';
import { el, replace, pageHead, dataTable, statusChip, skeleton, errorState, button, field } from '../ui.js';
import { fmtMinutes, fmtRelative, fmtWorkDate, statusLabel } from '../format.js';
import { replaceQuery } from '../router.js';
import { departmentSelect, countByStatus, employeeLink, cleanQuery } from '../data.js';

const REFRESH_MS = 30000;
const STATUS_ORDER = ['clocked_in', 'pending_arrival', 'pending_departure', 'missing_clock_out', 'clocked_out', 'not_started'];

export function render(container, { setTitle, query }) {
  setTitle('Live attendance', 'Refreshes automatically every 30 seconds');
  const state = { department_id: query.department_id || '', status: '' };
  let lastLoaded = null;
  let timer = null;
  let tick = null;
  let seq = 0;

  const note = el('span', { class: 'refresh-note', 'aria-live': 'polite' }, 'Loading…');
  const refreshBtn = button('Refresh now', { kind: 'secondary', size: 'sm', icon: 'refresh', onClick: () => load() });
  const deptHost = el('div', { class: 'field' });
  const summary = el('div', { class: 'live-summary', 'data-testid': 'live-summary' });
  const tableHost = el('div', { class: 'card flush' });
  replace(container, pageHead('Live attendance', '', [note, refreshBtn]), el('div', { class: 'card' }, el('div', { class: 'filter-bar' }, deptHost, el('div', { class: 'field', style: { flex: '2 1 300px', maxWidth: 'none' } }, el('span', { class: 'label' }, 'Filter by status'), summary))), tableHost);

  departmentSelect({ 'aria-label': 'Department filter' }, state.department_id).then((sel) => {
    sel.addEventListener('change', () => { state.department_id = sel.value; replaceQuery(cleanQuery(state)); load(); });
    replace(deptHost, field('Department', sel));
  });

  function renderSummary(items) {
    const counts = countByStatus(items);
    replace(summary,
      el('button', { type: 'button', class: `chip ${state.status ? 'grey' : 'blue'}`, 'aria-pressed': String(!state.status), onClick: () => { state.status = ''; renderTable(items); } }, `All ${items.length}`),
      STATUS_ORDER.filter((s) => counts[s]).map((s) => {
        const c = statusChip(s, `${statusLabel(s)} ${counts[s]}`);
        const b = el('button', { type: 'button', class: c.className, 'aria-pressed': String(state.status === s), style: { cursor: 'pointer' }, onClick: () => { state.status = state.status === s ? '' : s; renderSummary(items); renderTable(items); } }, c.textContent);
        return b;
      }));
  }

  let currentItems = [];
  function renderTable(items) {
    const rows = state.status ? items.filter((r) => r.status === state.status) : items;
    const table = dataTable([
      { key: 'employee', label: 'Employee', render: (r) => el('div', {}, employeeLink(r.employee), r.employee?.department ? el('div', { class: 'cell-sub' }, r.employee.department.name) : null) },
      { key: 'status', label: 'Status', render: (r) => statusChip(r.status) },
      { key: 'clock_in_local', label: 'Clock in', render: (r) => r.clock_in_local || el('span', { class: 'muted' }, '—') },
      { key: 'clock_out_local', label: 'Clock out', render: (r) => r.clock_out_local || el('span', { class: 'muted' }, '—') },
      { key: 'worked_minutes', label: 'Worked', numeric: true, render: (r) => fmtMinutes(r.worked_minutes) },
      { key: 'is_late', label: 'Late', render: (r) => (r.is_late ? statusChip('late', 'Late') : el('span', { class: 'muted' }, 'No')) },
      { key: 'pending_event_id', label: 'Pending', render: (r) => (r.pending_event_id ? el('a', { href: `#/pending?event_id=${r.pending_event_id}` }, 'Awaiting confirmation') : el('span', { class: 'muted' }, '—')) },
      { key: 'last_event_at', label: 'Last event', render: (r) => el('span', { class: 'muted' }, fmtRelative(r.last_event_at)) },
    ], { rows, caption: 'Live attendance', emptyText: items.length ? 'No employees with this status.' : 'No active employees to show.' });
    replace(tableHost, el('div', { style: { padding: '8px 12px 12px' } }, table));
  }

  async function load() {
    const my = ++seq;
    if (!currentItems.length) replace(tableHost, el('div', { style: { padding: '16px 20px' } }, skeleton({ rows: 6, cols: 6 })));
    refreshBtn.disabled = true;
    try {
      const data = await api.get('/admin/attendance/live', cleanQuery({ department_id: state.department_id }));
      if (my !== seq) return;
      currentItems = data.items || [];
      lastLoaded = Date.now();
      setTitle('Live attendance', `${fmtWorkDate(data.work_date)} · refreshes every 30 seconds`);
      renderSummary(currentItems);
      renderTable(currentItems);
    } catch (err) {
      if (my !== seq) return;
      replace(tableHost, errorState(err, load));
      note.textContent = 'Last refresh failed';
    } finally {
      refreshBtn.disabled = false;
      clearTimeout(timer);
      timer = setTimeout(load, REFRESH_MS);
    }
  }
  tick = setInterval(() => { if (lastLoaded) note.textContent = `Updated ${Math.round((Date.now() - lastLoaded) / 1000)}s ago`; }, 1000);
  const onVisible = () => { if (document.visibilityState === 'visible') load(); };
  document.addEventListener('visibilitychange', onVisible);
  load();
  return () => { seq += 1; clearTimeout(timer); clearInterval(tick); document.removeEventListener('visibilitychange', onVisible); };
}
