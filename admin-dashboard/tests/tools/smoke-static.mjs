// Static smoke test (no backend): serves backend/app/static on a local port and
// checks every route boots without uncaught JS errors. API calls fail (404), so
// screens must show their error/empty states gracefully. This does NOT test any
// server behaviour; use the e2e suite against a live backend for that.
import http from 'node:http';
import { readFile } from 'node:fs/promises';
import { join, extname, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from '@playwright/test';

if (!process.env.PLAYWRIGHT_BROWSERS_PATH && (await import('node:fs')).existsSync('/opt/pw-browsers')) process.env.PLAYWRIGHT_BROWSERS_PATH = '/opt/pw-browsers';
const STATIC = join(dirname(fileURLToPath(import.meta.url)), '../../../backend/app/static');
const TYPES = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css' };
const server = http.createServer(async (req, res) => {
  const p = new URL(req.url, 'http://x').pathname;
  if (p.startsWith('/api/')) { res.writeHead(503, { 'Content-Type': 'application/json' }); res.end('{"error":{"code":"server_error","message":"Backend not running (static smoke test).","details":{}}}'); return; }
  try {
    const file = join(STATIC, p.endsWith('/') ? p + 'index.html' : p);
    const body = await readFile(file);
    res.writeHead(200, { 'Content-Type': TYPES[extname(file)] || 'application/octet-stream' }); res.end(body);
  } catch { res.writeHead(404); res.end('not found'); }
});
await new Promise((r) => server.listen(0, '127.0.0.1', r));
const base = `http://127.0.0.1:${server.address().port}/admin/`;

const ADMIN_PERMS = ['attendance:self', 'admin:read', 'attendance:review', 'reports:export', 'employees:write', 'devices:write', 'config:write', 'roles:write', 'roles:superadmin', 'notifications:broadcast'];
const fakeUser = { id: '00000000-0000-4000-8000-000000000001', first_name: 'Smoke', last_name: 'Test', email: 'smoke@invalid', role: 'superadmin', permissions: ADMIN_PERMS, must_change_password: false };
const ROUTES = ['/overview', '/employees', '/employees/00000000-0000-4000-8000-000000000002', '/live', '/records', '/pending', '/pending?view=requests', '/exceptions', '/reports', '/workplaces', '/workplaces?tab=policy', '/workplaces?tab=departments', '/devices', '/roles', '/audit', '/notifications-broadcast', '/change-password'];

const browser = await chromium.launch();
let failures = 0;
async function check(name, fn, viewport) {
  const page = await browser.newPage(viewport ? { viewport } : {});
  const errors = [];
  page.on('pageerror', (e) => errors.push(e.message));
  try { await fn(page); if (errors.length) throw new Error(errors.join('; ')); console.log(`ok    ${name}`); }
  catch (e) { failures += 1; console.log(`FAIL  ${name}: ${e.message.split('\n')[0]}`); }
  await page.close();
}
const signedIn = (page) => page.addInitScript((u) => { sessionStorage.setItem('mg.access', 'x'); sessionStorage.setItem('mg.refresh', 'y'); sessionStorage.setItem('mg.user', u); }, JSON.stringify(fakeUser));

await check('login renders', async (p) => { await p.goto(base + '#/login'); await p.getByRole('heading', { name: 'Sign in' }).waitFor(); });
await check('login shows server error', async (p) => { await p.goto(base + '#/login'); await p.getByTestId('login-identifier').fill('a'); await p.getByTestId('login-password').fill('b'); await p.getByTestId('login-submit').click(); await p.locator('.form-error:not(.hidden)').waitFor(); });
await check('reset-password without token', async (p) => { await p.goto(base + '#/reset-password'); await p.getByRole('heading', { name: 'Invalid reset link' }).waitFor(); });
await check('reset-password with token', async (p) => { await p.goto(base + '#/reset-password?token=abc'); await p.getByRole('heading', { name: 'Choose a new password' }).waitFor(); });
await check('unauthenticated deep link -> login', async (p) => { await p.goto(base + '#/audit'); await p.waitForURL(/#\/login/); if (await p.evaluate(() => sessionStorage.getItem('mg.redirect')) !== '/audit') throw new Error('return route not stored'); });
await check('employee role rejected client-side', async (p) => {
  await p.addInitScript((u) => { sessionStorage.setItem('mg.access', 'x'); sessionStorage.setItem('mg.user', u); }, JSON.stringify({ ...fakeUser, role: 'employee', permissions: ['attendance:self'] }));
  await p.goto(base + '#/overview'); await p.getByText('No dashboard access').waitFor();
});
for (const r of ROUTES) {
  await check(`screen ${r}`, async (p) => {
    await signedIn(p); await p.goto(base + '#' + r);
    await p.locator('h1, h2, .state.error').first().waitFor();
    await p.waitForTimeout(600);
    if (await p.getByText('This page failed to load').count()) throw new Error('screen crashed');
  });
}
await check('mobile 360px drawer', async (p) => {
  await signedIn(p); await p.goto(base + '#/overview');
  await p.getByRole('button', { name: 'Open navigation' }).click();
  await p.locator('nav.rail').getByRole('link', { name: 'Employees' }).click();
  await p.waitForURL(/#\/employees/);
  const overflow = await p.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
  if (overflow > 1) throw new Error(`horizontal overflow ${overflow}px`);
}, { width: 360, height: 740 });

await browser.close(); server.close();
console.log(`\n${failures ? 'FAILED' : 'passed'}: ${failures} failure(s)`);
process.exit(failures ? 1 : 0);
