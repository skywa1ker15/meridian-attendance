// Employee profile: details, devices, 30-day stats, today, account actions.

import { api } from '../api.js';
import { session } from '../session.js';
import { el, replace, button, card, statTile, statusChip, kv, dataTable, confirmDialog, openModal, field, input, select, formError, showFormError, setBusy, toast, handleError, skeleton, errorState } from '../ui.js';
import { fullName, initials, titleCase, fmtDateTime, fmtMinutes, fmtRelative, fmtWorkDate, statusLabel } from '../format.js';
import { getDepartments, assignableRoles } from '../data.js';
import { resetPassword, sendInvitation } from './employees.js';

export function render(container, { params, setTitle, navigate, user: me }) {
  const id = params.id;
  const canWrite = session.can('employees:write');
  const canDevices = session.can('devices:write');
  const canRoles = session.can('roles:write');
  const canSuper = session.can('roles:superadmin');
  setTitle('Employee', 'Loading…');
  const host = el('div', { class: 'stack', style: { gap: '20px' } });
  replace(container, host);

  async function load() {
    replace(host, el('div', { class: 'card' }, skeleton({ rows: 4, cols: 3 })));
    let data;
    try { data = await api.get(`/admin/employees/${id}`); }
    catch (err) { replace(host, el('div', { class: 'card' }, errorState(err, load), el('div', { class: 'row', style: { justifyContent: 'center' } }, el('a', { href: '#/employees' }, 'Back to employees')))); setTitle('Employee', ''); return; }
    const u = data.user || {};
    const devices = data.devices || [];
    const today = data.today || null;
    const stats = data.stats_30d || {};
    setTitle(fullName(u), `${u.employee_number || ''} · ${u.department?.name || 'No department'}`);

    const isSelf = me && me.id === u.id;
    const targetIsSuper = u.role === 'superadmin';
    const neverLoggedIn = !u.last_login_at;
    const headActions = [
      button('Back', { kind: 'secondary', size: 'sm', icon: 'arrowLeft', onClick: () => navigate('/employees') }),
      canWrite ? button('Edit details', { kind: 'secondary', size: 'sm', icon: 'edit', onClick: () => openEdit(u, load), attrs: { 'data-testid': 'emp-edit' } }) : null,
      canWrite && u.status === 'active' && neverLoggedIn ? button('Send invitation', { size: 'sm', icon: 'send', onClick: () => sendInvitation(u, load), attrs: { 'data-testid': 'emp-invite' } }) : null,
      canWrite && u.status === 'active' && !neverLoggedIn ? button('Reset password', { kind: 'secondary', size: 'sm', icon: 'key', onClick: () => resetPassword(u) }) : null,
      canRoles && !isSelf && (!targetIsSuper || canSuper) ? button('Change role', { kind: 'secondary', size: 'sm', icon: 'roles', onClick: () => openRole(u, load), attrs: { 'data-testid': 'emp-role' } }) : null,
      canWrite && !isSelf && u.status === 'active' ? button('Deactivate', { kind: 'danger', size: 'sm', onClick: () => deactivate(u, load), attrs: { 'data-testid': 'emp-deactivate' } }) : null,
      canWrite && u.status === 'inactive' ? button('Reactivate', { kind: 'success', size: 'sm', onClick: () => reactivate(u, load), attrs: { 'data-testid': 'emp-reactivate' } }) : null,
      button('Export data (POPIA)', { kind: 'secondary', size: 'sm', icon: 'download', onClick: () => exportData(u) }),
      canSuper && !isSelf ? button('Erase personal data', { kind: 'danger', size: 'sm', onClick: () => erase(u, navigate) }) : null,
    ];

    const head = el('div', { class: 'card profile-head' },
      el('div', { class: 'avatar' }, initials(u)),
      el('div', { class: 'title' }, el('h1', {}, fullName(u)),
        el('div', { class: 'row', style: { marginTop: '6px' } },
          el('span', { class: 'mono' }, u.employee_number || '—'),
          el('span', { class: `chip ${u.role === 'employee' ? 'grey' : 'blue'}` }, titleCase(u.role)),
          statusChip(u.status),
          neverLoggedIn ? el('span', { class: 'chip orange' }, 'Invitation pending') : null,
          !neverLoggedIn && u.must_change_password ? el('span', { class: 'chip orange' }, 'Must change password') : null)),
      el('div', { class: 'page-actions', style: { marginLeft: 'auto' } }, headActions));

    const details = card('Details', el('div', {},
      kv('Email', u.email), kv('Phone', u.phone || '—'), kv('Department', u.department ? `${u.department.name} (${u.department.code})` : '—'),
      kv('Role', titleCase(u.role)), kv('Status', statusLabel(u.status)), kv('Created', fmtDateTime(u.created_at)), kv('Last login', u.last_login_at ? fmtDateTime(u.last_login_at) : 'Never'),
      kv('Permissions', (u.permissions || []).length ? el('span', { class: 'small mono' }, u.permissions.join(', ')) : '—')));

    const todayBody = today ? el('div', {},
      el('div', { class: 'row', style: { marginBottom: '8px' } }, statusChip(today.status), el('span', { class: 'muted small' }, fmtWorkDate(today.work_date))),
      kv('Clock in', today.clock_in_local || '—'), kv('Clock out', today.clock_out_local || '—'),
      kv('Worked today', fmtMinutes(today.worked_minutes_today)), kv('Late today', today.late_today ? 'Yes' : 'No'),
      kv('This week', fmtMinutes(today.week_minutes)), kv('This month', fmtMinutes(today.month_minutes)),
      kv('Pending events', String((today.pending_events || []).length)), kv('Pending requests', String(today.pending_requests ?? 0)))
      : el('p', { class: 'muted' }, 'No attendance information for today.');
    const todayCard = card('Today', todayBody, { className: 'today-card' });

    const statTiles = el('div', { class: 'grid profile-stats' },
      statTile({ label: 'Present days', value: stats.present_days ?? '—', icon: 'check', color: 'green' }),
      statTile({ label: 'Late days', value: stats.late_days ?? '—', icon: 'clock', color: 'orange' }),
      statTile({ label: 'Absent days', value: stats.absent_days ?? '—', icon: 'x', color: 'red' }),
      statTile({ label: 'Hours worked', value: stats.worked_minutes !== undefined ? fmtMinutes(stats.worked_minutes) : '—', icon: 'records' }),
      statTile({ label: 'Manual requests', value: stats.manual_requests ?? '—', icon: 'pending', color: 'orange' }));

    const devTable = dataTable([
      { key: 'model', label: 'Device', render: (d) => el('div', {}, el('div', { class: 'cell-primary' }, `${d.manufacturer || ''} ${d.model || ''}`.trim() || '—'), el('div', { class: 'cell-sub mono' }, d.device_id || '')) },
      { key: 'os_version', label: 'OS / app', render: (d) => `${d.os_version ? 'Android ' + d.os_version : '—'} · ${d.app_version || '—'}` },
      { key: 'status', label: 'Status', render: (d) => statusChip(d.status) },
      { key: 'integrity_verdict', label: 'Integrity', render: (d) => el('span', { class: `chip ${d.integrity_verdict === 'meets_device_integrity' ? 'green' : d.integrity_verdict === 'failed' ? 'red' : 'grey'}` }, titleCase(d.integrity_verdict || 'unknown')) },
      { key: 'registered_at', label: 'Registered', render: (d) => fmtDateTime(d.registered_at) },
      { key: 'last_seen_at', label: 'Last seen', render: (d) => fmtRelative(d.last_seen_at) },
    ], {
      rows: devices,
      caption: 'Devices',
      emptyText: 'No devices registered. The employee registers a device by signing in to the mobile app.',
      rowActions: canDevices ? (d) => [
        d.status === 'pending_approval' ? button('Approve', { kind: 'success', size: 'sm', onClick: () => approveDevice(d, load) }) : null,
        d.status !== 'revoked' ? button('Revoke', { kind: 'danger', size: 'sm', onClick: () => revokeDevice(d, load) }) : null,
      ] : null,
    });
    const devCard = card('Devices', devTable, { className: 'flush', actions: null });
    devCard.querySelector('.card-head').style.padding = '16px 20px 0';

    replace(host, head, statTiles, el('div', { class: 'detail-grid' }, details, todayCard), devCard,
      el('p', { class: 'muted small' }, 'Attendance history: ', el('a', { href: `#/records?employee_id=${u.id}&employee_name=${encodeURIComponent(fullName(u))}` }, 'open records for this employee'), ' · ',
        el('a', { href: `#/audit?entity_type=user&entity_id=${u.id}` }, 'audit trail')));
  }

  load();
  return () => {};
}

