// Roles & permissions matrix and role assignment.

import { api } from '../api.js';
import { session } from '../session.js';
import { el, replace, pageHead, button, card, select, field, formError, showFormError, setBusy, toast, skeleton, errorState, emptyState } from '../ui.js';
import { titleCase, fullName } from '../format.js';
import { employeePicker, assignableRoles, refreshButton } from '../data.js';

const PERMISSION_DESC = {
  'attendance:self': 'Confirm own attendance in the mobile app',
  'admin:read': 'Overview, directory, records, live, exceptions, audit, reports',
  'attendance:review': 'Approve/reject pending & under-review events, manual requests, corrections',
  'reports:export': 'Download CSV / XLSX / PDF reports',
  'employees:write': 'Create, edit, deactivate employees; reset passwords',
  'devices:write': 'Approve / revoke devices',
  'config:write': 'Workplaces, Wi-Fi, policy, departments',
  'roles:write': 'Change roles up to admin',
  'roles:superadmin': 'Grant/revoke superadmin, system settings, data erasure',
  'notifications:broadcast': 'Send broadcast notifications',
};

export function render(container, { setTitle }) {
  setTitle('Roles & permissions', 'Server-enforced permission matrix');
  const matrixHost = el('div');
  const assignHost = el('div');
  replace(container, pageHead('Roles & permissions', '', [refreshButton(() => load())]), matrixHost, assignHost);

  async function load() {
    replace(matrixHost, el('div', { class: 'card' }, skeleton({ rows: 6, cols: 4 })));
    let roles;
    try { roles = (await api.get('/admin/roles')).roles || []; }
    catch (err) { replace(matrixHost, errorState(err, load)); return; }
    if (!roles.length) { replace(matrixHost, el('div', { class: 'card' }, emptyState('No roles returned by the server'))); return; }
    const perms = Array.from(new Set(roles.flatMap((r) => r.permissions || [])));
    const known = Object.keys(PERMISSION_DESC);
    perms.sort((a, b) => (known.indexOf(a) === -1 ? 99 : known.indexOf(a)) - (known.indexOf(b) === -1 ? 99 : known.indexOf(b)));
    const table = el('table', { class: 'table matrix' },
      el('caption', { class: 'sr-only' }, 'Permissions by role'),
      el('thead', {}, el('tr', {}, el('th', { scope: 'col' }, 'Permission'), roles.map((r) => el('th', { scope: 'col' }, titleCase(r.role), el('span', { class: 'perm-desc' }, `${r.user_count ?? 0} user${r.user_count === 1 ? '' : 's'}`))))),
      el('tbody', {},
        perms.map((p) => el('tr', {}, el('th', { scope: 'row', style: { textTransform: 'none', letterSpacing: 0 } }, el('span', { class: 'mono' }, p), el('span', { class: 'perm-desc' }, PERMISSION_DESC[p] || '')),
          roles.map((r) => { const yes = (r.permissions || []).includes(p); return el('td', { class: yes ? 'yes' : 'no', 'aria-label': yes ? 'Granted' : 'Not granted' }, yes ? '✔' : '–'); }))),
        el('tr', {}, el('th', { scope: 'row', style: { textTransform: 'none', letterSpacing: 0 } }, 'Description'), roles.map((r) => el('td', { class: 'small muted', style: { textAlign: 'left' } }, r.description || '')))));
    replace(matrixHost, card('Permission matrix', el('div', { class: 'table-wrap' }, table), { className: 'flush' }));
    matrixHost.querySelector('.card-head').style.padding = '16px 20px 0';
    renderAssign(roles);
  }

  function renderAssign(roles) {
    const allowed = assignableRoles(session.permissions());
    if (!allowed.length) { replace(assignHost, el('div', { class: 'form-info' }, 'Your role cannot change user roles. Role changes require the admin role (or superadmin for the superadmin role).')); return; }
    let target = null;
    const roleSel = select(allowed.map((r) => ({ value: r, label: titleCase(r) })), { 'data-testid': 'roles-select' }, allowed[0]);
    const err = formError('');
    const current = el('p', { class: 'muted small' }, 'Search for an employee to see their current role.');
    const submit = button('Change role', { type: 'submit', attrs: { 'data-testid': 'roles-submit' } });
    const picker = employeePicker({ onSelect: (u) => { target = u; current.textContent = u ? `${fullName(u)} currently has the role ${titleCase(u.role)}.` : 'Search for an employee to see their current role.'; if (u && allowed.includes(u.role)) roleSel.value = u.role; } });
    const form = el('form', { class: 'stack', novalidate: true },
      el('div', { class: 'field' }, el('span', { class: 'label' }, 'Employee'), picker.el), current,
      field('New role', roleSel, { hint: session.can('roles:superadmin') ? 'You may assign any role, including superadmin.' : 'Only a superadmin can grant or revoke the superadmin role.' }),
      err, el('div', { class: 'form-actions' }, submit));
    form.addEventListener('submit', async (e) => {
      e.preventDefault();
      showFormError(err, '');
      if (!target) { showFormError(err, 'Select an employee first.'); return; }
      if (target.id === session.user()?.id) { showFormError(err, 'You cannot change your own role.'); return; }
      if (target.role === 'superadmin' && !session.can('roles:superadmin')) { showFormError(err, 'Only a superadmin can change the role of another superadmin.'); return; }
      if (target.role === roleSel.value) { showFormError(err, 'The employee already has this role.'); return; }
      setBusy(submit, true, 'Saving…');
      try {
        const u = await api.post(`/admin/employees/${target.id}/role`, { role: roleSel.value });
        toast(`${fullName(u)} is now ${titleCase(u.role)}.`, 'success');
        picker.clear(); target = null; current.textContent = 'Search for an employee to see their current role.';
        load();
      } catch (ex) { showFormError(err, ex); } finally { setBusy(submit, false, 'Saving…'); }
    });
    replace(assignHost, card('Change a user\'s role', form));
  }

  load();
  return () => {};
}
