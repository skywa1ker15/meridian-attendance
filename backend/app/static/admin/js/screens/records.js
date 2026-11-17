// Attendance records: sessions with filters, detail drawer, corrections, manual sessions.

import { api } from '../api.js';
import { session } from '../session.js';
import { el, replace, pageHead, button, dataTable, select, field, input, textarea, statusChip, skeleton, errorState, openDrawer, openModal, formError, showFormError, setBusy, toast, handleError, kv, loading } from '../ui.js';
import { fmtWorkDate, fmtMinutes, fmtDateTime, fullName, localInputToUtcIso, utcIsoToLocalInput, todayLocal } from '../format.js';
import { replaceQuery } from '../router.js';
import { departmentSelect, employeePicker, dateRange, rangeFromQuery, cleanQuery, parsePage, employeeLink } from '../data.js';
import { timelineItem } from './event-view.js';

const PAGE_SIZE = 50;

export function render(container, { setTitle, query, navigate }) {
  setTitle('Attendance records', 'Confirmed sessions · server is authoritative');
  const canReview = session.can('attendance:review');
  const range = rangeFromQuery(query, 'last_7');
  const state = { ...range, employee_id: query.employee_id || '', department_id: query.department_id || '', status: query.status || '', late: query.late || '', manual: query.manual || '', page: parsePage(query.page) };
  const initialEmployee = state.employee_id ? { id: state.employee_id, first_name: query.employee_name || 'Selected employee', last_name: '', employee_number: '' } : null;

  const dr = dateRange({ from: state.from, to: state.to, onChange: ({ from, to }) => { state.from = from; state.to = to; state.page = 1; load(); } });
  const emp = employeePicker({ initial: initialEmployee, onSelect: (u) => { state.employee_id = u ? u.id : ''; state.page = 1; load(); } });
  const statusSel = select([{ value: '', label: 'All statuses' }, { value: 'open', label: 'Open' }, { value: 'closed', label: 'Closed' }, { value: 'missing_clock_out', label: 'Missing clock-out' }], { 'aria-label': 'Session status' }, state.status);
  const lateSel = select([{ value: '', label: 'Late and on time' }, { value: 'true', label: 'Late only' }, { value: 'false', label: 'On time only' }], { 'aria-label': 'Late filter' }, state.late);
  const manualSel = select([{ value: '', label: 'All sources' }, { value: 'true', label: 'Manual / admin only' }, { value: 'false', label: 'Detected only' }], { 'aria-label': 'Manual filter' }, state.manual);
  const deptHost = el('div', { class: 'field' });
  statusSel.addEventListener('change', () => { state.status = statusSel.value; state.page = 1; load(); });
  lateSel.addEventListener('change', () => { state.late = lateSel.value; state.page = 1; load(); });
  manualSel.addEventListener('change', () => { state.manual = manualSel.value; state.page = 1; load(); });
  departmentSelect({ 'aria-label': 'Department filter' }, state.department_id).then((sel) => {
    sel.addEventListener('change', () => { state.department_id = sel.value; state.page = 1; load(); });
    replace(deptHost, field('Department', sel));
  });

  const filters = el('div', { class: 'stack' },
    dr.el,
    el('div', { class: 'filter-bar' },
      el('div', { class: 'field wide' }, el('span', { class: 'label' }, 'Employee'), emp.el),
      deptHost, field('Status', statusSel), field('Late', lateSel), field('Source', manualSel)));
  const tableHost = el('div', { class: 'card flush' });
  const actions = canReview ? [button('Add manual session', { icon: 'plus', onClick: () => openAddSession(load), attrs: { 'data-testid': 'add-session' } })] : [];
  replace(container, pageHead('Attendance records', '', actions), el('div', { class: 'card' }, filters), tableHost);

  let seq = 0;
  async function load() {
    const my = ++seq;
    replaceQuery(cleanQuery({ ...state, page: state.page > 1 ? state.page : '', employee_name: emp.value() ? fullName(emp.value()) : query.employee_name }));
    replace(tableHost, el('div', { style: { padding: '16px 20px' } }, skeleton({ rows: 8, cols: 6 })));
    let data;
    try {
      data = await api.get('/admin/attendance/sessions', cleanQuery({ from: state.from, to: state.to, employee_id: state.employee_id, department_id: state.department_id, status: state.status, late: state.late, manual: state.manual, page: state.page, page_size: PAGE_SIZE }));
    } catch (err) { if (my === seq) replace(tableHost, errorState(err, load)); return; }
    if (my !== seq) return;
    const table = dataTable([
      { key: 'work_date', label: 'Date', render: (s) => el('span', { class: 'nowrap' }, fmtWorkDate(s.work_date)) },
      { key: 'employee', label: 'Employee', render: (s) => el('div', {}, employeeLink(s.employee), s.employee?.department ? el('div', { class: 'cell-sub' }, s.employee.department.name) : null) },
      { key: 'workplace_name', label: 'Workplace' },
      { key: 'clock_in_local', label: 'Clock in', render: (s) => (s.clock_in_local ? s.clock_in_local.slice(11, 16) : '—') },
      { key: 'clock_out_local', label: 'Clock out', render: (s) => (s.clock_out_local ? s.clock_out_local.slice(11, 16) : '—') },
      { key: 'worked_minutes', label: 'Worked', numeric: true, render: (s) => fmtMinutes(s.worked_minutes) },
      { key: 'status', label: 'Status', render: (s) => el('div', { class: 'row', style: { gap: '4px' } }, statusChip(s.status), s.is_late ? statusChip('late', `Late ${s.late_minutes || 0}m`) : null, s.is_manual ? el('span', { class: 'chip grey' }, 'Manual') : null, s.corrected ? el('span', { class: 'chip blue' }, 'Corrected') : null) },
    ], {
      rows: data.items || [],
      caption: 'Attendance sessions',
      onRowClick: (s) => openSession(s.id, load),
      emptyText: 'No sessions in this range. Adjust the date range or filters.',
      pager: { page: data.page || state.page, pageSize: data.page_size || PAGE_SIZE, total: data.total || 0, onPage: (p) => { state.page = p; load(); } },
    });
    replace(tableHost, el('div', { style: { padding: '8px 12px 12px' } }, table));
  }
  load();
  if (query.session_id) openSession(query.session_id, load);
  return () => { seq += 1; };
}