function openEdit(u, reload) {
  const f = {
    employee_number: input({ value: u.employee_number || '', required: true }),
    first_name: input({ value: u.first_name || '', required: true, 'data-testid': 'edit-first-name' }),
    last_name: input({ value: u.last_name || '', required: true }),
    email: input({ type: 'email', value: u.email || '', required: true }),
    phone: input({ type: 'tel', value: u.phone || '' }),
  };
  const deptHost = el('div', { class: 'field' }, el('label', {}, 'Department'), select([{ value: '', label: 'No department' }], {}, ''));
  let deptSel = deptHost.querySelector('select');
  getDepartments().then((items) => {
    deptSel = select([{ value: '', label: 'No department' }, ...items.filter((d) => d.active !== false || d.id === u.department?.id).map((d) => ({ value: d.id, label: `${d.name} (${d.code})` }))], {}, u.department?.id || '');
    replace(deptHost, el('label', { for: deptSel.id = 'edit-dept' }, 'Department'), deptSel);
  }).catch(() => {});
  const err = formError('');
  const submit = button('Save changes', { type: 'submit', attrs: { 'data-testid': 'edit-submit' } });
  const cancel = button('Cancel', { kind: 'secondary' });
  const form = el('form', { class: 'stack', novalidate: true },
    el('div', { class: 'form-grid' }, field('Employee number', f.employee_number), deptHost, field('First name', f.first_name), field('Last name', f.last_name), field('Email', f.email), field('Phone', f.phone)),
    err, el('div', { class: 'form-actions' }, cancel, submit));
  const m = openModal({ title: `Edit ${fullName(u)}`, body: form, wide: true });
  cancel.addEventListener('click', () => m.close());
  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    const body = {};
    Object.entries(f).forEach(([k, inp]) => { const v = inp.value.trim(); if (v !== (u[k] || '')) body[k] = v || null; });
    const newDept = deptSel.value;
    if (newDept !== (u.department?.id || '')) { if (newDept) body.department_id = newDept; else body.clear_department = true; }
    if (!Object.keys(body).length) { m.close(); return; }
    if (body.first_name === null || body.last_name === null || body.email === null || body.employee_number === null) { showFormError(err, 'Name, email and employee number are required.'); return; }
    setBusy(submit, true, 'Saving…');
    try {
      await api.patch(`/admin/employees/${u.id}`, body);
      m.close(); toast('Employee updated.', 'success'); reload();
    } catch (ex) { showFormError(err, ex); } finally { setBusy(submit, false, 'Saving…'); }
  });
}

