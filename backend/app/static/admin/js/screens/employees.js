// Employee directory: search, filters, pagination, create employee.

import { api } from '../api.js';
import { session } from '../session.js';
import { el, replace, pageHead, button, dataTable, input, select, field, statusChip, debounce, skeleton, errorState, openModal, confirmDialog, formError, showFormError, setBusy, copyBox, toast, handleError } from '../ui.js';
import { fullName, titleCase, statusLabel, fmtRelative } from '../format.js';
import { replaceQuery } from '../router.js';
import { departmentSelect, getDepartments, cleanQuery, parsePage, assignableRoles } from '../data.js';

const PAGE_SIZE = 25;

export function render(container, { setTitle, query, navigate }) {
  setTitle('Employees', 'Directory and account management');
  const state = { search: query.search || '', department_id: query.department_id || '', status: query.status || '', role: query.role || '', page: parsePage(query.page) };
  const canWrite = session.can('employees:write');

  const searchInp = input({ type: 'search', placeholder: 'Name, employee number or email', value: state.search, 'aria-label': 'Search employees', 'data-testid': 'emp-search' });
  const statusSel = select([{ value: '', label: 'All statuses' }, { value: 'active', label: 'Active' }, { value: 'inactive', label: 'Inactive' }], { 'aria-label': 'Status filter' }, state.status);
  const roleSel = select([{ value: '', label: 'All roles' }, 'employee', 'manager', 'admin', 'superadmin'].map((r) => (typeof r === 'string' ? { value: r, label: titleCase(r) } : r)), { 'aria-label': 'Role filter' }, state.role);
  const deptHost = el('div', { class: 'field' }, el('label', {}, 'Department'), select([{ value: '', label: 'All departments' }], {}, ''));
  const filterBar = el('div', { class: 'filter-bar' },
    el('div', { class: 'field wide' }, el('label', { for: 'emp-search' }, 'Search'), searchInp),
    deptHost, field('Status', statusSel), field('Role', roleSel));
  searchInp.id = 'emp-search';
  const tableHost = el('div', { class: 'card flush' });
  const actions = [];
  if (canWrite) actions.push(button('New employee', { icon: 'plus', onClick: () => openCreate(navigate), attrs: { 'data-testid': 'emp-create' } }));
  replace(container, pageHead('Employees', '', actions), el('div', { class: 'card' }, filterBar), tableHost);

  departmentSelect({ 'aria-label': 'Department filter' }, state.department_id).then((sel) => {
    sel.addEventListener('change', () => { state.department_id = sel.value; state.page = 1; load(); });
    replace(deptHost, el('label', { for: sel.id = 'emp-dept' }, 'Department'), sel);
  });

  const onSearch = debounce(() => { state.search = searchInp.value.trim(); state.page = 1; load(); }, 300);
  searchInp.addEventListener('input', onSearch);
  statusSel.addEventListener('change', () => { state.status = statusSel.value; state.page = 1; load(); });
  roleSel.addEventListener('change', () => { state.role = roleSel.value; state.page = 1; load(); });

  let seq = 0;
  async function load() {
    const my = ++seq;
    replaceQuery(cleanQuery({ ...state, page: state.page > 1 ? state.page : '' }));
    replace(tableHost, el('div', { style: { padding: '16px 20px' } }, skeleton({ rows: 6, cols: 5 })));
    let data;
    try {
      data = await api.get('/admin/employees', cleanQuery({ search: state.search, department_id: state.department_id, status: state.status, role: state.role, page: state.page, page_size: PAGE_SIZE }));
    } catch (err) {
      if (my === seq) replace(tableHost, errorState(err, load));
      return;
    }
    if (my !== seq) return;
    const columns = [
      { key: 'name', label: 'Employee', render: (u) => el('div', {}, el('div', { class: 'cell-primary' }, fullName(u)), el('div', { class: 'cell-sub' }, `${u.employee_number || ''}${u.email ? ' · ' + u.email : ''}`)) },
      { key: 'department', label: 'Department', render: (u) => (u.department ? u.department.name : el('span', { class: 'muted' }, '—')) },
      { key: 'role', label: 'Role', render: (u) => el('span', { class: `chip ${u.role === 'employee' ? 'grey' : 'blue'}` }, titleCase(u.role)) },
      { key: 'status', label: 'Status', render: (u) => statusChip(u.status) },
      { key: 'last_attendance_status', label: 'Today', render: (u) => (u.last_attendance_status ? statusChip(u.last_attendance_status, statusLabel(u.last_attendance_status)) : el('span', { class: 'muted' }, '—')) },
      { key: 'active_device', label: 'Device', render: (u) => (u.active_device ? el('span', { title: u.active_device.device_id || '' }, `${u.active_device.manufacturer || ''} ${u.active_device.model || ''}`.trim() || 'Registered') : el('span', { class: 'muted' }, 'None')) },
      { key: 'last_login_at', label: 'Last login', render: (u) => el('span', { class: 'muted' }, fmtRelative(u.last_login_at)) },
    ];
    const table = dataTable(columns, {
      rows: data.items || [],
      caption: 'Employees',
      onRowClick: (u) => navigate(`/employees/${u.id}`),
      rowActions: (u) => [
        button('View', { kind: 'secondary', size: 'sm', onClick: () => navigate(`/employees/${u.id}`) }),
        canWrite && u.status === 'active' ? button('Reset password', { kind: 'secondary', size: 'sm', icon: 'key', onClick: () => resetPassword(u) }) : null,
      ],
      emptyText: state.search || state.department_id || state.status || state.role ? 'No employees match these filters.' : 'No employees yet. Create the first employee to get started.',
      pager: { page: data.page || state.page, pageSize: data.page_size || PAGE_SIZE, total: data.total || 0, onPage: (p) => { state.page = p; load(); } },
    });
    replace(tableHost, el('div', { style: { padding: '8px 12px 12px' } }, table));
  }
  const onChanged = () => load();
  window.addEventListener('employees:changed', onChanged);
  load();
  return () => { seq += 1; window.removeEventListener('employees:changed', onChanged); };
}

