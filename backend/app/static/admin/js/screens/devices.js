// Devices: registered mobile devices, approve / revoke.

import { api } from '../api.js';
import { session } from '../session.js';
import { el, replace, pageHead, button, dataTable, input, select, field, statusChip, debounce, skeleton, errorState } from '../ui.js';
import { fmtDateTime, fmtRelative, titleCase } from '../format.js';
import { replaceQuery } from '../router.js';
import { cleanQuery, parsePage, employeeLink, refreshButton } from '../data.js';
import { approveDevice, revokeDevice } from './employee-profile.js';

const PAGE_SIZE = 50;

export function render(container, { setTitle, query }) {
  setTitle('Devices', 'Registered mobile devices');
  const canWrite = session.can('devices:write');
  const state = { status: query.status || '', search: query.search || '', page: parsePage(query.page) };
  const statusSel = select([{ value: '', label: 'All statuses' }, { value: 'active', label: 'Active' }, { value: 'pending_approval', label: 'Pending approval' }, { value: 'revoked', label: 'Revoked' }], { 'aria-label': 'Device status', 'data-testid': 'dev-status' }, state.status);
  const searchInp = input({ type: 'search', placeholder: 'Employee, model or device id', value: state.search, 'aria-label': 'Search devices' });
  statusSel.addEventListener('change', () => { state.status = statusSel.value; state.page = 1; load(); });
  searchInp.addEventListener('input', debounce(() => { state.search = searchInp.value.trim(); state.page = 1; load(); }, 300));
  const tableHost = el('div', { class: 'card flush' });
  replace(container, pageHead('Devices', '', [refreshButton(() => load())]),
    el('div', { class: 'card' }, el('div', { class: 'filter-bar' }, el('div', { class: 'field wide' }, el('span', { class: 'label' }, 'Search'), searchInp), field('Status', statusSel))), tableHost);

  let seq = 0;
  async function load() {
    const my = ++seq;
    replaceQuery(cleanQuery({ ...state, page: state.page > 1 ? state.page : '' }));
    replace(tableHost, el('div', { style: { padding: '16px 20px' } }, skeleton({ rows: 6, cols: 6 })));
    let data;
    try { data = await api.get('/admin/devices', cleanQuery({ status: state.status, search: state.search, page: state.page, page_size: PAGE_SIZE })); }
    catch (err) { if (my === seq) replace(tableHost, errorState(err, load)); return; }
    if (my !== seq) return;
    const table = dataTable([
      { key: 'employee', label: 'Employee', render: (d) => el('div', {}, employeeLink(d.employee || d.user), (d.employee || d.user)?.department ? el('div', { class: 'cell-sub' }, (d.employee || d.user).department.name) : null) },
      { key: 'model', label: 'Device', render: (d) => el('div', {}, el('div', { class: 'cell-primary' }, `${d.manufacturer || ''} ${d.model || ''}`.trim() || '—'), el('div', { class: 'cell-sub mono' }, d.device_id || '')) },
      { key: 'os_version', label: 'OS / app', render: (d) => `${d.os_version ? 'Android ' + d.os_version : '—'} · ${d.app_version || '—'}` },
      { key: 'status', label: 'Status', render: (d) => statusChip(d.status) },
      { key: 'integrity_verdict', label: 'Integrity', render: (d) => el('span', { class: `chip ${d.integrity_verdict === 'meets_device_integrity' ? 'green' : d.integrity_verdict === 'failed' ? 'red' : 'grey'}` }, titleCase(d.integrity_verdict || 'unknown')) },
      { key: 'registered_at', label: 'Registered', render: (d) => el('span', { class: 'nowrap' }, fmtDateTime(d.registered_at)) },
      { key: 'last_seen_at', label: 'Last seen', render: (d) => el('span', { class: 'muted' }, fmtRelative(d.last_seen_at)) },
    ], {
      rows: data.items || [], caption: 'Devices',
      emptyText: state.status || state.search ? 'No devices match these filters.' : 'No devices registered yet.',
      rowActions: canWrite ? (d) => [
        d.status === 'pending_approval' ? button('Approve', { kind: 'success', size: 'sm', icon: 'check', onClick: () => approveDevice(d, load), attrs: { 'data-testid': 'dev-approve' } }) : null,
        d.status !== 'revoked' ? button('Revoke', { kind: 'danger', size: 'sm', onClick: () => revokeDevice(d, load) }) : null,
      ] : null,
      pager: { page: data.page || state.page, pageSize: data.page_size || PAGE_SIZE, total: data.total || 0, onPage: (p) => { state.page = p; load(); } },
    });
    replace(tableHost, el('div', { style: { padding: '8px 12px 12px' } }, table));
  }
  load();
  return () => { seq += 1; };
}
