import { test, expect } from '@playwright/test';
import { adminSession, openAs, requireAdminCreds } from './helpers.js';

test('navigation collapses to a drawer at 360px', async ({ page }) => {
  requireAdminCreds(test);
  const sess = await adminSession();
  await openAs(page, sess, '/overview');
  const menu = page.getByRole('button', { name: 'Open navigation' });
  await expect(menu).toBeVisible();
  await expect(page.locator('nav.rail')).not.toBeInViewport();
  await menu.click();
  await expect(page.locator('nav.rail')).toBeInViewport();
  await page.locator('nav.rail').getByRole('link', { name: 'Employees' }).click();
  await expect(page).toHaveURL(/#\/employees/);
  await expect(page.locator('nav.rail')).not.toBeInViewport();
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
  expect(overflow).toBeLessThanOrEqual(1);
});
