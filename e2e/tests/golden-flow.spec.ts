import { test, expect, type Browser, type BrowserContext, type Page } from '@playwright/test'
import { readFileSync } from 'node:fs'
import type { Identities } from '../global-setup'

const ids: Identities = JSON.parse(readFileSync(new URL('../.e2e-users.json', import.meta.url), 'utf8'))

/** Canned responses so the test never depends on Nominatim / OSRM / OSM tiles. */
async function stubExternal(ctx: BrowserContext, tag: string) {
  const places: Record<string, { lat: string; lon: string; name: string }> = {
    start: { lat: '59.3293', lon: '18.0686', name: `E2E Start ${tag}` },
    slut: { lat: '59.3400', lon: '18.0900', name: `E2E Slut ${tag}` }
  }
  const item = (p: { lat: string; lon: string; name: string }) => ({
    display_name: `${p.name}, Stockholm, Sverige`,
    name: p.name,
    lat: p.lat,
    lon: p.lon,
    address: { municipality: 'Stockholms kommun' }
  })
  await ctx.route('**/api/public/geocode/search**', (route) => {
    const q = (new URL(route.request().url()).searchParams.get('q') ?? '').toLowerCase()
    const p = q.includes('slut') ? places.slut : places.start
    return route.fulfill({ json: [item(p)] })
  })
  await ctx.route('**/api/public/geocode/reverse**', (route) => route.fulfill({ json: item(places.start) }))
  await ctx.route('**/api/public/route/**', (route) =>
    route.fulfill({
      json: {
        code: 'Ok',
        routes: [
          {
            distance: 2500,
            duration: 420,
            geometry: { type: 'LineString', coordinates: [[18.0686, 59.3293], [18.09, 59.34]] }
          }
        ]
      }
    })
  )
  // OSM tiles and any other third-party host.
  await ctx.route(/^https?:\/\/[^/]*(tile\.openstreetmap|openstreetmap\.org|osm\.org|unpkg\.com)[^/]*\//, (route) => route.abort())
}

async function newUserContext(browser: Browser, project: { use: Record<string, unknown> }, tag: string) {
  const ctx = await browser.newContext({
    ...(project.use as object),
    geolocation: { latitude: 59.3293, longitude: 18.0686 },
    permissions: ['geolocation']
  })
  await stubExternal(ctx, tag)
  return ctx
}

async function loginViaUi(page: Page, u: { email: string; password: string }) {
  await page.goto('/login')
  await page.getByLabel('E-post').fill(u.email)
  await page.getByLabel('Lösenord').fill(u.password)
  await page.getByRole('button', { name: 'Fortsätt' }).click()
  await expect(page).toHaveURL(/\/app/)
  // The PWA-install modal appears once after login and blocks the page; dismiss it.
  const later = page.getByRole('button', { name: 'Inte nu' })
  await later.waitFor({ state: 'visible', timeout: 5_000 }).then(() => later.click(), () => {})
}

async function pickAddress(page: Page, ariaLabel: string, query: string, tag: string) {
  const input = page.getByLabel(ariaLabel, { exact: true })
  await input.click()
  await input.fill(query)
  const hit = page.locator('.sheet-search button', { hasText: tag })
  await hit.first().click()
  await expect(input).toHaveValue(new RegExp(tag))
}

test('golden flow: passenger books, driver accepts, starts and completes, passenger sees history', async ({ browser }, testInfo) => {
  const tag = `${Date.now().toString(36)}${testInfo.project.name.replace(/\W/g, '')}`
  const passengerCtx = await newUserContext(browser, testInfo.project, tag)
  const driverCtx = await newUserContext(browser, testInfo.project, tag)
  const passenger = await passengerCtx.newPage()
  const driver = await driverCtx.newPage()

  try {
    // Passenger logs in and books "Åka nu" through the UI.
    await loginViaUi(passenger, ids.passenger)
    await passenger.goto('/app')
    await pickAddress(passenger, 'Startadress', 'start', tag)
    await pickAddress(passenger, 'Destination', 'slut', tag)
    await passenger.getByRole('button', { name: 'Åka nu' }).click()
    await expect(passenger).toHaveURL(/\/app\/bekraftelse/)
    await expect(passenger.getByText(`E2E Start ${tag}`).first()).toBeVisible()

    // Driver logs in (second context) and sees the open ride.
    await loginViaUi(driver, ids.driver)
    await driver.goto('/app/forare')
    const openRide = driver
      .locator('article.ride-item')
      .filter({ hasText: `E2E Start ${tag}` })
      .filter({ has: driver.getByRole('button', { name: 'Acceptera' }) })
    await expect(openRide).toBeVisible()
    await openRide.getByRole('button', { name: 'Acceptera' }).click()

    const myRide = driver.locator('article.ride-item').filter({ hasText: `E2E Start ${tag}` })
    await myRide.getByRole('button', { name: 'Starta körning' }).click()
    await expect(myRide.getByRole('button', { name: 'Klar' })).toBeVisible()
    await myRide.getByRole('button', { name: 'Klar' }).click()
    await expect(driver.getByText('Resan är klar.')).toBeVisible()

    // Passenger sees the completed ride in their rides list.
    await passenger.goto('/app/resor')
    const row = passenger.locator('article.ride-item').filter({ hasText: `E2E Start ${tag}` })
    await expect(row).toContainText('COMPLETED')
  } finally {
    await passengerCtx.close()
    await driverCtx.close()
  }
})
