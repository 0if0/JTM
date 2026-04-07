import { defineConfig } from '@playwright/test';

const baseURL = process.env.JENKINS_BASE_URL ?? 'http://127.0.0.1:8080';

export default defineConfig({
  testDir: './tests',
  timeout: 120_000,
  expect: { timeout: 30_000 },
  fullyParallel: false,
  workers: 1,
  use: {
    baseURL,
    trace: 'on-first-retry',
    ignoreHTTPSErrors: true,
    // Avoid stale run-detail HTML after POST (CI / reverse proxies sometimes cache GET).
    launchOptions: {
      args: ['--disable-http-cache'],
    },
  },
});
