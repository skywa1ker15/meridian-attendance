import { test } from 'node:test';
import assert from 'node:assert/strict';
import { installDomStub } from '../tools/dom-stub.mjs';
installDomStub();
const f = await import('../../../backend/app/static/admin/js/format.js');

test('fmtMinutes', () => {
  assert.equal(f.fmtMinutes(0), '0m');
  assert.equal(f.fmtMinutes(45), '45m');
  assert.equal(f.fmtMinutes(312), '5h 12m');
  assert.equal(f.fmtMinutes(60), '1h 00m');
  assert.equal(f.fmtMinutes(null), '—');
  assert.equal(f.fmtMinutes(-5), '0m');
});

test('times are shown in Africa/Johannesburg (UTC+2)', () => {
  assert.equal(f.fmtTime('2026-11-16T06:02:11Z'), '08:02');
  assert.equal(f.localDateOf(new Date('2026-11-16T22:30:00Z')), '2026-11-17');
  assert.equal(f.fmtTime(null), '—');
  assert.equal(f.fmtDateTime('not a date'), '—');
});

test('local datetime input <-> UTC ISO round trip', () => {
  assert.equal(f.localInputToUtcIso('2026-11-16T08:02'), '2026-11-16T06:02:00Z');
  assert.equal(f.localInputToUtcIso('2026-11-16T01:00'), '2026-11-15T23:00:00Z');
  assert.equal(f.utcIsoToLocalInput('2026-11-16T06:02:00Z'), '2026-11-16T08:02');
  assert.equal(f.localInputToUtcIso(''), null);
  assert.equal(f.utcIsoToLocalInput(null), '');
});

test('calendar helpers', () => {
  assert.equal(f.addDays('2026-11-30', 1), '2026-12-01');
  assert.equal(f.addDays('2026-03-01', -1), '2026-02-28');
  assert.equal(f.startOfWeek('2026-11-18'), '2026-11-16'); // Wed -> Mon
  assert.equal(f.startOfWeek('2026-11-22'), '2026-11-16'); // Sun -> Mon
  assert.equal(f.startOfMonth('2026-11-18'), '2026-11-01');
  assert.equal(f.endOfMonth('2028-02-10'), '2028-02-29');
  assert.equal(f.fmtWorkDate('2026-11-16'), f.fmtWorkDate('2026-11-16')); // stable
  assert.match(f.fmtWorkDate('2026-11-16'), /16/);
  assert.equal(f.fmtWorkDate(''), '—');
});

test('date presets return ordered ranges', () => {
  for (const p of f.DATE_PRESETS) {
    const [a, b] = p.range();
    assert.match(a, /^\d{4}-\d{2}-\d{2}$/);
    assert.ok(a <= b, `${p.key}: ${a} <= ${b}`);
  }
});

test('names, labels, statuses', () => {
  assert.equal(f.fullName({ first_name: 'Thandi', last_name: 'Naidoo' }), 'Thandi Naidoo');
  assert.equal(f.fullName({ email: 'x@y.z' }), 'x@y.z');
  assert.equal(f.fullName(null), '—');
  assert.equal(f.initials({ first_name: 'thandi', last_name: 'naidoo' }), 'TN');
  assert.equal(f.titleCase('missing_clock_out'), 'Missing Clock Out');
  assert.equal(f.statusLabel('missing_clock_out'), 'Missing clock-out');
  assert.equal(f.statusLabel('confirmed'), 'Confirmed');
  // DESIGN.md status mapping
  for (const s of ['confirmed', 'clocked_in', 'active']) assert.equal(f.STATUS_COLOR[s], 'green');
  for (const s of ['pending', 'under_review', 'late', 'missing_clock_out', 'pending_approval']) assert.equal(f.STATUS_COLOR[s], 'orange');
  for (const s of ['rejected', 'expired', 'revoked', 'inactive']) assert.equal(f.STATUS_COLOR[s], 'red');
});

test('numbers and distances', () => {
  assert.equal(f.fmtDistance(42.1), '42 m');
  assert.equal(f.fmtDistance(1500), '1.50 km');
  assert.equal(f.fmtDistance(null), '—');
  assert.equal(f.fmtHoursDecimal(90), '1.50');
});

test('uuid v4 shape', () => {
  const u = f.uuid();
  assert.match(u, /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
  assert.notEqual(u, f.uuid());
});
