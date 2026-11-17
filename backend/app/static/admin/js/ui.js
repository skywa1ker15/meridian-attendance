// Small component library: DOM helpers, icons, toasts, modals, drawers,
// tables, pagers, chips, form fields, states. No innerHTML with user data.

import { statusLabel, STATUS_COLOR } from './format.js';

// ---------- DOM helpers ----------
export function el(tag, attrs = {}, ...children) {
  const node = document.createElement(tag);
  if (attrs) {
    for (const [k, v] of Object.entries(attrs)) {
      if (v === undefined || v === null || v === false) continue;
      if (k === 'class' || k === 'className') node.className = v;
      else if (k === 'dataset') Object.assign(node.dataset, v);
      else if (k === 'style' && typeof v === 'object') Object.assign(node.style, v);
      else if (k.startsWith('on') && typeof v === 'function') node.addEventListener(k.slice(2).toLowerCase(), v);
      else if (k === 'html') node.innerHTML = v; // only for trusted static markup (icons)
      else if (v === true) node.setAttribute(k, '');
      else node.setAttribute(k, String(v));
    }
  }
  append(node, children);
  return node;
}

export function append(node, children) {
  for (const c of children.flat(Infinity)) {
    if (c === null || c === undefined || c === false) continue;
    node.appendChild(c instanceof Node ? c : document.createTextNode(String(c)));
  }
  return node;
}

export function clear(node) { while (node.firstChild) node.removeChild(node.firstChild); return node; }
export function replace(node, ...children) { clear(node); append(node, children); return node; }
export function frag(...children) { const f = document.createDocumentFragment(); append(f, children); return f; }

export function debounce(fn, ms = 300) {
  let t;
  return (...args) => { clearTimeout(t); t = setTimeout(() => fn(...args), ms); };
}

// ---------- Icons (inline SVG, stroke icons) ----------
const ICONS = {
  dashboard: '<rect x="3" y="3" width="7" height="9" rx="1.5"/><rect x="14" y="3" width="7" height="5" rx="1.5"/><rect x="14" y="12" width="7" height="9" rx="1.5"/><rect x="3" y="16" width="7" height="5" rx="1.5"/>',
  users: '<circle cx="9" cy="8" r="3.5"/><path d="M2.5 20a6.5 6.5 0 0 1 13 0"/><circle cx="17" cy="9" r="2.5"/><path d="M15.5 14.5a5 5 0 0 1 6 5"/>',
  user: '<circle cx="12" cy="8" r="4"/><path d="M4 21a8 8 0 0 1 16 0"/>',
  live: '<circle cx="12" cy="12" r="3"/><path d="M5 5a10 10 0 0 0 0 14M19 5a10 10 0 0 1 0 14M8 8a6 6 0 0 0 0 8M16 8a6 6 0 0 1 0 8"/>',
  records: '<path d="M6 3h9l5 5v13H6z"/><path d="M14 3v6h6M9 13h6M9 17h6"/>',
  pending: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
  exceptions: '<path d="M12 3 2.5 20h19z"/><path d="M12 10v4M12 17.5v.5"/>',
  reports: '<path d="M4 20h16M7 16V9M12 16V4M17 16v-6"/>',
  workplace: '<path d="M12 21s7-6 7-11a7 7 0 1 0-14 0c0 5 7 11 7 11z"/><circle cx="12" cy="10" r="2.5"/>',
  devices: '<rect x="7" y="2.5" width="10" height="19" rx="2"/><path d="M11 18h2"/>',
  roles: '<path d="M12 3 4 6v6c0 4.5 3.5 8 8 9 4.5-1 8-4.5 8-9V6z"/><path d="m9.5 12 2 2 3.5-4"/>',
  audit: '<path d="M4 5h16v14H4z"/><path d="M8 9h8M8 13h5"/>',
  logout: '<path d="M10 4H5v16h5M14 8l4 4-4 4M18 12H9"/>',
  menu: '<path d="M4 7h16M4 12h16M4 17h16"/>',
  search: '<circle cx="11" cy="11" r="6.5"/><path d="m20 20-4.5-4.5"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  download: '<path d="M12 4v11M7 10l5 5 5-5M4 20h16"/>',
  check: '<path d="m5 12 4 4L19 7"/>',
  x: '<path d="M6 6l12 12M18 6 6 18"/>',
  refresh: '<path d="M20 11a8 8 0 0 0-14.5-4.5L4 8M4 4v4h4M4 13a8 8 0 0 0 14.5 4.5L20 16M20 20v-4h-4"/>',
  edit: '<path d="M4 20h4l10-10-4-4L4 16z"/><path d="m12.5 7.5 4 4"/>',
  key: '<circle cx="8" cy="14" r="4"/><path d="M11 11l9-9M16 6l2 2M13 9l2 2"/>',
  eye: '<path d="M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12z"/><circle cx="12" cy="12" r="3"/>',
  clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
  bell: '<path d="M6 16V11a6 6 0 1 1 12 0v5l2 2H4z"/><path d="M10 20a2 2 0 0 0 4 0"/>',
  info: '<circle cx="12" cy="12" r="9"/><path d="M12 11v5M12 8v.5"/>',
  arrowLeft: '<path d="M19 12H5M11 18l-6-6 6-6"/>',
  chevronLeft: '<path d="m15 6-6 6 6 6"/>',
  chevronRight: '<path d="m9 6 6 6-6 6"/>',
  copy: '<rect x="9" y="9" width="11" height="11" rx="2"/><path d="M5 15V5a1 1 0 0 1 1-1h10"/>',
  shield: '<path d="M12 3 4 6v6c0 4.5 3.5 8 8 9 4.5-1 8-4.5 8-9V6z"/>',
  wifi: '<path d="M2 9a16 16 0 0 1 20 0M5.5 12.5a11 11 0 0 1 13 0M9 16a5.5 5.5 0 0 1 6 0"/><circle cx="12" cy="19" r="1"/>',
  warning: '<path d="M12 3 2.5 20h19z"/><path d="M12 10v4M12 17.5v.5"/>',
  send: '<path d="m3 11 18-8-8 18-2-8z"/>',
  building: '<rect x="4" y="3" width="16" height="18" rx="1.5"/><path d="M9 7h2M13 7h2M9 11h2M13 11h2M9 15h2M13 15h2M10 21v-3h4v3"/>',
  settings: '<circle cx="12" cy="12" r="3"/><path d="M19 12a7 7 0 0 0-.1-1.2l2-1.5-2-3.5-2.3 1a7 7 0 0 0-2-1.2L14.2 3h-4.4l-.4 2.6a7 7 0 0 0-2 1.2l-2.3-1-2 3.5 2 1.5a7 7 0 0 0 0 2.4l-2 1.5 2 3.5 2.3-1a7 7 0 0 0 2 1.2l.4 2.6h4.4l.4-2.6a7 7 0 0 0 2-1.2l2.3 1 2-3.5-2-1.5c.1-.4.1-.8.1-1.2z"/>',
};

