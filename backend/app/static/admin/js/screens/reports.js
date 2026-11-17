// Reports & analytics: preview as table, download CSV / XLSX / PDF.

import { api } from '../api.js';
import { session } from '../session.js';
import { el, replace, pageHead, button, dataTable, select, field, skeleton, errorState, emptyState, toast, handleError, setBusy } from '../ui.js';
import { fmtDateTime, fmtNumber, titleCase, fullName } from '../format.js';
import { replaceQuery } from '../router.js';
import { departmentSelect, employeePicker, dateRange, rangeFromQuery, cleanQuery } from '../data.js';

export const REPORTS = [
  { key: 'daily', label: 'Daily attendance', desc: 'One row per employee per day' },
  { key: 'weekly', label: 'Weekly summary', desc: 'Hours and days per employee per week' },
  { key: 'monthly', label: 'Monthly summary', desc: 'Hours and days per employee per month' },
  { key: 'employee', label: 'Employee report', desc: 'Detailed history for one employee' },
  { key: 'department', label: 'Department report', desc: 'Attendance by department' },
  { key: 'late-arrivals', label: 'Late arrivals', desc: 'Arrivals after the late threshold' },
  { key: 'absences', label: 'Absences', desc: 'Working days without a session' },
  { key: 'missing-clock-outs', label: 'Missing clock-outs', desc: 'Sessions never closed' },
  { key: 'manual-attendance', label: 'Manual attendance', desc: 'Manual requests and admin sessions' },
  { key: 'pending-confirmations', label: 'Pending confirmations', desc: 'Detections not confirmed' },
  { key: 'working-hours', label: 'Working hours', desc: 'Worked, break and overtime minutes' },
];
const FORMATS = [{ key: 'csv', label: 'CSV' }, { key: 'xlsx', label: 'Excel (XLSX)' }, { key: 'pdf', label: 'PDF' }];