export async function openSession(id, reload) {
  const d = openDrawer({ title: 'Session', body: loading() });
  try {
    const data = await api.get(`/admin/attendance/sessions/${id}`);
    const s = data.session || {};
    const emp = data.employee || s.employee;
    d.setTitle(`${fmtWorkDate(s.work_date)} · ${fullName(emp)}`);
    const canReview = session.can('attendance:review');
    replace(d.body,
      el('div', { class: 'row' }, statusChip(s.status), s.is_late ? statusChip('late', `Late ${s.late_minutes || 0} min`) : null, s.is_manual ? el('span', { class: 'chip grey' }, 'Manual') : null, s.corrected ? el('span', { class: 'chip blue' }, 'Corrected') : null),
      el('div', {},
        kv('Employee', employeeLink(emp)), kv('Workplace', s.workplace_name || '—'),
        kv('Clock in', s.clock_in_at ? fmtDateTime(s.clock_in_at) : '—'), kv('Clock out', s.clock_out_at ? fmtDateTime(s.clock_out_at) : '—'),
        kv('Worked', fmtMinutes(s.worked_minutes)), kv('Break', fmtMinutes(s.break_minutes)), kv('Overtime', fmtMinutes(s.overtime_minutes)),
        s.correction_note ? kv('Correction note', s.correction_note) : null, s.notes ? kv('Notes', s.notes) : null),
      canReview ? el('div', { class: 'row' }, button('Correct times', { kind: 'secondary', size: 'sm', icon: 'edit', onClick: () => openCorrection(s, () => { d.close(); reload && reload(); }), attrs: { 'data-testid': 'correct-session' } })) : null,
      el('h3', {}, 'Events'),
      (data.events || []).length ? el('ul', { class: 'timeline' }, (data.events || []).map(timelineItem)) : el('p', { class: 'muted' }, 'No detection events are linked to this session (created manually).'));
  } catch (err) {
    replace(d.body, errorState(err));
  }
}