function openRole(u, reload) {
  const roles = assignableRoles(session.permissions());
  const sel = select(roles.map((r) => ({ value: r, label: titleCase(r) })), { 'data-testid': 'role-select' }, roles.includes(u.role) ? u.role : roles[0]);
  const err = formError('');
  const submit = button('Change role', { type: 'submit', attrs: { 'data-testid': 'role-submit' } });
  const cancel = button('Cancel', { kind: 'secondary' });
  const form = el('form', { class: 'stack', novalidate: true },
    el('p', {}, `${fullName(u)} currently has the role `, el('strong', {}, titleCase(u.role)), '. Permissions apply immediately at the next request.'),
    field('New role', sel, { hint: session.can('roles:superadmin') ? 'You may assign any role.' : 'Only a superadmin can grant or revoke the superadmin role.' }),
    err, el('div', { class: 'form-actions' }, cancel, submit));
  const m = openModal({ title: 'Change role', body: form });
  cancel.addEventListener('click', () => m.close());
  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    if (sel.value === u.role) { m.close(); return; }
    setBusy(submit, true, 'Saving…');
    try {
      await api.post(`/admin/employees/${u.id}/role`, { role: sel.value });
      m.close(); toast(`Role changed to ${titleCase(sel.value)}.`, 'success'); reload();
    } catch (ex) { showFormError(err, ex); } finally { setBusy(submit, false, 'Saving…'); }
  });
}