export async function resetPassword(user) {
  const ok = await confirmDialog({ title: 'Reset password', message: `Issue a new temporary password for ${fullName(user)} (${user.employee_number})? Their current password stops working immediately and they must choose a new one at next sign-in.`, confirmLabel: 'Reset password', danger: true });
  if (!ok) return null;
  try {
    const res = await api.post(`/admin/employees/${user.id}/reset-password`);
    showTemporaryPassword(res.temporary_password, user);
    return res;
  } catch (err) { handleError(err); return null; }
}

export function showTemporaryPassword(password, user, extraActions = []) {
  const done = button('Done');
  const m = openModal({
    title: 'Temporary password',
    body: el('div', { class: 'stack', 'data-testid': 'temp-password-modal' },
      el('p', {}, `Share this temporary password with ${fullName(user)} through a secure channel. It is shown only once and must be changed at first sign-in.`),
      copyBox(password || '—'),
      el('div', { class: 'form-actions' }, extraActions.map((a) => a(() => m.close())), done)),
  });
  done.addEventListener('click', () => m.close());
  return m;
}

function openCreate(navigate) {
  const roles = ['employee', ...assignableRoles(session.permissions()).filter((r) => r !== 'employee')];
  const f = {
    employee_number: input({ required: true, placeholder: 'e.g. MG-0042', 'data-testid': 'new-employee-number' }),
    first_name: input({ required: true, autocomplete: 'off', 'data-testid': 'new-first-name' }),
    last_name: input({ required: true, autocomplete: 'off', 'data-testid': 'new-last-name' }),
    email: input({ type: 'email', required: true, autocomplete: 'off', 'data-testid': 'new-email' }),
    phone: input({ type: 'tel', placeholder: '+27 …', autocomplete: 'off' }),
    role: select(roles.map((r) => ({ value: r, label: titleCase(r) })), { 'data-testid': 'new-role' }, 'employee'),
    temporary_password: input({ type: 'text', autocomplete: 'off', placeholder: 'Leave blank to generate one' }),
  };
  const deptHost = el('div', { class: 'field' }, el('label', {}, 'Department'), select([{ value: '', label: 'No department' }], {}, ''));
  let deptSel = deptHost.querySelector('select');
  getDepartments().then((items) => {
    deptSel = select([{ value: '', label: 'No department' }, ...items.filter((d) => d.active !== false).map((d) => ({ value: d.id, label: `${d.name} (${d.code})` }))], { 'data-testid': 'new-department' }, '');
    replace(deptHost, el('label', { for: deptSel.id = 'new-dept' }, 'Department'), deptSel);
  }).catch(() => {});
  const err = formError('');
  const submit = button('Create employee', { type: 'submit', attrs: { 'data-testid': 'new-submit' } });
  const cancel = button('Cancel', { kind: 'secondary' });
  const form = el('form', { class: 'stack', novalidate: true },
    el('div', { class: 'form-grid' },
      field('Employee number', f.employee_number), field('Role', f.role),
      field('First name', f.first_name), field('Last name', f.last_name),
      field('Email', f.email), field('Phone', f.phone, { hint: 'Optional' }),
      deptHost, field('Temporary password', f.temporary_password, { hint: 'Optional · the employee must change it at first sign-in' })),
    err, el('div', { class: 'form-actions' }, cancel, submit));
  const m = openModal({ title: 'New employee', body: form, wide: true });
  cancel.addEventListener('click', () => m.close());
  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    showFormError(err, '');
    const body = cleanQuery({
      employee_number: f.employee_number.value.trim(), first_name: f.first_name.value.trim(), last_name: f.last_name.value.trim(),
      email: f.email.value.trim(), phone: f.phone.value.trim(), department_id: deptSel.value, role: f.role.value,
      temporary_password: f.temporary_password.value,
    });
    const missing = ['employee_number', 'first_name', 'last_name', 'email'].filter((k) => !body[k]);
    if (missing.length) { showFormError(err, `Required: ${missing.map(titleCase).join(', ')}.`); return; }
    setBusy(submit, true, 'Creating…');
    try {
      const res = await api.post('/admin/employees', body);
      m.close();
      toast(`${fullName(res.user)} created.`, 'success');
      showTemporaryPassword(res.temporary_password, res.user,
        [(close) => button('Open profile', { kind: 'secondary', onClick: () => { close(); navigate(`/employees/${res.user.id}`); } })]);
      window.dispatchEvent(new CustomEvent('employees:changed'));
    } catch (ex) {
      showFormError(err, ex.status === 409 ? `${ex.message} Check the employee number and email are unique.` : ex);
    } finally { setBusy(submit, false, 'Creating…'); }
  });
}
