// Overview: stat tiles, 14-day trend, department breakdown, recent activity.

import { api } from '../api.js';
import { session } from '../session.js';
import { el, replace, pageHead, card, statTile, emptyState, errorState, skeleton, input, field, frag } from '../ui.js';
import { lineChart, legend } from '../charts.js';
import { fmtWorkDate, fmtRelative, todayLocal, fmtNumber } from '../format.js';
import { replaceQuery } from '../router.js';
import { refreshButton } from '../data.js';

const TILES = [
  { key: 'total_employees', label: 'Total employees', icon: 'users', color: '' },
  { key: 'clocked_in', label: 'Clocked in', icon: 'check', color: 'green' },
  { key: 'clocked_out', label: 'Clocked out', icon: 'logout', color: '' },
  { key: 'absent', label: 'Absent', icon: 'x', color: 'red' },
  { key: 'late_arrivals', label: 'Late arrivals', icon: 'clock', color: 'orange' },
  { key: 'pending_confirmations', label: 'Pending confirmations', icon: 'pending', color: 'orange', link: '#/pending' },
  { key: 'under_review', label: 'Under review', icon: 'eye', color: 'orange', link: '#/pending?view=under_review' },
  { key: 'exceptions', label: 'Exceptions', icon: 'exceptions', color: 'red', link: '#/exceptions' },
  { key: 'pending_requests', label: 'Pending requests', icon: 'records', color: 'orange', link: '#/pending?view=requests' },
  { key: 'pending_devices', label: 'Pending devices', icon: 'devices', color: 'orange', link: '#/devices?status=pending_approval' },
];

export function render(container, { setTitle, query }) {
  let date = /^\d{4}-\d{2}-\d{2}$/.test(query.date || '') ? query.date : todayLocal();
  setTitle('Overview', 'Attendance at a glance');

  const dateInput = input({ type: 'date', value: date, max: todayLocal(), 'aria-label': 'Overview date', 'data-testid': 'overview-date' });
  const body = el('div', { class: 'stack', style: { gap: '20px' } });
  const head = pageHead('Overview', '', [field('Date', dateInput), refreshButton(() => load())]);
  replace(container, head, body);

  dateInput.addEventListener('change', () => {
    if (!dateInput.value) return;
    date = dateInput.value;
    replaceQuery({ date: date === todayLocal() ? '' : date });
    load();
  });

  async function load() {
    replace(body,
      el('div', { class: 'grid overview-tiles' }, TILES.map(() => el('div', { class: 'card stat-tile' }, skeleton({ rows: 2, cols: 1 })))),
      el('div', { class: 'card' }, skeleton({ rows: 4, cols: 1, tall: true })));
    let data;
    try {
      data = await api.get('/admin/overview', { date });
    } catch (err) {
      replace(body, errorState(err, load));
      return;
    }
    const stats = data.stats || {};
    const tiles = el('div', { class: 'grid overview-tiles', 'data-testid': 'overview-tiles' }, TILES.map((t) => {
      const tile = statTile({ label: t.label, value: fmtNumber(stats[t.key]), icon: t.icon, color: t.color });
      tile.dataset.stat = t.key;
      if (t.link && session.can('attendance:review')) {
        const a = el('a', { href: t.link, class: 'tile-link', 'aria-label': `${t.label}: open` });
        a.appendChild(tile);
        a.style.textDecoration = 'none'; a.style.color = 'inherit';
        return a;
      }
      return tile;
    }));

    const trend = Array.isArray(data.attendance_trend) ? data.attendance_trend : [];
    let trendBody;
    if (!trend.length) trendBody = emptyState('No attendance history yet', 'The trend appears once sessions have been recorded.');
    else {
      const labels = trend.map((t) => (t.work_date || '').slice(5));
      const series = [
        { name: 'Present', values: trend.map((t) => Number(t.present || 0)), color: '#1FA463' },
        { name: 'Late', values: trend.map((t) => Number(t.late || 0)), color: '#F28C28' },
        { name: 'Absent', values: trend.map((t) => Number(t.absent || 0)), color: '#E5484D' },
      ];
      trendBody = frag(lineChart({ series, labels, height: 240 }), legend(series));
    }
    const trendCard = card(`Attendance trend · last ${trend.length || 14} days`, trendBody, { className: 'chart-card span-2' });

    const depts = Array.isArray(data.department_breakdown) ? data.department_breakdown : [];
    const deptBody = depts.length
      ? el('div', { class: 'dept-bars' }, depts.map((d) => {
        const total = Number(d.total || 0);
        const present = Number(d.present || 0);
        const pct = total > 0 ? Math.round((present / total) * 100) : 0;
        return el('div', { class: 'dept-bar', title: `${d.department}: ${present} of ${total} present (${pct}%)` },
          el('span', { class: 'nowrap', style: { overflow: 'hidden', textOverflow: 'ellipsis' } }, d.department || '—'),
          el('div', { class: 'progress', role: 'progressbar', 'aria-valuenow': pct, 'aria-valuemin': 0, 'aria-valuemax': 100, 'aria-label': `${d.department} present` }, el('span', { style: { width: `${pct}%` } })),
          el('span', { class: 'num' }, `${present}/${total}`));
      }))
      : emptyState('No departments with employees', 'Add departments under Workplace configuration.');
    const deptCard = card('Present by department', deptBody);

    const activity = Array.isArray(data.recent_activity) ? data.recent_activity : [];
    const actBody = activity.length
      ? el('ul', { class: 'activity' }, activity.map((a) => el('li', {},
        el('span', { class: 'dot', style: { background: a.kind && /reject|expired|revok/.test(a.kind) ? '#E5484D' : (a.kind && /pending|review|late/.test(a.kind) ? '#F28C28' : '#7FA6F5') } }),
        a.employee_id ? el('a', { href: `#/employees/${a.employee_id}`, style: { color: 'inherit', textDecoration: 'none' } }, a.text) : el('span', {}, a.text),
        el('time', { datetime: a.at, title: a.at }, fmtRelative(a.at)))))
      : emptyState('No recent activity', 'Confirmed events, reviews and changes will appear here.');
    const actCard = card('Recent activity', actBody);

    replace(body,
      el('p', { class: 'muted small' }, `Showing ${fmtWorkDate(data.work_date || date)} · computed by the server from recorded data`),
      tiles,
      el('div', { class: 'grid grid-3' }, trendCard, deptCard),
      actCard);
  }

  load();
  return () => {};
}
