// Audit logs: filterable list with before/after diff viewer.

import { api } from '../api.js';
import { el, replace, pageHead, dataTable, input, field, skeleton, errorState, openDrawer, kv, jsonDiff, debounce, select } from '../ui.js';
import { fmtDateTime, titleCase } from '../format.js';
import { replaceQuery } from '../router.js';
import { employeePicker, dateRange, rangeFromQuery, cleanQuery, parsePage, refreshButton } from '../data.js';

const PAGE_SIZE = 50;
const ENTITY_TYPES = ['user', 'device', 'workplace', 'policy', 'department', 'attendance_event', 'attendance_session', 'manual_request', 'notification', 'auth'];

export function render(container, { setTitle, query }) {
  setTitle('Audit logs', 'Every administrative change is recorded');
  const range = rangeFromQuery(query, 'last_7');
  const state = { ...range, actor_id: query.actor_id || '', action: query.action || '', entity_type: query.entity_type || '', entity_id: query.entity_id || '', search: query.search || '', page: parsePage(query.page) };

  const dr = dateRange({ from: state.from, to: state.to, onChange: ({ from, to }) => { state.from = from; state.to = to; state.page = 1; load(); } });
  const actor = employeePicker({ placeholder: 'Filter by actor', onSelect: (u) => { state.actor_id = u ? u.id : ''; state.page = 1; load(); } });
  const actionInp = input({ value: state.action, placeholder: 'e.g. employee.create', 'aria-label': 'Action', class: 'input mono' });
  const entitySel = select([{ value: '', label: 'Any entity' }, ...ENTITY_TYPES.map((t) => ({ value: t, label: titleCase(t) }))], { 'aria-label': 'Entity type' }, state.entity_type);
  if (state.entity_type && !ENTITY_TYPES.includes(state.entity_type)) entitySel.appendChild(el('option', { value: state.entity_type, selected: true }, state.entity_type));
  const entityIdInp = input({ value: state.entity_id, placeholder: 'Entity id (UUID)', 'aria-label': 'Entity id', class: 'input mono' });
  const searchInp = input({ type: 'search', value: state.search, placeholder: 'Search text', 'aria-label': 'Search audit logs', 'data-testid': 'audit-search' });
  const reload = debounce(() => { state.action = actionInp.value.trim(); state.entity_id = entityIdInp.value.trim(); state.search = searchInp.value.trim(); state.page = 1; load(); }, 350);
  [actionInp, entityIdInp, searchInp].forEach((i) => i.addEventListener('input', reload));
  entitySel.addEventListener('change', () => { state.entity_type = entitySel.value; state.page = 1; load(); });

  const tableHost = el('div', { class: 'card flush' });
  replace(container, pageHead('Audit logs', '', [refreshButton(() => load())]),
    el('div', { class: 'card stack' }, dr.el,
      el('div', { class: 'filter-bar' },
        el('div', { class: 'field wide' }, el('span', { class: 'label' }, 'Actor'), actor.el),
        field('Action', actionInp), field('Entity type', entitySel), field('Entity id', entityIdInp), el('div', { class: 'field wide' }, el('label', { for: searchInp.id = 'audit-search' }, 'Search'), searchInp))),
    tableHost);

  let seq = 0;
  async function load() {
    const my = ++seq;
    replaceQuery(cleanQuery({ ...state, page: state.page > 1 ? state.page : '' }));
    replace(tableHost, el('div', { style: { padding: '16px 20px' } }, skeleton({ rows: 8, cols: 5 })));
    let data;
    try { data = await api.get('/admin/audit-logs', cleanQuery({ from: state.from, to: state.to, actor_id: state.actor_id, action: state.action, entity_type: state.entity_type, entity_id: state.entity_id, search: state.search, page: state.page, page_size: PAGE_SIZE })); }
    catch (err) { if (my === seq) replace(tableHost, errorState(err, load)); return; }
    if (my !== seq) return;
    const table = dataTable([
      { key: 'created_at', label: 'When', render: (a) => el('span', { class: 'nowrap' }, fmtDateTime(a.created_at)) },
      { key: 'actor', label: 'Actor', render: (a) => (a.actor ? el('div', {}, el('div', { class: 'cell-primary' }, a.actor.name || '—'), el('div', { class: 'cell-sub' }, `${a.actor.employee_number || ''} · ${titleCase(a.actor.role || '')}`)) : el('span', { class: 'chip grey' }, titleCase(a.actor_type || 'system'))) },
      { key: 'action', label: 'Action', render: (a) => el('span', { class: 'mono' }, a.action) },
      { key: 'entity_type', label: 'Entity', render: (a) => el('div', {}, titleCase(a.entity_type || ''), a.entity_id ? el('div', { class: 'cell-sub mono' }, String(a.entity_id).slice(0, 8) + '…') : null) },
      { key: 'ip', label: 'IP', render: (a) => el('span', { class: 'mono small' }, a.ip || '—') },
    ], {
      rows: data.items || [], caption: 'Audit log entries', onRowClick: openEntry,
      emptyText: 'No audit entries match these filters.',
      pager: { page: data.page || state.page, pageSize: data.page_size || PAGE_SIZE, total: data.total || 0, onPage: (p) => { state.page = p; load(); } },
    });
    replace(tableHost, el('div', { style: { padding: '8px 12px 12px' }, 'data-testid': 'audit-table' }, table));
  }

  function openEntry(a) {
    openDrawer({
      title: a.action,
      body: el('div', { class: 'stack' },
        el('div', {},
          kv('When', fmtDateTime(a.created_at)),
          kv('Actor', a.actor ? `${a.actor.name} (${a.actor.employee_number || '—'}, ${titleCase(a.actor.role || '')})` : titleCase(a.actor_type || 'system')),
          kv('Entity', `${titleCase(a.entity_type || '')}`), kv('Entity id', a.entity_id ? el('span', { class: 'mono small' }, a.entity_id) : '—'),
          kv('IP address', a.ip || '—'), kv('User agent', a.user_agent ? el('span', { class: 'small' }, a.user_agent) : '—')),
        el('h3', {}, 'Changes'),
        el('div', { class: 'audit-detail' }, jsonDiff(a.before, a.after)),
        a.entity_type === 'user' && a.entity_id ? el('a', { href: `#/employees/${a.entity_id}` }, 'Open employee profile') : null),
    });
  }

  load();
  return () => { seq += 1; };
}
