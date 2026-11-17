// Pending confirmations: pending + under-review events and manual requests.

import { api } from '../api.js';
import { el, replace, pageHead, button, pillTabs, skeleton, errorState, emptyState, confirmDialog, openModal, field, input, textarea, formError, showFormError, setBusy, toast, handleError, dataTable, pagerEl } from '../ui.js';
import { fmtDateTime, fmtWorkDate, fullName, titleCase, utcIsoToLocalInput, localInputToUtcIso } from '../format.js';
import { replaceQuery } from '../router.js';
import { countdown, employeeLink, refreshButton, cleanQuery, parsePage } from '../data.js';
import { eventTitle, eventMeta, verificationChips } from './event-view.js';

const PAGE_SIZE = 50;
const VIEWS = [
  { key: 'all', label: 'All events' }, { key: 'pending', label: 'Pending' }, { key: 'under_review', label: 'Under review' }, { key: 'requests', label: 'Manual requests' },
];

export function render(container, { setTitle, query, refreshBadge }) {
  setTitle('Pending confirmations', 'Detections awaiting confirmation or management review');
  const state = { view: VIEWS.some((v) => v.key === query.view) ? query.view : 'all', page: parsePage(query.page) };
  const body = el('div', { class: 'stack' });
  const tabs = pillTabs(VIEWS, state.view, (k) => { state.view = k; state.page = 1; load(); });
  replace(container, pageHead('Pending confirmations', '', [refreshButton(() => load())]), el('div', { class: 'row between' }, tabs), body);

  let seq = 0;
  let ticker = null;

  async function load() {
    const my = ++seq;
    clearInterval(ticker);
    replaceQuery(cleanQuery({ view: state.view === 'all' ? '' : state.view, page: state.page > 1 ? state.page : '' }));
    replace(body, el('div', { class: 'card' }, skeleton({ rows: 4, cols: 3, tall: true })));
    try {
      if (state.view === 'requests') {
        const data = await api.get('/admin/manual-requests', { status: 'pending', page: state.page, page_size: PAGE_SIZE });
        if (my !== seq) return;
        renderRequests(data);
        return;
      }
      let items = [];
      let total = 0;
      let pager = null;
      if (state.view === 'all') {
        const [p, r] = await Promise.all([
          api.get('/admin/attendance/events', { status: 'pending', page: 1, page_size: PAGE_SIZE }),
          api.get('/admin/attendance/events', { status: 'under_review', page: 1, page_size: PAGE_SIZE }),
        ]);
        if (my !== seq) return;
        items = [...(r.items || []), ...(p.items || [])];
        total = (p.total || 0) + (r.total || 0);
        if (total > items.length) pager = el('p', { class: 'muted small' }, `Showing the ${items.length} most recent of ${total}. Use the Pending / Under review tabs to page through all of them.`);
      } else {
        const data = await api.get('/admin/attendance/events', { status: state.view, page: state.page, page_size: PAGE_SIZE });
        if (my !== seq) return;
        items = data.items || [];
        total = data.total || 0;
        pager = pagerEl({ page: data.page || state.page, pageSize: data.page_size || PAGE_SIZE, total, onPage: (p) => { state.page = p; load(); } });
      }
      renderEvents(items, total, pager);
    } catch (err) {
      if (my === seq) replace(body, errorState(err, load));
    }
  }

  function renderEvents(items, total, pager) {
    if (!items.length) { replace(body, el('div', { class: 'card' }, emptyState('Nothing waiting for review', 'Pending detections and under-review events appear here as they arrive.'))); return; }
    const list = el('div', { class: 'review-list', 'data-testid': 'pending-list' }, items.map((ev) => eventCard(ev)));
    replace(body, el('p', { class: 'muted small' }, `${total} event${total === 1 ? '' : 's'} waiting`), list, pager);
    if (query.event_id) { const target = list.querySelector(`[data-event-id="${query.event_id}"]`); if (target) { target.scrollIntoView({ block: 'center' }); target.style.outline = '2px solid #7FA6F5'; } }
    ticker = setInterval(() => {
      list.querySelectorAll('[data-expires]').forEach((n) => { const c = countdown(n.dataset.expires); n.textContent = c.label; n.classList.toggle('red', c.expired); });
    }, 1000);
  }

  function eventCard(ev) {
    const c = countdown(ev.expires_at);
    const expiry = ev.status === 'pending' && ev.expires_at
      ? el('span', { class: `chip ${c.expired ? 'red' : 'orange'}`, title: `Expires ${fmtDateTime(ev.expires_at)}` }, 'Expires in ', el('span', { class: 'count-down', dataset: { expires: ev.expires_at } }, c.label))
      : null;
    const approveBtn = button('Approve', { kind: 'success', size: 'sm', icon: 'check', onClick: () => review(ev, 'approve'), attrs: { 'data-testid': 'approve-event' } });
    const rejectBtn = button('Reject', { kind: 'danger', size: 'sm', icon: 'x', onClick: () => review(ev, 'reject'), attrs: { 'data-testid': 'reject-event' } });
    return el('div', { class: 'card review-card', dataset: { eventId: ev.id } },
      el('div', { class: 'head' },
        el('div', {}, eventTitle(ev), el('div', { style: { marginTop: '6px' } }, employeeLink(ev.employee), ev.employee?.department ? el('span', { class: 'muted small' }, ` · ${ev.employee.department.name}`) : null)),
        el('div', { class: 'row' }, expiry, el('span', { class: 'muted small' }, `Received ${fmtDateTime(ev.received_at)}`))),
      eventMeta(ev), verificationChips(ev),
      ev.note ? el('p', { class: 'small muted', style: { marginTop: '8px' } }, `Employee note: ${ev.note}`) : null,
      el('div', { class: 'actions' }, approveBtn, rejectBtn));
  }

  async function review(ev, action) {
    const who = `${titleCase(ev.event_type)} by ${fullName(ev.employee)} at ${fmtDateTime(ev.detected_at)}`;
    const r = await confirmDialog(action === 'approve'
      ? { title: 'Approve event', confirmLabel: 'Approve', message: `${who} will be confirmed and the session updated.`, note: { label: 'Note', required: false, placeholder: 'Optional note for the audit log' } }
      : { title: 'Reject event', confirmLabel: 'Reject', danger: true, message: `${who} will be rejected and will not count as attendance.`, note: { label: 'Reason', required: true, placeholder: 'Why is this rejected?' } });
    if (!r) return;
    try {
      await api.post(`/admin/attendance/events/${ev.id}/${action}`, cleanQuery({ note: r.note }));
      toast(action === 'approve' ? 'Event approved.' : 'Event rejected.', 'success');
      refreshBadge && refreshBadge();
      load();
    } catch (err) { handleError(err); }
  }

  function renderRequests(data) {
    const items = data.items || [];
    const table = dataTable([
      { key: 'type', label: 'Type', render: (r) => el('span', { class: 'chip blue' }, titleCase(r.type)) },
      { key: 'employee', label: 'Employee', render: (r) => employeeLink(r.employee) },
      { key: 'work_date', label: 'Work date', render: (r) => fmtWorkDate(r.work_date) },
      { key: 'requested', label: 'Requested', render: (r) => (r.type === 'profile_correction' ? `${titleCase(r.field || '')} → ${r.requested_value ?? '—'}` : `${r.requested_clock_in ? fmtDateTime(r.requested_clock_in) : '—'} → ${r.requested_clock_out ? fmtDateTime(r.requested_clock_out) : '—'}`) },
      { key: 'reason', label: 'Reason', render: (r) => el('span', { title: r.reason }, r.reason && r.reason.length > 80 ? r.reason.slice(0, 80) + '…' : r.reason) },
      { key: 'created_at', label: 'Submitted', render: (r) => fmtDateTime(r.created_at) },
    ], {
      rows: items, caption: 'Pending manual requests', emptyText: 'No pending manual requests.',
      rowActions: (r) => [button('Approve', { kind: 'success', size: 'sm', onClick: () => approveRequest(r) }), button('Reject', { kind: 'danger', size: 'sm', onClick: () => rejectRequest(r) })],
      pager: { page: data.page || state.page, pageSize: data.page_size || PAGE_SIZE, total: data.total || 0, onPage: (p) => { state.page = p; load(); } },
    });
    replace(body, el('div', { class: 'card flush' }, el('div', { style: { padding: '8px 12px 12px' } }, table)));
  }

  function approveRequest(r) {
    const isTime = r.type !== 'profile_correction';
    const ci = input({ type: 'datetime-local', value: utcIsoToLocalInput(r.requested_clock_in) });
    const co = input({ type: 'datetime-local', value: utcIsoToLocalInput(r.requested_clock_out) });
    const note = textarea({ placeholder: 'Optional note' });
    const err = formError('');
    const submit = button('Approve request', { type: 'submit', kind: 'success' });
    const cancel = button('Cancel', { kind: 'secondary' });
    const form = el('form', { class: 'stack', novalidate: true },
      el('p', {}, `${titleCase(r.type)} for ${fullName(r.employee)} on ${fmtWorkDate(r.work_date)}.`),
      el('p', { class: 'muted small' }, `Reason given: ${r.reason || '—'}`),
      isTime ? el('div', { class: 'form-grid' }, field('Clock in (SAST)', ci, { hint: 'Adjust if needed' }), field('Clock out (SAST)', co)) : el('p', {}, `Field "${titleCase(r.field || '')}" will be set to "${r.requested_value ?? ''}".`),
      field('Note', note), err, el('div', { class: 'form-actions' }, cancel, submit));
    const m = openModal({ title: 'Approve manual request', body: form });
    cancel.addEventListener('click', () => m.close());
    form.addEventListener('submit', async (e) => {
      e.preventDefault();
      const bodyData = cleanQuery({ note: note.value.trim() });
      if (isTime) {
        const inIso = localInputToUtcIso(ci.value); const outIso = localInputToUtcIso(co.value);
        if (inIso && inIso !== r.requested_clock_in) bodyData.clock_in_at = inIso;
        if (outIso && outIso !== r.requested_clock_out) bodyData.clock_out_at = outIso;
        if (inIso && outIso && outIso <= inIso) { showFormError(err, 'Clock out must be after clock in.'); return; }
      }
      setBusy(submit, true, 'Approving…');
      try { await api.post(`/admin/manual-requests/${r.id}/approve`, bodyData); m.close(); toast('Request approved.', 'success'); refreshBadge && refreshBadge(); load(); }
      catch (ex) { showFormError(err, ex); } finally { setBusy(submit, false, 'Approving…'); }
    });
  }

  async function rejectRequest(r) {
    const res = await confirmDialog({ title: 'Reject manual request', danger: true, confirmLabel: 'Reject', message: `${titleCase(r.type)} for ${fullName(r.employee)} on ${fmtWorkDate(r.work_date)} will be rejected. The employee is notified with your note.`, note: { label: 'Reason', required: true } });
    if (!res) return;
    try { await api.post(`/admin/manual-requests/${r.id}/reject`, { note: res.note }); toast('Request rejected.', 'success'); refreshBadge && refreshBadge(); load(); }
    catch (err) { handleError(err); }
  }

  load();
  return () => { seq += 1; clearInterval(ticker); };
}