export function icon(name, size = 18) {
  const span = document.createElement('span');
  span.className = 'icon';
  span.setAttribute('aria-hidden', 'true');
  span.innerHTML = `<svg width="${size}" height="${size}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">${ICONS[name] || ''}</svg>`;
  return span;
}

// ---------- Buttons ----------
export function button(label, { kind = '', size = '', icon: ic, onClick, type = 'button', title, disabled, attrs = {} } = {}) {
  const b = el('button', { class: `btn ${kind} ${size}`.trim(), type, title, disabled, ...attrs }, ic ? icon(ic, 16) : null, label);
  if (onClick) b.addEventListener('click', onClick);
  return b;
}

export function setBusy(btn, busy, busyLabel) {
  if (!btn) return;
  if (busy) {
    btn.dataset.label = btn.textContent;
    btn.disabled = true;
    if (busyLabel) btn.textContent = busyLabel;
  } else {
    btn.disabled = false;
    if (btn.dataset.label && busyLabel) btn.textContent = btn.dataset.label;
  }
}

// ---------- Toasts ----------
export function toast(message, kind = 'info', ms = 4500) {
  const host = document.getElementById('toasts');
  const t = el('div', { class: `toast ${kind}`, role: kind === 'error' ? 'alert' : 'status' },
    el('span', {}, message),
    el('button', { class: 'toast-close', 'aria-label': 'Dismiss', onClick: () => t.remove() }, '×'));
  host.appendChild(t);
  if (ms) setTimeout(() => t.remove(), ms);
  return t;
}

export function errorMessage(err) {
  if (!err) return 'Something went wrong.';
  if (err.name === 'ApiError') {
    if (err.code === 'validation_error' && err.details && err.details.fields) {
      const f = err.details.fields;
      const parts = Array.isArray(f) ? f.map((x) => (typeof x === 'string' ? x : `${x.field || x.loc || ''}: ${x.message || x.msg || ''}`)) : Object.entries(f).map(([k, v]) => `${k}: ${Array.isArray(v) ? v.join(', ') : v}`);
      return `${err.message} ${parts.join('; ')}`.trim();
    }
    return err.message;
  }
  return err.message || String(err);
}

