import { test, expect } from '@playwright/test';
import { readFile } from 'node:fs/promises';
import { adminSession, openAs, requireAdminCreds } from './helpers.js';

test('preview a report and download it as CSV with the bearer token', async ({ page }) => {
  requireAdminCreds(test);
  const sess = await adminSession();
  await openAs(page, sess, '/reports?report=daily');
  await page.getByTestId('report-preview').click();
  await expect(page.getByTestId('report-result')).toBeVisible();

  const [download] = await Promise.all([page.waitForEvent('download'), page.getByTestId('report-download-csv').click()]);
  expect(download.suggestedFilename()).toMatch(/\.csv$/i);
  const text = await readFile(await download.path(), 'utf8');
  expect(text.split(/\r?\n/)[0]).toContain(',');
});
