import { defineConfig, devices } from '@playwright/test'

const baseURL = process.env.BASE_URL ?? 'http://127.0.0.1:8099'

// Both device profiles run on chromium (viewport + UA + touch of the real devices) so only one browser is installed.
const chromiumDevice = (d: (typeof devices)[string]) => {
  const { defaultBrowserType: _ignored, ...rest } = d
  return { ...rest, browserName: 'chromium' as const }
}

export default defineConfig({
  testDir: './tests',
  globalSetup: './global-setup.ts',
  timeout: 90_000,
  expect: { timeout: 15_000 },
  fullyParallel: false,
  workers: 1,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : 'list',
  use: {
    baseURL,
    locale: 'sv-SE',
    timezoneId: 'Europe/Stockholm',
    // Optional local override when the matching browser download is unavailable.
    launchOptions: process.env.PW_CHROMIUM_PATH ? { executablePath: process.env.PW_CHROMIUM_PATH } : {},
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure'
  },
  projects: [
    { name: 'pixel-7', use: chromiumDevice(devices['Pixel 7']) },
    { name: 'iphone-13', use: chromiumDevice(devices['iPhone 13']) }
  ]
})
