import { test, expect } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { adminSession, openAs, requireAdminCreds, createUserWithPassword, apiLogin, api } from './helpers.js';

test('manager-facing approval of a pending detection', async ({ page }) => {
  requireAdminCreds(test);
  const admin = await adminSession();
  const emp = await createUserWithPassword(admin, { role: 'employee' });
  const device = { device_id: randomUUID(), model: 'E2E-Phone', manufacturer: 'e2e', os_version: '14', app_version: '1.0.0 (e2e)' };
  const empSess = await apiLogin(emp.identifier, emp.password, device);

  let { items: workplaces } = await api(empSess, 'GET', '/config/workplaces');
  if (!workplaces.length) {
    await api(admin, 'POST', '/admin/workplaces', { name: 'E2E workplace', latitude: -29.72, longitude: 31.07, radius_m: 150 });
    ({ items: workplaces } = await api(empSess, 'GET', '/config/workplaces'));
  }
  const wp = workplaces[0];
  const submitted = await api(empSess, 'POST', '/attendance/events', {
    client_event_id: randomUUID(), workplace_id: wp.id, event_type: 'arrival', source: 'geofence',
    detected_at: new Date().toISOString().replace(/\.\d{3}Z$/, 'Z'), device_timezone: 'Africa/Johannesburg', geofence_transition: 'dwell',
    location: { latitude: wp.latitude, longitude: wp.longitude, accuracy_m: 15 }, wifi: null, offline_queued: false, battery_level: 80, note: 'e2e',
  });
  const ev = submitted.event;
  expect(['pending', 'under_review']).toContain(ev.status);

  await openAs(page, admin, `/pending?view=${ev.status}`);
  const card = page.locator(`[data-event-id="${ev.id}"]`);
  await expect(card).toBeVisible();
  await expect(card.locator('.verif-list')).toBeVisible();
  await card.getByTestId('approve-event').click();
  await page.getByRole('dialog').getByRole('textbox').fill('Approved by e2e test');
  await page.getByRole('dialog').getByRole('button', { name: 'Approve' }).click();
  await expect(page.locator('.toast.success')).toContainText('approved');
  await expect(card).toHaveCount(0);

  const after = await api(admin, 'GET', `/admin/attendance/events?employee_id=${emp.user.id}`);
  expect(after.items.find((e) => e.id === ev.id).status).toBe('confirmed');
});