export function render(container, { setTitle, query }) {
  setTitle('Reports & analytics', 'Preview on screen, then download');
  const canExport = session.can('reports:export');
  const range = rangeFromQuery(query, 'this_month');
  const state = { report: REPORTS.some((r) => r.key === query.report) ? query.report : 'daily', ...range, employee_id: '', department_id: query.department_id || '', status: query.status || '' };

  const picker = el('div', { class: 'report-picker', role: 'radiogroup', 'aria-label': 'Report type' });
  function renderPicker() {
    replace(picker, REPORTS.map((r) => el('button', { type: 'button', class: 'report-btn', role: 'radio', 'aria-checked': String(r.key === state.report), 'aria-pressed': String(r.key === state.report), dataset: { report: r.key }, onClick: () => { state.report = r.key; renderPicker(); replaceQuery(cleanQuery(state)); } },
      el('strong', {}, r.label), el('span', {}, r.desc))));
  }
  renderPicker();

  const dr = dateRange({ from: state.from, to: state.to, onChange: ({ from, to }) => { state.from = from; state.to = to; } });
  const emp = employeePicker({ onSelect: (u) => { state.employee_id = u ? u.id : ''; } });
  const statusSel = select([{ value: '', label: 'Any status' }, { value: 'open', label: 'Open' }, { value: 'closed', label: 'Closed' }, { value: 'missing_clock_out', label: 'Missing clock-out' }], { 'aria-label': 'Status filter' }, state.status);
  statusSel.addEventListener('change', () => { state.status = statusSel.value; });
  const deptHost = el('div', { class: 'field' });
  departmentSelect({ 'aria-label': 'Department filter' }, state.department_id).then((sel) => { sel.addEventListener('change', () => { state.department_id = sel.value; }); replace(deptHost, field('Department', sel)); });

  const previewBtn = button('Preview', { icon: 'eye', onClick: () => preview(), attrs: { 'data-testid': 'report-preview' } });
  const dlButtons = FORMATS.map((f) => button(f.label, { kind: 'secondary', icon: 'download', onClick: () => download(f.key), disabled: !canExport, title: canExport ? `Download ${f.label}` : 'Requires reports:export', attrs: { 'data-testid': `report-download-${f.key}` } }));
  const result = el('div', { class: 'stack' }, el('div', { class: 'card' }, emptyState('No preview yet', 'Choose a report and date range, then press Preview.')));

  replace(container, pageHead('Reports & analytics', '', []),
    el('div', { class: 'card stack' }, el('h3', {}, 'Report'), picker),
    el('div', { class: 'card stack' }, el('h3', {}, 'Filters'), dr.el,
      el('div', { class: 'filter-bar' }, el('div', { class: 'field wide' }, el('span', { class: 'label' }, 'Employee'), emp.el), deptHost, field('Status', statusSel)),
      el('div', { class: 'row between' }, previewBtn, el('div', { class: 'btn-group' }, dlButtons))),
    result);

  function params(format) {
    const v = dr.value();
    return cleanQuery({ from: v.from, to: v.to, employee_id: state.employee_id, department_id: state.department_id, status: state.status, format });
  }

  let seq = 0;
  async function preview() {
    const my = ++seq;
    const v = dr.value();
    if (!v.from || !v.to || v.from > v.to) { toast('Choose a valid date range.', 'warning'); return; }
    replace(result, el('div', { class: 'card' }, skeleton({ rows: 6, cols: 5 })));
    setBusy(previewBtn, true, 'Loading…');
    try {
      const data = await api.get(`/admin/reports/${state.report}`, params('json'));
      if (my !== seq) return;
      renderPreview(data);
    } catch (err) { if (my === seq) replace(result, errorState(err, preview)); }
    finally { setBusy(previewBtn, false, 'Loading…'); }
  }

  function renderPreview(data) {
    const cols = Array.isArray(data.columns) && data.columns.length ? data.columns : Object.keys((data.rows || [])[0] || {}).map((k) => ({ key: k, label: titleCase(k) }));
    const rows = data.rows || [];
    const summary = data.summary && typeof data.summary === 'object' ? Object.entries(data.summary) : [];
    const fmtVal = (v) => (v === null || v === undefined ? '—' : typeof v === 'number' ? fmtNumber(v) : typeof v === 'boolean' ? (v ? 'Yes' : 'No') : typeof v === 'object' ? (v.first_name ? fullName(v) : JSON.stringify(v)) : String(v));
    const table = dataTable(cols.map((c) => ({ key: c.key, label: c.label || titleCase(c.key), render: (r) => fmtVal(r[c.key]) })), { rows, caption: `${state.report} report`, emptyText: 'The report has no rows for this range.' });
    replace(result,
      el('div', { class: 'card stack', 'data-testid': 'report-result' },
        el('div', { class: 'row between' },
          el('div', {}, el('h3', {}, `${(REPORTS.find((r) => r.key === data.report) || {}).label || titleCase(data.report || state.report)}`), el('p', { class: 'muted small' }, `${data.from || ''} → ${data.to || ''} · generated ${fmtDateTime(data.generated_at)} · ${fmtNumber(rows.length)} row${rows.length === 1 ? '' : 's'}`)),
          el('div', { class: 'btn-group' }, FORMATS.map((f) => button(f.label, { kind: 'secondary', size: 'sm', icon: 'download', onClick: () => download(f.key), disabled: !canExport })))),
        summary.length ? el('div', { class: 'summary-row' }, summary.map(([k, v]) => el('div', { class: 'card' }, el('div', { class: 'stat-label' }, titleCase(k)), el('div', { class: 'stat-value' }, fmtVal(v))))) : null,
        el('div', { class: 'report-table-wrap' }, table)));
  }

  async function download(format) {
    const v = dr.value();
    if (!v.from || !v.to || v.from > v.to) { toast('Choose a valid date range.', 'warning'); return; }
    try {
      const name = await api.download(`/admin/reports/${state.report}`, params(format), `${state.report}-${v.from}-${v.to}.${format}`);
      toast(`Downloaded ${name}.`, 'success');
    } catch (err) { handleError(err); }
  }

  return () => { seq += 1; };
}