// ---------- Modals ----------
export function openModal({ title, body, wide = false, onClose, closable = true, labelledBy } = {}) {
  const host = document.getElementById('modals');
  const titleId = labelledBy || `modal-title-${Math.random().toString(36).slice(2, 8)}`;
  const modal = el('div', { class: `modal ${wide ? 'wide' : ''}`, role: 'dialog', 'aria-modal': 'true', 'aria-labelledby': titleId });
  const closeBtn = el('button', { class: 'modal-close', 'aria-label': 'Close', type: 'button' }, '×');
  const head = el('div', { class: 'modal-head' }, el('h2', { id: titleId }, title), closable ? closeBtn : null);
  const bodyEl = el('div', { class: 'modal-body' });
  append(bodyEl, [body]);
  modal.appendChild(head); modal.appendChild(bodyEl);
  const backdrop = el('div', { class: 'modal-backdrop' }, modal);
  const previous = document.activeElement;
  function close(result) {
    if (!backdrop.isConnected) return;
    backdrop.remove();
    document.removeEventListener('keydown', onKey);
    if (previous && previous.focus) previous.focus();
    if (onClose) onClose(result);
  }
  function onKey(e) {
    if (e.key === 'Escape' && closable) { e.stopPropagation(); close(); }
    if (e.key === 'Tab') trapFocus(e, modal);
  }
  closeBtn.addEventListener('click', () => close());
  backdrop.addEventListener('mousedown', (e) => { if (e.target === backdrop && closable) close(); });
  document.addEventListener('keydown', onKey);
  host.appendChild(backdrop);
  const first = modal.querySelector('input, select, textarea, button:not(.modal-close)');
  (first || closeBtn).focus();
  return { close, el: modal, body: bodyEl };
}

function trapFocus(e, container) {
  const focusables = container.querySelectorAll('a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])');
  if (!focusables.length) return;
  const first = focusables[0];
  const last = focusables[focusables.length - 1];
  if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
  else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
}

/**
 * Confirmation dialog. Resolves to { note } when confirmed, or null when cancelled.
 * Options: requireText (must be typed exactly), note: { label, required, placeholder }, fields: extra form nodes.
 */
export function confirmDialog({ title, message, confirmLabel = 'Confirm', cancelLabel = 'Cancel', danger = false, requireText = null, note = null, extra = null } = {}) {
  return new Promise((resolve) => {
    const errBox = el('div', { class: 'form-error hidden' });
    const noteInput = note ? el('textarea', { class: 'input', placeholder: note.placeholder || '', 'aria-label': note.label || 'Note', rows: 3 }) : null;
    const typedInput = requireText ? el('input', { class: 'input mono', autocomplete: 'off', 'aria-label': `Type ${requireText} to confirm` }) : null;
    const confirmBtn = button(confirmLabel, { kind: danger ? 'danger' : '' });
    const cancelBtn = button(cancelLabel, { kind: 'secondary' });
    const body = el('div', { class: 'stack' },
      message ? el('p', {}, message) : null,
      extra,
      note ? field(note.label || 'Note', noteInput, { hint: note.required ? 'Required' : 'Optional' }) : null,
      requireText ? field(`Type "${requireText}" to confirm`, typedInput) : null,
      errBox,
      el('div', { class: 'form-actions' }, cancelBtn, confirmBtn));
    const m = openModal({ title, body, onClose: (r) => resolve(r === undefined ? null : r) });
    cancelBtn.addEventListener('click', () => m.close(null));
    confirmBtn.addEventListener('click', () => {
      if (note && note.required && !noteInput.value.trim()) { errBox.textContent = `${note.label || 'A note'} is required.`; errBox.classList.remove('hidden'); noteInput.focus(); return; }
      if (requireText && typedInput.value.trim() !== requireText) { errBox.textContent = `Please type "${requireText}" exactly to confirm.`; errBox.classList.remove('hidden'); typedInput.focus(); return; }
      m.close({ note: noteInput ? noteInput.value.trim() : '' });
    });
  });
}

