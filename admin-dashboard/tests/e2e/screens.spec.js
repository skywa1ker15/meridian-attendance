import { test, expect } from '@playwright/test';
import { adminSession, openAs, requireAdminCreds, trackPageErrors, expectHeading } from './helpers.js';

const SCREENS = [
  ['/overview', 'Overview'], ['/employees', 'Employees'], ['/live', 'Live attendance'], ['/records', 'Attendance records'],
  ['/pending', 'Pending confirmations'], ['/exceptions', 'Exceptions'], ['/reports', 'Reports & analytics'],
  ['/workplaces', 'Workplace configuration'], ['/devices', 'Devices'], ['/roles', 'Roles & permissions'], ['/audit', 'Audit logs'],
  ['/notifications-broadcast', 'Broadcast notification'],
];

test.describe('every screen renders for an admin', () => {
  test.beforeEach(() => requireAdminCreds(test));
  for (const [route, title] of SCREENS) {
    test(`${route}`, async ({ page }) => {
      const errors = trackPageErrors(page);
      const sess = await adminSession();
      await openAs(page, sess, route);
      await expectHeading(page, title);
      await page.waitForLoadState('networkidle');
      await expect(page.locator('.state.error')).toHaveCount(0);
      await expect(page.getByText('This page failed to load')).toHaveCount(0);
      expect(errors, errors.join('\n')).toEqual([]);
    });
  }

  test('overview shows every stat tile from the API', async ({ page }) => {
    const sess = await adminSession();
    await openAs(page, sess, '/overview');
    for (const k of ['total_employees', 'clocked_in', 'clocked_out', 'absent', 'late_arrivals', 'pending_confirmations', 'under_review', 'exceptions', 'pending_requests', 'pending_devices']) {
      await expect(page.locator(`[data-stat="${k}"]`)).toBeVisible();
    }
  });

  test('unknown route shows not found', async ({ page }) => {
    const sess = await adminSession();
    await openAs(page, sess, '/does-not-exist');
    await expect(page.getByText('Page not found')).toBeVisible();
  });
});