async function deactivate(u, reload) {
  const r = await confirmDialog({ title: 'Deactivate account', danger: true, confirmLabel: 'Deactivate',
    message: `${fullName(u)} will be signed out everywhere, their devices revoked and attendance detection stopped. Attendance history is kept.`,
    note: { label: 'Reason', required: true, placeholder: 'e.g. Left the company on 30 Nov' } });
  if (!r) return;
  try { await api.post(`/admin/employees/${u.id}/deactivate`, { reason: r.note }); toast('Account deactivated.', 'success'); reload(); }
  catch (err) { handleError(err); }
}

async function reactivate(u, reload) {
  const r = await confirmDialog({ title: 'Reactivate account', confirmLabel: 'Reactivate', message: `${fullName(u)} will be able to sign in again. They will need to register their device again.` });
  if (!r) return;
  try { await api.post(`/admin/employees/${u.id}/reactivate`); toast('Account reactivated.', 'success'); reload(); }
  catch (err) { handleError(err); }
}

async function exportData(u) {
  try {
    const name = await api.downloadJson(`/admin/employees/${u.id}/export`, null, `${u.employee_number || u.id}-personal-data.json`);
    toast(`Export downloaded (${name}).`, 'success');
  } catch (err) { handleError(err); }
}

async function erase(u, navigate) {
  const r = await confirmDialog({ title: 'Erase personal data', danger: true, confirmLabel: 'Erase permanently',
    message: `This pseudonymises ${fullName(u)}'s personal data after retention checks. Attendance evidence is kept without identifying details. This cannot be undone.`,
    requireText: u.employee_number, note: { label: 'Reason', required: true, placeholder: 'POPIA request reference, date…' } });
  if (!r) return;
  try {
    await api.post(`/admin/employees/${u.id}/erase`, { confirm_employee_number: u.employee_number, reason: r.note });
    toast('Erasure accepted. Personal data will be pseudonymised.', 'success', 7000);
    navigate('/employees');
  } catch (err) { handleError(err); }
}

export async function approveDevice(d, reload) {
  try { await api.post(`/admin/devices/${d.id}/approve`); toast('Device approved.', 'success'); reload(); }
  catch (err) { handleError(err); }
}
export async function revokeDevice(d, reload) {
  const r = await confirmDialog({ title: 'Revoke device', danger: true, confirmLabel: 'Revoke', message: `The device ${d.manufacturer || ''} ${d.model || ''} will no longer be able to submit attendance. The employee is notified.`, note: { label: 'Reason', required: true } });
  if (!r) return;
  try { await api.post(`/admin/devices/${d.id}/revoke`, { reason: r.note }); toast('Device revoked.', 'success'); reload(); }
  catch (err) { handleError(err); }
}