// ---------- Drawer ----------
export function openDrawer({ title, body, onClose } = {}) {
  const host = document.getElementById('modals');
  const closeBtn = el('button', { class: 'modal-close', 'aria-label': 'Close', type: 'button' }, '×');
  const titleEl = el('h2', {}, title);
  const drawer = el('aside', { class: 'drawer', role: 'dialog', 'aria-modal': 'true', 'aria-label': typeof title === 'string' ? title : 'Details' },
    el('div', { class: 'modal-head' }, titleEl, closeBtn));
  const bodyEl = el('div', { class: 'stack' });
  append(bodyEl, [body]);
  drawer.appendChild(bodyEl);
  const backdrop = el('div', { class: 'drawer-backdrop' });
  function close() {
    if (!drawer.isConnected) return;
    drawer.remove(); backdrop.remove();
    document.removeEventListener('keydown', onKey);
    if (onClose) onClose();
  }
  function onKey(e) { if (e.key === 'Escape') close(); }
  closeBtn.addEventListener('click', close);
  backdrop.addEventListener('click', close);
  document.addEventListener('keydown', onKey);
  host.appendChild(backdrop); host.appendChild(drawer);
  closeBtn.focus();
  return { close, body: bodyEl, setTitle: (t) => { titleEl.textContent = t; } };
}

// ---------- States ----------
export function loading(text = 'Loading…') {
  return el('div', { class: 'state', role: 'status' }, el('div', { class: 'spinner' }), text);
}
export function emptyState(text = 'Nothing to show.', sub = null) {
  return el('div', { class: 'state' }, el('strong', {}, text), sub);
}
/** Skeleton placeholder for tables/cards while data loads. */
export function skeleton({ rows = 5, cols = 4, tall = false } = {}) {
  const wrap = el('div', { class: 'skeleton', role: 'status', 'aria-label': 'Loading' });
  for (let r = 0; r < rows; r += 1) {
    const row = el('div', { class: 'skeleton-row', style: { gridTemplateColumns: `repeat(${cols}, minmax(0, 1fr))` } });
    for (let c = 0; c < cols; c += 1) row.appendChild(el('div', { class: `bone ${tall ? 'tall' : ''}`, style: { width: `${60 + ((r * 7 + c * 13) % 40)}%` } }));
    wrap.appendChild(row);
  }
  return wrap;
}

/** Show an API error as a toast (403 and friends handled gracefully). */
export function handleError(err, fallback) {
  console.error(err);
  if (err && err.name === 'ApiError' && err.status === 403) {
    toast(err.message || fallback || 'You do not have permission to do this.', 'warning');
    return;
  }
  toast(errorMessage(err) || fallback || 'Something went wrong.', 'error');
}

export function errorState(err, retry) {
  return el('div', { class: 'state error', role: 'alert' },
    el('strong', {}, 'Could not load'),
    el('p', {}, errorMessage(err)),
    retry ? button('Try again', { kind: 'secondary', size: 'sm', icon: 'refresh', onClick: retry }) : null);
}

// ---------- Chips ----------
export function statusChip(status, label) {
  const color = STATUS_COLOR[status] || 'grey';
  return el('span', { class: `chip ${color}` }, label || statusLabel(status));
}
export function boolChip(v, yes = 'Yes', no = 'No') { return el('span', { class: `chip ${v ? 'green' : 'grey'}` }, v ? yes : no); }

export function pillTabs(tabs, active, onChange) {
  const wrap = el('div', { class: 'pill-tabs', role: 'tablist' });
  tabs.forEach((t) => {
    const b = el('button', { type: 'button', role: 'tab', 'aria-selected': String(t.key === active), dataset: { tab: t.key } }, t.label);
    b.addEventListener('click', () => {
      wrap.querySelectorAll('button').forEach((x) => x.setAttribute('aria-selected', 'false'));
      b.setAttribute('aria-selected', 'true');
      onChange(t.key);
    });
    wrap.appendChild(b);
  });
  return wrap;
}

