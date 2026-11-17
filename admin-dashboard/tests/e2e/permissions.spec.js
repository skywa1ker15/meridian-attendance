import { test, expect } from '@playwright/test';
import { adminSession, openAs, requireAdminCreds, createUserWithPassword, sessionFor, apiCall, uniq, expectHeading } from './helpers.js';

test('a manager cannot create employees (UI hides it, server returns 403)', async ({ page }) => {
  requireAdminCreds(test);
  const admin = await adminSession();
  const mgr = await createUserWithPassword(admin, { role: 'manager' });
  const sess = await sessionFor(mgr.identifier, mgr.password);
  expect(sess.user.permissions).not.toContain('employees:write');

  const num = uniq('DENY');
  const res = await apiCall(sess, 'POST', '/admin/employees', { employee_number: num, first_name: 'No', last_name: 'Access', email: `${num.toLowerCase()}@e2e-test.example.co.za` });
  expect(res.status()).toBe(403);
  expect((await res.json()).error.code).toBe('forbidden');

  await openAs(page, sess, '/employees');
  await expectHeading(page, 'Employees');
  await expect(page.getByTestId('emp-create')).toHaveCount(0);
  await expect(page.locator('nav.rail')).not.toContainText('Broadcast notification');
  await page.goto(page.url().replace(/#.*/, '#/notifications-broadcast'));
  await expect(page.getByText('You do not have permission to view this page.')).toBeVisible();
});
