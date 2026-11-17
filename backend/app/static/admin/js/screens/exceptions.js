// Exceptions: anomalies flagged by the server (missing clock-outs, late arrivals, …).

import { api } from '../api.js';
import { el, replace, pageHead, dataTable, select, field, skeleton, errorState, statusChip } from '../ui.js';
import { fmtWorkDate, fmtDateTime, titleCase } from '../format.js';
import { replaceQuery } from '../router.js';
import { departmentSelect, dateRange, rangeFromQuery, cleanQuery, parsePage, employeeLink, refreshButton } from '../data.js';
import { openSession } from './records.js';

const PAGE_SIZE = 50;
export const KINDS = ['missing_clock_out', 'late_arrival', 'under_review', 'expired_confirmation', 'dismissed_detection', 'absent', 'rejected_event', 'outside_geofence'];
const KIND_COLOR = { missing_clock_out: 'orange', late_arrival: 'orange', under_review: 'orange', expired_confirmation: 'red', dismissed_detection: 'grey', absent: 'red', rejected_event: 'red', outside_geofence: 'orange' };

export function render(container, { setTitle, query }) {
  setTitle('Exceptions', 'Items that need attention');
  const range = rangeFromQuery(query, 'last_7');
  const state = { ...range, department_id: query.department_id || '', kind: KINDS.includes(query.kind) ? query.kind : '', page: parsePage(query.page) };
  const dr = dateRange({ from: state.from, to: state.to, onChange: ({ from, to }) => { state.from = from; state.to = to; state.page = 1; load(); } });
  const kindSel = select([{ value: '', label: 'All kinds' }, ...KINDS.map((k) => ({ value: k, label: titleCase(k) }))], { 'aria-label': 'Exception kind' }, state.kind);
  kindSel.addEventListener('change', () => { state.kind = kindSel.value; state.page = 1; load(); });
  const deptHost = el('div', { class: 'field' });
  departmentSelect({ 'aria-label': 'Department filter' }, state.department_id).then((sel) => {
    sel.addEventListener('change', () => { state.department_id = sel.value; state.page = 1; load(); });
    replace(deptHost, field('Department', sel));
  });
  const tableHost = el('div', { class: 'card flush' });
  replace(container, pageHead('Exceptions', '', [refreshButton(() => load())]),
    el('div', { class: 'card stack' }, dr.el, el('div', { class: 'filter-bar' }, field('Kind', kindSel), deptHost)), tableHost);

  let seq = 0;
  async function load() {
    const my = ++seq;
    replaceQuery(cleanQuery({ ...state, page: state.page > 1 ? state.page : '' }));
    replace(tableHost, el('div', { style: { padding: '16px 20px' } }, skeleton({ rows: 6, cols: 5 })));
    let data;
    try { data = await api.get('/admin/attendance/exceptions', cleanQuery({ from: state.from, to: state.to, department_id: state.department_id, kind: state.kind, page: state.page, page_size: PAGE_SIZE })); }
    catch (err) { if (my === seq) replace(tableHost, errorState(err, load)); return; }
    if (my !== seq) return;
    const table = dataTable([
      { key: 'kind', label: 'Kind', render: (x) => statusChip(x.kind, titleCase(x.kind)) },
      { key: 'work_date', label: 'Date', render: (x) => el('span', { class: 'nowrap' }, fmtWorkDate(x.work_date)) },
      { key: 'employee', label: 'Employee', render: (x) => el('div', {}, employeeLink(x.employee), x.employee?.department ? el('div', { class: 'cell-sub' }, x.employee.department.name) : null) },
      { key: 'detail', label: 'Detail' },
      { key: 'at', label: 'At', render: (x) => el('span', { class: 'muted nowrap' }, fmtDateTime(x.at)) },
      { key: 'links', label: 'Open', render: (x) => el('div', { class: 'row', style: { gap: '6px' } },
        x.session_id ? el('button', { type: 'button', class: 'link-btn', onClick: () => openSession(x.session_id) }, 'Session') : null,
        x.event_id ? el('a', { href: `#/pending?event_id=${x.event_id}` }, 'Event') : null) },
    ], {
      rows: (data.items || []).map((x) => ({ ...x, kind: x.kind })), caption: 'Exceptions',
      rowClass: (x) => KIND_COLOR[x.kind] || '',
      emptyText: 'No exceptions in this range.',
      pager: { page: data.page || state.page, pageSize: data.page_size || PAGE_SIZE, total: data.total || 0, onPage: (p) => { state.page = p; load(); } },
    });
    replace(tableHost, el('div', { style: { padding: '8px 12px 12px' } }, table));
  }
  load();
  return () => { seq += 1; };
}