// ---------- Form fields ----------
export function field(label, control, { hint, error, id } = {}) {
  const fid = id || control.id || `f-${Math.random().toString(36).slice(2, 8)}`;
  control.id = fid;
  return el('div', { class: 'field' },
    el('label', { for: fid }, label),
    control,
    hint ? el('span', { class: 'hint' }, hint) : null,
    error ? el('span', { class: 'error', role: 'alert' }, error) : null);
}
export function input(attrs = {}) { return el('input', { class: 'input', ...attrs }); }
export function textarea(attrs = {}) { return el('textarea', { class: 'input', ...attrs }); }
export function select(options, attrs = {}, value) {
  const s = el('select', { class: 'input', ...attrs });
  options.forEach((o) => {
    const opt = el('option', { value: o.value ?? o }, o.label ?? o);
    if (o.disabled) opt.disabled = true;
    s.appendChild(opt);
  });
  if (value !== undefined && value !== null) s.value = String(value);
  return s;
}
export function checkbox(label, attrs = {}) {
  const i = el('input', { type: 'checkbox', ...attrs });
  return { el: el('label', { class: 'checkbox' }, i, el('span', {}, label)), input: i };
}
export function switchInput(label, attrs = {}) {
  const i = el('input', { type: 'checkbox', role: 'switch', ...attrs });
  return { el: el('label', { class: 'switch' }, i, el('span', { class: 'track' }), el('span', {}, label)), input: i };
}
export function formError(msg) { return el('div', { class: `form-error ${msg ? '' : 'hidden'}`, role: 'alert' }, msg || ''); }
export function showFormError(box, err) {
  if (!box) return;
  const msg = typeof err === 'string' ? err : errorMessage(err);
  box.textContent = msg;
  box.classList.toggle('hidden', !msg);
}

export function copyBox(text) {
  const code = el('code', {}, text);
  const btn = button('Copy', { kind: 'secondary', size: 'sm', icon: 'copy' });
  btn.addEventListener('click', async () => {
    try { await navigator.clipboard.writeText(text); toast('Copied to clipboard', 'success', 2000); }
    catch (e) { const r = document.createRange(); r.selectNodeContents(code); const s = window.getSelection(); s.removeAllRanges(); s.addRange(r); toast('Select and copy the text manually.', 'warning'); }
  });
  return el('div', { class: 'copy-box' }, code, btn);
}

// ---------- Table ----------
/**
 * columns: [{ key, label, render?(row) -> node|string, sortable?, numeric?, className?, width? }]
 * options: { rows, onRowClick(row), rowActions(row) -> node[], sort: {key, dir}, onSort(key, dir), emptyText,
 *            pager: { page, pageSize, total, onPage(page) }, rowKey(row) }
 */
export function dataTable(columns, { rows = [], onRowClick, rowActions, sort, onSort, emptyText = 'No records found.', pager, rowClass, caption } = {}) {
  const table = el('table', { class: 'table' });
  if (caption) table.appendChild(el('caption', { class: 'sr-only' }, caption));
  const thead = el('thead');
  const tr = el('tr');
  columns.forEach((c) => {
    const th = el('th', { class: `${c.sortable ? 'sortable' : ''} ${c.numeric ? 'num' : ''} ${c.className || ''}`.trim(), scope: 'col', style: c.width ? { width: c.width } : null }, c.label);
    if (c.sortable) {
      th.tabIndex = 0;
      const active = sort && sort.key === c.key;
      th.setAttribute('aria-sort', active ? (sort.dir === 'asc' ? 'ascending' : 'descending') : 'none');
      th.appendChild(el('span', { class: 'sort-ind' }, active ? (sort.dir === 'asc' ? '▲' : '▼') : '↕'));
      const trigger = () => onSort && onSort(c.key, active && sort.dir === 'asc' ? 'desc' : 'asc');
      th.addEventListener('click', trigger);
      th.addEventListener('keydown', (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); trigger(); } });
    }
    tr.appendChild(th);
  });
  if (rowActions) tr.appendChild(el('th', { scope: 'col', class: 'num' }, 'Actions'));
  thead.appendChild(tr);
  table.appendChild(thead);
  const tbody = el('tbody');
  if (!rows.length) {
    tbody.appendChild(el('tr', {}, el('td', { colspan: columns.length + (rowActions ? 1 : 0) }, emptyState(emptyText))));
  }
  rows.forEach((row) => {
    const r = el('tr', { class: `${onRowClick ? 'clickable' : ''} ${rowClass ? rowClass(row) : ''}`.trim() });
    if (onRowClick) {
      r.tabIndex = 0;
      r.addEventListener('click', () => onRowClick(row));
      r.addEventListener('keydown', (e) => { if (e.key === 'Enter') onRowClick(row); });
    }
    columns.forEach((c) => {
      const td = el('td', { class: `${c.numeric ? 'num' : ''} ${c.className || ''}`.trim() });
      const v = c.render ? c.render(row) : row[c.key];
      append(td, [v === null || v === undefined ? '—' : v]);
      r.appendChild(td);
    });
    if (rowActions) {
      const td = el('td', { class: 'num' }, el('div', { class: 'row-actions' }, rowActions(row)));
      td.addEventListener('click', (e) => e.stopPropagation());
      td.addEventListener('keydown', (e) => e.stopPropagation());
      r.appendChild(td);
    }
    tbody.appendChild(r);
  });
  table.appendChild(tbody);
  const wrap = el('div', { class: 'table-wrap' }, table);
  if (pager) return el('div', {}, wrap, pagerEl(pager));
  return wrap;
}

