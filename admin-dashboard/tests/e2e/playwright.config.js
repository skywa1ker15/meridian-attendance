// Playwright configuration for the admin dashboard end-to-end tests.
// Runs against a live backend; see README.md.
import { defineConfig, devices } from '@playwright/test';

process.env.PLAYWRIGHT_BROWSERS_PATH ||= '/opt/pw-browsers';
const BASE_URL = process.env.BASE_URL || 'http://127.0.0.1:8000/admin/';

export default defineConfig({
  testDir: '.',
  testMatch: /.*\.spec\.js$/,
  // Login is rate limited (10/min/IP): run serially and reuse one admin session per worker.
  workers: 1,
  fullyParallel: false,
  timeout: 45_000,
  expect: { timeout: 10_000 },
  retries: 0,
  reporter: [['list'], ['html', { open: 'never', outputFolder: '../playwright-report' }]],
  outputDir: '../test-results',
  use: {
    baseURL: BASE_URL,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    acceptDownloads: true,
    timezoneId: 'Africa/Johannesburg',
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] }, testIgnore: /responsive\.spec\.js$/ },
    { name: 'mobile-360', use: { ...devices['Desktop Chrome'], viewport: { width: 360, height: 740 } }, testMatch: /responsive\.spec\.js$/ },
  ],
});
