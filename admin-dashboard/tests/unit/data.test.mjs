import { test } from 'node:test';
import assert from 'node:assert/strict';
import { installDomStub } from '../tools/dom-stub.mjs';
installDomStub();
const d = await import('../../../backend/app/static/admin/js/data.js');

test('cleanQuery drops empty values but keeps 0', () => {
  assert.deepEqual(d.cleanQuery({ a: '', b: null, c: undefined, d: false, e: 0, f: 'x', g: 'false' }), { e: 0, f: 'x', g: 'false' });
});

test('parsePage', () => {
  assert.equal(d.parsePage('3'), 3);
  assert.equal(d.parsePage('0'), 1);
  assert.equal(d.parsePage('abc'), 1);
  assert.equal(d.parsePage(undefined), 1);
});

test('countdown formats remaining time', () => {
  const now = Date.parse('2026-11-16T06:00:00Z');
  assert.deepEqual(d.countdown('2026-11-16T05:59:00Z', now).expired, true);
  assert.equal(d.countdown('2026-11-16T06:05:09Z', now).label, '5m 09s');
  assert.equal(d.countdown('2026-11-16T09:30:00Z', now).label, '3h 30m');
  assert.equal(d.countdown('2026-11-18T08:00:00Z', now).label, '2d 2h');
  assert.equal(d.countdown(null, now).label, '—');
  assert.equal(d.countdown('garbage', now).label, '—');
});

test('normaliseBssid', () => {
  assert.equal(d.normaliseBssid('AA-BB-CC-DD-EE-FF'), 'aa:bb:cc:dd:ee:ff');
  assert.equal(d.normaliseBssid(' aa:bb:cc:dd:ee:0f '), 'aa:bb:cc:dd:ee:0f');
  assert.equal(d.normaliseBssid('aa:bb:cc:dd:ee'), null);
  assert.equal(d.normaliseBssid('zz:bb:cc:dd:ee:ff'), null);
});

test('countByStatus', () => {
  assert.deepEqual(d.countByStatus([{ status: 'clocked_in' }, { status: 'clocked_in' }, { status: 'not_started' }, {}]), { clocked_in: 2, not_started: 1, unknown: 1 });
});

test('assignableRoles follows the API.md permission matrix', () => {
  const manager = ['attendance:self', 'admin:read', 'attendance:review', 'reports:export'];
  const admin = [...manager, 'employees:write', 'devices:write', 'config:write', 'roles:write'];
  assert.deepEqual(d.assignableRoles(manager), []);
  assert.deepEqual(d.assignableRoles(admin), ['employee', 'manager', 'admin']);
  assert.deepEqual(d.assignableRoles([...admin, 'roles:superadmin']), ['employee', 'manager', 'admin', 'superadmin']);
});

test('rangeFromQuery and validRange', () => {
  assert.deepEqual(d.rangeFromQuery({ from: '2026-11-01', to: '2026-11-07' }), { from: '2026-11-01', to: '2026-11-07' });
  const def = d.rangeFromQuery({ from: 'bad' }, 'last_7');
  assert.ok(d.validRange(def.from, def.to));
  assert.equal(d.validRange('2026-11-08', '2026-11-07'), false);
  assert.equal(d.validRange('', '2026-11-07'), false);
});

test('rowsToCsv escapes values', () => {
  const csv = d.rowsToCsv([{ key: 'a', label: 'Name' }, { key: 'b', label: 'Note' }], [{ a: 'Naidoo, T', b: 'said "hi"' }, { a: 'X', b: null }]);
  assert.equal(csv, 'Name,Note\n"Naidoo, T","said ""hi"""\nX,');
});
