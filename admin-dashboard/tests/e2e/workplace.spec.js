import { test, expect } from '@playwright/test';
import { adminSession, openAs, requireAdminCreds, api } from './helpers.js';

test('admin edits a workplace radius and the change persists', async ({ page }) => {
  requireAdminCreds(test);
  const sess = await adminSession();
  let { items } = await api(sess, 'GET', '/admin/workplaces');
  if (!items.length) {
    await api(sess, 'POST', '/admin/workplaces', { name: 'E2E workplace', latitude: -29.72, longitude: 31.07, radius_m: 150 });
    ({ items } = await api(sess, 'GET', '/admin/workplaces'));
  }
  const wp = items[0];
  const newRadius = wp.radius_m === 175 ? 180 : 175;
  await openAs(page, sess, `/workplaces?workplace_id=${wp.id}`);
  if (!wp.coordinates_confirmed) await expect(page.getByTestId('coords-warning')).toBeVisible();
  await page.getByTestId('wp-radius').fill(String(newRadius));
  await page.getByTestId('wp-save').click();
  await expect(page.locator('.toast.success')).toContainText('saved');
  const after = (await api(sess, 'GET', '/admin/workplaces')).items.find((w) => w.id === wp.id);
  expect(after.radius_m).toBe(newRadius);
  await api(sess, 'PATCH', `/admin/workplaces/${wp.id}`, { radius_m: wp.radius_m }); // restore
});

test('policy tab shows the management-review note', async ({ page }) => {
  requireAdminCreds(test);
  const sess = await adminSession();
  await openAs(page, sess, '/workplaces?tab=policy');
  await expect(page.getByText('Defaults for management review')).toBeVisible();
  await expect(page.locator('[data-policy="pending_expiry_hours"]')).toBeVisible();
});
