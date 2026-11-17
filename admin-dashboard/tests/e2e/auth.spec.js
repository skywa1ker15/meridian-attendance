import { test, expect } from '@playwright/test';
import { ADMIN, BASE_URL, requireAdminCreds, expectHeading } from './helpers.js';

test.describe('authentication', () => {
  test('login page renders and rejects bad credentials', async ({ page }) => {
    await page.goto(`${BASE_URL}#/login`);
    await expect(page.getByRole('heading', { name: 'Sign in' })).toBeVisible();
    await page.getByTestId('login-identifier').fill('nobody@e2e-test.example.co.za');
    await page.getByTestId('login-password').fill('definitely-wrong-password');
    await page.getByTestId('login-submit').click();
    await expect(page.locator('.form-error:not(.hidden)')).toBeVisible();
    await expect(page).toHaveURL(/#\/login/);
  });

  test('forgot password always reports success (anti-enumeration)', async ({ page }) => {
    await page.goto(`${BASE_URL}#/login`);
    await page.getByTestId('forgot-link').click();
    await page.getByTestId('forgot-identifier').fill('nobody@e2e-test.example.co.za');
    await page.getByRole('button', { name: 'Send reset link' }).click();
    await expect(page.getByTestId('forgot-done')).toBeVisible();
  });

  test('reset-password without token explains the problem', async ({ page }) => {
    await page.goto(`${BASE_URL}#/reset-password`);
    await expect(page.getByRole('heading', { name: 'Invalid reset link' })).toBeVisible();
  });

  test('admin signs in through the form, deep link is restored, then signs out', async ({ page }) => {
    requireAdminCreds(test);
    await page.goto(`${BASE_URL}#/audit`);
    await expect(page).toHaveURL(/#\/login/);
    await page.getByTestId('login-identifier').fill(ADMIN.identifier);
    await page.getByTestId('login-password').fill(ADMIN.password);
    await page.getByTestId('login-submit').click();
    await expectHeading(page, 'Audit logs');
    await page.goto(`${BASE_URL}#/overview`);
    await expect(page.getByTestId('overview-tiles')).toBeVisible();
    await page.getByTestId('logout').click();
    await expect(page.getByRole('heading', { name: 'Sign in' })).toBeVisible();
    expect(await page.evaluate(() => sessionStorage.getItem('mg.access'))).toBeNull();
  });
});