function openCorrection(s, done) {
  const ci = input({ type: 'datetime-local', value: utcIsoToLocalInput(s.clock_in_at), 'data-testid': 'corr-in' });
  const co = input({ type: 'datetime-local', value: utcIsoToLocalInput(s.clock_out_at), 'data-testid': 'corr-out' });
  const note = textarea({ placeholder: 'Why is this correction needed? (required, audit-logged)', 'data-testid': 'corr-note' });
  const err = formError('');
  const submit = button('Save correction', { type: 'submit', attrs: { 'data-testid': 'corr-submit' } });
  const cancel = button('Cancel', { kind: 'secondary' });
  const form = el('form', { class: 'stack', novalidate: true },
    el('div', { class: 'form-info' }, 'Times are entered in Africa/Johannesburg. The original values stay in the audit log.'),
    el('div', { class: 'form-grid' }, field('Clock in', ci), field('Clock out', co, { hint: 'Leave empty to keep the session open' })),
    field('Reason', note, { hint: 'Required' }), err, el('div', { class: 'form-actions' }, cancel, submit));
  const m = openModal({ title: 'Correct session times', body: form });
  cancel.addEventListener('click', () => m.close());
  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    if (note.value.trim().length < 3) { showFormError(err, 'A reason of at least 3 characters is required.'); note.focus(); return; }
    const body = { note: note.value.trim() };
    const inIso = localInputToUtcIso(ci.value);
    const outIso = localInputToUtcIso(co.value);
    if (inIso && inIso !== s.clock_in_at) body.clock_in_at = inIso;
    if (outIso && outIso !== s.clock_out_at) body.clock_out_at = outIso;
    if (inIso && outIso && outIso <= inIso) { showFormError(err, 'Clock out must be after clock in.'); return; }
    if (!body.clock_in_at && !body.clock_out_at) { showFormError(err, 'Change at least one time.'); return; }
    setBusy(submit, true, 'Saving…');
    try { await api.patch(`/admin/attendance/sessions/${s.id}`, body); m.close(); toast('Session corrected.', 'success'); done(); }
    catch (ex) { showFormError(err, ex); } finally { setBusy(submit, false, 'Saving…'); }
  });
}

function openAddSession(reload) {
  const emp = employeePicker({});
  const wd = input({ type: 'date', value: todayLocal(), max: todayLocal(), 'data-testid': 'ms-date' });
  const ci = input({ type: 'datetime-local', 'data-testid': 'ms-in' });
  const co = input({ type: 'datetime-local', 'data-testid': 'ms-out' });
  const note = textarea({ placeholder: 'Reason for the manual session (required)', 'data-testid': 'ms-note' });
  const err = formError('');
  const submit = button('Create session', { type: 'submit', attrs: { 'data-testid': 'ms-submit' } });
  const cancel = button('Cancel', { kind: 'secondary' });
  wd.addEventListener('change', () => { if (wd.value && !ci.value) ci.value = `${wd.value}T08:00`; });
  const form = el('form', { class: 'stack', novalidate: true },
    el('div', { class: 'form-info' }, 'Creates a session with source "admin". It is audit-logged and the employee is notified.'),
    el('div', { class: 'field' }, el('span', { class: 'label' }, 'Employee'), emp.el),
    el('div', { class: 'form-grid' }, field('Work date', wd), el('div'), field('Clock in (SAST)', ci), field('Clock out (SAST)', co, { hint: 'Optional' })),
    field('Reason', note, { hint: 'Required' }), err, el('div', { class: 'form-actions' }, cancel, submit));
  const m = openModal({ title: 'Add manual session', body: form, wide: true });
  cancel.addEventListener('click', () => m.close());
  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    const u = emp.value();
    if (!u) { showFormError(err, 'Select an employee.'); return; }
    const inIso = localInputToUtcIso(ci.value);
    if (!wd.value || !inIso) { showFormError(err, 'Work date and clock-in time are required.'); return; }
    const outIso = localInputToUtcIso(co.value);
    if (outIso && outIso <= inIso) { showFormError(err, 'Clock out must be after clock in.'); return; }
    if (note.value.trim().length < 3) { showFormError(err, 'A reason of at least 3 characters is required.'); return; }
    setBusy(submit, true, 'Creating…');
    try {
      await api.post('/admin/attendance/sessions', cleanQuery({ employee_id: u.id, work_date: wd.value, clock_in_at: inIso, clock_out_at: outIso, note: note.value.trim() }));
      m.close(); toast(`Manual session created for ${fullName(u)}.`, 'success'); reload();
    } catch (ex) { showFormError(err, ex); } finally { setBusy(submit, false, 'Creating…'); }
  });
}
