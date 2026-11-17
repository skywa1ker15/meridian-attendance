import { test } from 'node:test';
import assert from 'node:assert/strict';
import { installDomStub } from '../tools/dom-stub.mjs';
installDomStub();
const r = await import('../../../backend/app/static/admin/js/router.js');

r.addRoute('/overview', 'overview');
r.addRoute('/employees', 'employees');
r.addRoute('/employees/:id', 'profile');
r.addRoute('/reset-password', 'reset');

test('parseHash splits path and query', () => {
  assert.deepEqual(r.parseHash('#/employees/abc?tab=devices&x=1'), { path: '/employees/abc', query: { tab: 'devices', x: '1' } });
  assert.deepEqual(r.parseHash('#/reset-password?token=a%2Bb'), { path: '/reset-password', query: { token: 'a+b' } });
  assert.deepEqual(r.parseHash(''), { path: '/', query: {} });
  assert.deepEqual(r.parseHash('#overview/'), { path: '/overview', query: {} });
});

test('match resolves params and rejects unknown paths', () => {
  const m = r.match('/employees/3f2a-uuid');
  assert.equal(m.route.screen, 'profile');
  assert.deepEqual(m.params, { id: '3f2a-uuid' });
  assert.equal(r.match('/employees').route.screen, 'employees');
  assert.equal(r.match('/employees/a/b'), null);
  assert.equal(r.match('/nope'), null);
  assert.deepEqual(r.match('/employees/a%20b').params, { id: 'a b' });
});

test('navigate builds a hash without empty query values', () => {
  r.navigate('/records', { from: '2026-11-01', to: '', employee_id: null, page: 2 });
  assert.equal(globalThis.location.hash, '/records?from=2026-11-01&page=2');
});

test('replaceQuery keeps the current path', () => {
  globalThis.location.hash = '#/audit';
  r.replaceQuery({ search: 'employee.create', page: '' });
  assert.equal(globalThis.location.hash, '#/audit?search=employee.create');
});

test('start dispatches the current route', () => {
  globalThis.location.hash = '#/employees/xyz?tab=devices';
  let got = null;
  r.start((cur) => { got = cur; });
  assert.equal(got.path, '/employees/xyz');
  assert.equal(got.params.id, 'xyz');
  assert.equal(got.query.tab, 'devices');
  assert.equal(r.currentRoute().path, '/employees/xyz');
});
