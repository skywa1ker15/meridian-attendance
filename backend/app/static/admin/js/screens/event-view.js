// Shared rendering of an attendance Event (verification signals, timestamps).

import { el, statusChip } from '../ui.js';
import { fmtDateTime, fmtDistance, titleCase } from '../format.js';
import { STATUS_COLOR } from '../format.js';

const SIGNALS = [
  ['inside_geofence', 'Inside geofence'], ['accuracy_ok', 'Accuracy OK'], ['wifi_matched', 'Wi-Fi matched'],
  ['timestamp_plausible', 'Timestamp plausible'], ['device_active', 'Device active'],
];

export function verificationChips(ev) {
  const v = ev.verification || {};
  const wrap = el('div', { class: 'verif-list' });
  SIGNALS.forEach(([k, label]) => {
    if (v[k] === undefined || v[k] === null) return;
    wrap.appendChild(el('span', { class: `chip ${v[k] ? 'ok' : 'bad'}` }, `${v[k] ? '✓' : '✗'} ${label}`));
  });
  if (ev.verification_score !== undefined && ev.verification_score !== null) {
    wrap.appendChild(el('span', { class: 'chip grey', title: 'Verification score' }, `Score ${Number(ev.verification_score).toFixed(2)}`));
  }
  if (!wrap.childNodes.length) wrap.appendChild(el('span', { class: 'muted small' }, 'No verification signals recorded'));
  return wrap;
}

export function eventMeta(ev) {
  const item = (label, value) => el('div', {}, el('span', {}, label), el('span', {}, value ?? '—'));
  return el('div', { class: 'meta' },
    item('Source', titleCase(ev.source)),
    item('Workplace', ev.workplace_name || '—'),
    item('Detected (device)', fmtDateTime(ev.detected_at)),
    item('Received (server)', fmtDateTime(ev.received_at)),
    item('Official time', ev.official_at ? fmtDateTime(ev.official_at) : '—'),
    item('Confirmed', ev.confirmed_at ? fmtDateTime(ev.confirmed_at) : '—'),
    item('Distance from workplace', fmtDistance(ev.distance_from_workplace_m)),
    item('Location accuracy', ev.location?.accuracy_m !== undefined && ev.location?.accuracy_m !== null ? `${Math.round(ev.location.accuracy_m)} m` : '—'),
    item('Wi-Fi verified', ev.wifi_verified ? 'Yes' : 'No'),
    item('Offline queued', ev.offline_queued ? 'Yes' : 'No'),
    ev.review_reason ? item('Review reason', ev.review_reason) : null,
    ev.review_note ? item('Review note', ev.review_note) : null,
    ev.reviewed_at ? item('Reviewed', fmtDateTime(ev.reviewed_at)) : null);
}

export function eventTitle(ev) {
  return el('div', { class: 'row' },
    el('strong', {}, titleCase(ev.event_type)),
    statusChip(ev.status),
    ev.source === 'admin' ? el('span', { class: 'chip grey' }, 'Admin') : null);
}

/** Timeline item for a session's events list. */
export function timelineItem(ev) {
  const notes = (ev.verification && Array.isArray(ev.verification.notes)) ? ev.verification.notes : [];
  return el('li', { class: STATUS_COLOR[ev.status] || '' },
    el('div', { class: 'event-item' }, eventTitle(ev), eventMeta(ev), verificationChips(ev),
      notes.length ? el('ul', { class: 'small muted', style: { margin: '8px 0 0', paddingLeft: '18px' } }, notes.map((n) => el('li', {}, n))) : null));
}