export function pagerEl({ page = 1, pageSize = 50, total = 0, onPage }) {
  const pages = Math.max(1, Math.ceil(total / pageSize));
  const from = total === 0 ? 0 : (page - 1) * pageSize + 1;
  const to = Math.min(total, page * pageSize);
  const prev = button('', { kind: 'secondary', size: 'sm', icon: 'chevronLeft', title: 'Previous page', disabled: page <= 1, onClick: () => onPage(page - 1), attrs: { 'aria-label': 'Previous page' } });
  const next = button('', { kind: 'secondary', size: 'sm', icon: 'chevronRight', title: 'Next page', disabled: page >= pages, onClick: () => onPage(page + 1), attrs: { 'aria-label': 'Next page' } });
  return el('div', { class: 'pager' },
    el('span', {}, total ? `Showing ${from}–${to} of ${total}` : 'No results'),
    el('div', { class: 'pager-controls' }, prev, el('span', {}, `Page ${page} of ${pages}`), next));
}

// ---------- JSON diff (audit before/after) ----------
export function jsonDiff(before, after) {
  const b = before && typeof before === 'object' ? before : {};
  const a = after && typeof after === 'object' ? after : {};
  const keys = Array.from(new Set([...Object.keys(b), ...Object.keys(a)])).sort();
  const grid = el('div', { class: 'json-diff' }, el('div', { class: 'h' }, 'Field'), el('div', { class: 'h' }, 'Before'), el('div', { class: 'h' }, 'After'));
  if (!keys.length) {
    return el('div', { class: 'muted small' }, 'No data recorded for this entry.');
  }
  const show = (v) => (v === undefined ? '' : typeof v === 'object' ? JSON.stringify(v) : String(v));
  keys.forEach((k) => {
    const hasB = Object.prototype.hasOwnProperty.call(b, k);
    const hasA = Object.prototype.hasOwnProperty.call(a, k);
    const changed = hasB && hasA && JSON.stringify(b[k]) !== JSON.stringify(a[k]);
    grid.appendChild(el('div', { class: 'k' }, k));
    grid.appendChild(el('div', { class: !hasB ? '' : (!hasA ? 'removed' : (changed ? 'changed' : '')) }, hasB ? show(b[k]) : '—'));
    grid.appendChild(el('div', { class: !hasA ? '' : (!hasB ? 'added' : (changed ? 'changed' : '')) }, hasA ? show(a[k]) : '—'));
  });
  return grid;
}

// ---------- Page scaffolding ----------
export function pageHead(title, subtitle, actions = []) {
  return el('div', { class: 'page-head' },
    el('div', {}, el('h1', {}, title), subtitle ? el('p', {}, subtitle) : null),
    el('div', { class: 'page-actions' }, actions));
}

export function card(title, body, { actions = null, className = '' } = {}) {
  return el('section', { class: `card ${className}`.trim() },
    title ? el('div', { class: 'card-head' }, el('h3', {}, title), actions ? el('div', { class: 'row' }, actions) : null) : null,
    body);
}

export function statTile({ label, value, sub, icon: ic, color = '' }) {
  return el('div', { class: 'card stat-tile' },
    el('div', { class: `stat-icon ${color}` }, icon(ic || 'info', 18)),
    el('div', { class: 'stat-label' }, label),
    el('div', { class: 'stat-value' }, value === null || value === undefined ? '—' : String(value)),
    sub ? el('div', { class: 'stat-sub' }, sub) : null);
}

export function kv(label, value) { return el('div', { class: 'kv' }, el('span', {}, label), el('span', {}, value ?? '—')); }

/** Wrap an async loader: shows loading, then content or an error with retry. */
export async function withLoader(container, loader, placeholder) {
  replace(container, placeholder ? placeholder() : loading());
  try {
    const node = await loader();
    if (node) replace(container, node);
  } catch (err) {
    console.error(err);
    replace(container, errorState(err, () => withLoader(container, loader, placeholder)));
  }
}
