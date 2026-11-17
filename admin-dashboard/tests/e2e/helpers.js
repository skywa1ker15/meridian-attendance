// Shared helpers: API access for test setup and session injection for the SPA.
import { expect, request as pwRequest } from '@playwright/test';
import { randomUUID } from 'node:crypto';

export const BASE_URL = process.env.BASE_URL || 'http://127.0.0.1:8000/admin/';
export const API = new URL('/api/v1', BASE_URL).toString();
export const ADMIN = { identifier: process.env.ADMIN_IDENTIFIER, password: process.env.ADMIN_PASSWORD };

export function requireAdminCreds(test) {
  test.skip(!ADMIN.identifier || !ADMIN.password, 'Set ADMIN_IDENTIFIER and ADMIN_PASSWORD (an admin or superadmin account).');
}

let ctx = null;
export async function apiContext() {
  if (!ctx) ctx = await pwRequest.newContext();
  return ctx;
}

async function json(res, what) {
  const body = await res.text();
  if (!res.ok()) throw new Error(`${what} failed: ${res.status()} ${body}`);
  return body ? JSON.parse(body) : null;
}

export async function apiLogin(identifier, password, device = undefined) {
  const c = await apiContext();
  return json(await c.post(`${API}/auth/login`, { data: { identifier, password, ...(device ? { device } : {}) } }), `login ${identifier}`);
}

const cache = new Map();
/** Log in once per worker per account (login is rate limited). */
export async function sessionFor(identifier, password) {
  if (!cache.has(identifier)) cache.set(identifier, await apiLogin(identifier, password));
  return cache.get(identifier);
}

export async function adminSession() { return sessionFor(ADMIN.identifier, ADMIN.password); }

export async function apiCall(sess, method, path, data) {
  const c = await apiContext();
  const res = await c.fetch(`${API}${path}`, {
    method, data,
    headers: { Authorization: `Bearer ${sess.access_token}`, 'Idempotency-Key': randomUUID() },
  });
  return res;
}
export async function api(sess, method, path, data) { return json(await apiCall(sess, method, path, data), `${method} ${path}`); }

/** Open the SPA already signed in (tokens go to sessionStorage like a real login). */
export async function openAs(page, sess, route = '/overview') {
  await page.addInitScript(([access, refresh, user]) => {
    if (!sessionStorage.getItem('mg.access')) {
      sessionStorage.setItem('mg.access', access);
      sessionStorage.setItem('mg.refresh', refresh);
      sessionStorage.setItem('mg.user', user);
    }
  }, [sess.access_token, sess.refresh_token, JSON.stringify(sess.user)]);
  await page.goto(`${BASE_URL}#${route}`);
}

export function uniq(prefix = 'E2E') {
  return `${prefix}-${Date.now().toString(36).toUpperCase()}${Math.floor(Math.random() * 1000)}`;
}

/** Create an employee through the API and complete the forced password change. */
export async function createUserWithPassword(adminSess, { role = 'employee' } = {}) {
  const num = uniq('E2E');
  const created = await api(adminSess, 'POST', '/admin/employees', {
    employee_number: num, first_name: 'E2E', last_name: role === 'employee' ? 'Employee' : 'Manager',
    email: `${num.toLowerCase()}@e2e-test.example.co.za`, role,
  });
  const newPassword = `Qx7-${randomUUID()}`;
  const first = await apiLogin(num, created.temporary_password);
  await api(first, 'POST', '/auth/password/change', { current_password: created.temporary_password, new_password: newPassword });
  return { user: created.user, identifier: num, password: newPassword };
}

/** Collect uncaught page errors so screens can be asserted error-free. */
export function trackPageErrors(page) {
  const errors = [];
  page.on('pageerror', (e) => errors.push(e.message));
  return errors;
}

export async function expectHeading(page, text) {
  await expect(page.getByRole('heading', { level: 1, name: text })).toBeVisible();
}
