import { test, expect } from '@playwright/test';
import { adminSession, openAs, requireAdminCreds, uniq, api, expectHeading } from './helpers.js';

test('admin creates an employee and sees the one-time password', async ({ page }) => {
  requireAdminCreds(test);
  const sess = await adminSession();
  const num = uniq('E2E');
  await openAs(page, sess, '/employees');
  await page.getByTestId('emp-create').click();
  await page.getByTestId('new-employee-number').fill(num);
  await page.getByTestId('new-first-name').fill('Playwright');
  await page.getByTestId('new-last-name').fill('Created');
  await page.getByTestId('new-email').fill(`${num.toLowerCase()}@e2e-test.example.co.za`);
  await page.getByTestId('new-submit').click();

  const modal = page.getByTestId('temp-password-modal');
  await expect(modal).toBeVisible();
  const pw = (await modal.locator('.copy-box code').textContent()).trim();
  expect(pw.length).toBeGreaterThanOrEqual(10);
  await modal.getByRole('button', { name: 'Open profile' }).click();
  await expectHeading(page, 'Playwright Created');

  const found = await api(sess, 'GET', `/admin/employees?search=${encodeURIComponent(num)}`);
  expect(found.items.map((u) => u.employee_number)).toContain(num);

  await page.goto(page.url().replace(/#.*/, '#/employees'));
  await page.getByTestId('emp-search').fill(num);
  await expect(page.locator('table.table tbody')).toContainText(num);
});
