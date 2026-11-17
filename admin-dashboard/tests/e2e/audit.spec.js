import { test, expect } from '@playwright/test';
import { adminSession, openAs, requireAdminCreds, api, uniq } from './helpers.js';

test('an admin action appears in the audit log with a diff', async ({ page }) => {
  requireAdminCreds(test);
  const sess = await adminSession();
  const num = uniq('AUD');
  const created = await api(sess, 'POST', '/admin/employees', { employee_number: num, first_name: 'Audit', last_name: 'Trail', email: `${num.toLowerCase()}@e2e-test.example.co.za` });
  await openAs(page, sess, `/audit?entity_type=user&entity_id=${created.user.id}`);
  const rows = page.getByTestId('audit-table').locator('tbody tr.clickable');
  await expect(rows.first()).toBeVisible();
  await expect(page.getByTestId('audit-table')).toContainText('employee.create');
  await rows.first().click();
  await expect(page.locator('.drawer .json-diff, .drawer .audit-detail').first()).toBeVisible();
});
