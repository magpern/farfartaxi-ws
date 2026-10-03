import { test, expect, type APIRequestContext, type BrowserContext, type Page } from '@playwright/test'
import { apiLogin, bearer, baseURL, bookRideApi, cancelAllMine, cancelRideApi, driverStep, ids, loginViaUi, newUserContext, stubExternal } from '../helpers'

/**
 * M7 gate (live tracking), two browser contexts (test passenger + test driver) with emulated geolocation.
 *  - the passenger's car marker follows every new driver position within ~6 s (5 s poll + 1 s tween)
 *  - reloading the driver page resumes tracking
 *  - ETA target switches pickup -> destination after "Hämtat upp"
 *  - closing the driver context -> the stale warning appears after 2 min (tagged @slow, ~2.5 min)
 *  - "Dela resan" link works logged out ("{passenger} åker med {driver}" + map) and gives "Länken har gått ut" after "Sluta dela"
 *  - driving mode shows the wake-lock indicator or the unsupported hint
 * The heavy specs run on pixel-7 only (annotated); share + wake lock run on both mobile projects.
 */

// Scripted route, every step >= 60 m apart (driver sends on >= 50 m moves): approach (P0-P2 = pickup), then drive to the destination.
const ROUTE = [
  { latitude: 59.325, longitude: 18.06 }, // P0 ~490 m from pickup
  { latitude: 59.327, longitude: 18.0645 }, // P1
  { latitude: 59.3293, longitude: 18.0686 }, // P2 = pickup
  { latitude: 59.332, longitude: 18.0735 }, // P3
  { latitude: 59.336, longitude: 18.081 }, // P4
  { latitude: 59.34, longitude: 18.09 } // P5 = destination
]
const TRACK_WITHIN_MS = 9_000 // ~6 s nominal (5 s poll + 1 s tween) plus request/CI jitter; the measured latency is logged

const firstName = (full: string) => full.trim().split(/\s+/)[0]
async function myFirstName(request: APIRequestContext, u: { email: string; password: string }) {
  const token = await apiLogin(request, u)
  const me = await (await request.get(`${baseURL}/api/auth/me`, { headers: bearer(token) })).json()
  return firstName(String(me.fullName ?? me.name ?? me.user?.fullName))
}

const car = (page: Page) => page.getByTestId('live-car')
async function carPos(page: Page) {
  const el = car(page)
  if ((await el.count()) === 0) return null
  return { lat: Number(await el.getAttribute('data-lat')), lon: Number(await el.getAttribute('data-lon')) }
}
/** Waits until the car marker sits at `p`; returns how long it took. */
async function expectCarAt(page: Page, p: { latitude: number; longitude: number }, label: string) {
  const t0 = Date.now()
  await expect
    .poll(
      async () => {
        const c = await carPos(page)
        return c ? Math.max(Math.abs(c.lat - p.latitude), Math.abs(c.lon - p.longitude)) : 1
      },
      { timeout: TRACK_WITHIN_MS, message: `${label}: car marker did not reach ${p.latitude},${p.longitude}` }
    )
    .toBeLessThan(2e-5)
  const ms = Date.now() - t0
  console.log(`[m7] ${label}: marker moved in ${ms} ms`)
  return ms
}

async function blockMaps(ctx: BrowserContext) {
  await ctx.route(/^https:\/\/(www\.google\.com\/maps|maps\.apple\.com)\//, (route) => route.abort())
}

async function driverTakesRide(page: Page, tag: string) {
  await page.goto('/app/forare')
  const card = page.locator('article.ride-item').filter({ hasText: `E2E Start ${tag}` })
  await expect(card).toBeVisible()
  await card.getByRole('button', { name: 'Ta resan' }).click()
  const proximity = page.getByRole('button', { name: 'Ta ändå' })
  await proximity.waitFor({ state: 'visible', timeout: 3_000 }).then(() => proximity.click(), () => {})
  await page.getByRole('button', { name: /Öppna körläge|Fortsätt köra/ }).first().click()
  await expect(page).toHaveURL(/\/app\/forare\/kor\/\d+/)
}

let tokenP = ''
let rideId: number | null = null

test.beforeEach(async ({ request }) => {
  tokenP = await apiLogin(request, ids.passenger)
  await cleanup(request)
})
/** Cancel what can be cancelled; a ride stuck in PICKED_UP (cannot be cancelled) is completed by the driver. */
async function cleanup(request: APIRequestContext) {
  if (rideId != null) await cancelRideApi(request, tokenP, rideId)
  await cancelAllMine(request, tokenP)
  const tokenD = await apiLogin(request, ids.driver)
  const act = await request.get(`${baseURL}/api/rides/active`, { headers: bearer(tokenD) })
  if (act.status() === 200) {
    const a = await act.json()
    if (a.ride?.status === 'PICKED_UP') await request.post(`${baseURL}/api/driver/rides/${a.ride.id}/complete`, { headers: bearer(tokenD) })
  }
  rideId = null
}
test.afterEach(async ({ request }) => cleanup(request))

test.describe('M7 live tracking', () => {
  test('GATE: car follows the driver, reload resumes, ETA target switches, then Klar', async ({ browser, request }, testInfo) => {
    test.skip(testInfo.project.name !== 'pixel-7', 'heavy two-context spec: pixel-7 only')
    test.setTimeout(180_000)
    const tag = `${Date.now().toString(36)}m7a`
    const ride = await bookRideApi(request, tokenP, tag)
    rideId = ride.id

    const pctx = await newUserContext(browser, testInfo.project, tag)
    const dctx = await newUserContext(browser, testInfo.project, tag)
    await blockMaps(dctx)
    const passenger = await pctx.newPage()
    const driver = await dctx.newPage()
    try {
      await loginViaUi(passenger, ids.passenger)
      await loginViaUi(driver, ids.driver)
      await dctx.setGeolocation(ROUTE[0])
      await driverTakesRide(driver, tag)

      await passenger.goto('/app')
      await expect(passenger).toHaveURL(new RegExp(`/app/resa/${ride.id}$`))
      await expect(passenger.getByTestId('live-map')).toBeVisible()

      // Kör nu: tracking starts, the first fix (P0) is sent at once.
      await driver.getByRole('button', { name: 'Kör nu' }).click()
      await expect(driver.getByRole('button', { name: 'Jag är framme' })).toBeVisible()
      await expectCarAt(passenger, ROUTE[0], 'P0 (first fix)')

      // ETA target before pickup is the pickup, with pickup wording.
      const line = passenger.getByTestId('live-status-line')
      await expect(line).toHaveText(/är \d+ min bort|är på väg/)
      await expect(line).not.toContainText('Framme om ca')

      // P1, then reload the driver page mid-ride: tracking must resume and deliver P2.
      await dctx.setGeolocation(ROUTE[1])
      await expectCarAt(passenger, ROUTE[1], 'P1')
      await driver.reload()
      await expect(driver.getByRole('button', { name: 'Jag är framme' })).toBeVisible()
      // Headless Chromium delivers one cached fix when a watch starts and only fires again on a position change, so
      // give the restarted watch a moment to register before emulating the next position.
      await driver.waitForTimeout(2_000)
      await dctx.setGeolocation(ROUTE[2])
      await expectCarAt(passenger, ROUTE[2], 'P2 after driver reload')

      const tokenD = await apiLogin(request, ids.driver)
      await expect
        .poll(async () => (await (await request.get(`${baseURL}/api/rides/${ride.id}`, { headers: bearer(tokenD) })).json()).etaTarget, { timeout: 15_000 })
        .toBe('PICKUP')

      await driver.getByRole('button', { name: 'Jag är framme' }).click()
      await expect(passenger.getByTestId('live-status-line')).toContainText('är framme', { timeout: 10_000 })
      await driver.getByRole('button', { name: 'Hämtat upp' }).click()
      await expect(driver.getByRole('button', { name: 'Klar', exact: true })).toBeVisible()

      // After pickup the status line switches to destination wording and the server ETA target follows.
      await expect(line).toHaveText(/Framme om ca \d+ min|Ni är på väg/, { timeout: 12_000 })
      await expect(line).not.toContainText('min bort')

      // Drive to the destination: every new position moves the car. The server recomputes the ETA on the next
      // position after the status change, now towards the destination.
      for (const [i, p] of ROUTE.slice(3).entries()) {
        await dctx.setGeolocation(p)
        await expectCarAt(passenger, p, `P${i + 3}`)
        if (i === 0) {
          await expect
            .poll(async () => (await (await request.get(`${baseURL}/api/rides/${ride.id}`, { headers: bearer(tokenD) })).json()).etaTarget, { timeout: 15_000 })
            .toBe('DESTINATION')
          await expect(line).toHaveText(/Framme om ca \d+ min/, { timeout: 12_000 })
        }
      }

      await driver.getByRole('button', { name: 'Klar', exact: true }).click()
      await expect(driver.getByText('Klart! Bra jobbat')).toBeVisible()
      await driver.getByRole('button', { name: 'Klart – tillbaka' }).click()
      const r = await request.get(`${baseURL}/api/rides/${ride.id}`, { headers: bearer(tokenD) })
      expect((await r.json()).status).toBe('COMPLETED')
    } finally {
      await pctx.close()
      await dctx.close().catch(() => {})
    }
  })

  test('GATE @slow: closing the driver context -> stale warning on the passenger after 2 min', async ({ browser, request }, testInfo) => {
    test.skip(testInfo.project.name !== 'pixel-7', 'long two-context spec: pixel-7 only')
    test.setTimeout(300_000)
    const tag = `${Date.now().toString(36)}m7s`
    const ride = await bookRideApi(request, tokenP, tag)
    rideId = ride.id

    const pctx = await newUserContext(browser, testInfo.project, tag)
    const dctx = await newUserContext(browser, testInfo.project, tag)
    const passenger = await pctx.newPage()
    const driver = await dctx.newPage()
    try {
      await loginViaUi(passenger, ids.passenger)
      await loginViaUi(driver, ids.driver)
      await dctx.setGeolocation(ROUTE[0])
      await driverTakesRide(driver, tag)
      await passenger.goto('/app')
      await driver.getByRole('button', { name: 'Kör nu' }).click()
      await expect(driver.getByRole('button', { name: 'Jag är framme' })).toBeVisible()
      await expectCarAt(passenger, ROUTE[0], 'stale: first fix')
      await expect(passenger.getByTestId('live-stale')).toHaveCount(0)

      await dctx.close() // driver "turns the screen off": no more fixes
      const t0 = Date.now()
      await expect(passenger.getByTestId('live-stale')).toBeVisible({ timeout: 150_000 })
      const secs = Math.round((Date.now() - t0) / 1000)
      console.log(`[m7] stale warning appeared ${secs} s after the driver context closed`)
      expect(secs).toBeGreaterThanOrEqual(60) // not before the 2-min rule (last fix was already a few s old when closing)
      await expect(passenger.getByTestId('live-stale')).toContainText(/har inte uppdaterats på \d+ min/)
    } finally {
      await pctx.close()
      await dctx.close().catch(() => {})
    }
  })

  test('GATE: share link works logged out, then "Sluta dela" -> Länken har gått ut', async ({ browser, request }, testInfo) => {
    const tag = `${Date.now().toString(36)}${testInfo.project.name.replace(/\W/g, '')}m7h`
    const ride = await bookRideApi(request, tokenP, tag)
    rideId = ride.id
    const tokenD = await apiLogin(request, ids.driver)
    await driverStep(request, tokenD, ride.id, 'accept', { confirmProximity: true })
    await driverStep(request, tokenD, ride.id, 'start')
    await driverStep(request, tokenD, ride.id, 'location', { lat: ROUTE[1].latitude, lon: ROUTE[1].longitude, accuracy: 12 })
    const passengerName = await myFirstName(request, ids.passenger)
    const driverName = await myFirstName(request, ids.driver)

    const pctx = await newUserContext(browser, testInfo.project, tag)
    // Capture what the native share sheet would receive.
    await pctx.addInitScript(() => {
      ;(navigator as unknown as { share: (d: { url: string }) => Promise<void> }).share = async (d) => {
        ;(window as unknown as { __sharedUrl: string }).__sharedUrl = d.url
      }
    })
    const passenger = await pctx.newPage()
    let anon: BrowserContext | null = null
    try {
      await loginViaUi(passenger, ids.passenger)
      await passenger.goto('/app')
      await expect(passenger).toHaveURL(new RegExp(`/app/resa/${ride.id}$`))
      await passenger.getByRole('button', { name: 'Dela resan' }).click()
      await expect(passenger.getByRole('button', { name: 'Sluta dela' })).toBeVisible()
      const url = await passenger.evaluate(() => (window as unknown as { __sharedUrl?: string }).__sharedUrl)
      expect(url, 'navigator.share received the link').toMatch(/\/dela\/[A-Za-z0-9_-]{20,}$/)
      const path = new URL(url!).pathname // public-base-url is the production host; open the same path on the local stack

      // A brand new context: no cookies, no storage, no login.
      anon = await browser.newContext({ ...(testInfo.project.use as object), baseURL })
      await stubExternal(anon, tag)
      const pub = await anon.newPage()
      await pub.goto(path)
      await expect(pub.getByRole('heading', { name: `${passengerName} åker med ${driverName}` })).toBeVisible()
      await expect(pub.getByTestId('live-map')).toBeVisible()
      await expect(pub.getByTestId('live-car')).toHaveAttribute('data-lat', ROUTE[1].latitude.toFixed(6))
      await expect(pub.getByText(/E2E Slut/)).toBeVisible()
      expect(await pub.locator('body').innerText()).not.toMatch(/\+46|tel:/)

      await passenger.getByRole('button', { name: 'Sluta dela' }).click()
      await expect(passenger.getByRole('button', { name: 'Sluta dela' })).toHaveCount(0)
      await pub.reload()
      await expect(pub.getByRole('heading', { name: 'Länken har gått ut' })).toBeVisible()
      const api410 = await request.get(`${baseURL}/api/public/share/${path.split('/').pop()}`)
      expect(api410.status()).toBe(410)
    } finally {
      await anon?.close()
      await pctx.close()
    }
  })

  test('driving mode shows the wake-lock indicator or the unsupported hint', async ({ browser, request }, testInfo) => {
    const tag = `${Date.now().toString(36)}${testInfo.project.name.replace(/\W/g, '')}m7w`
    const ride = await bookRideApi(request, tokenP, tag)
    rideId = ride.id
    const tokenD = await apiLogin(request, ids.driver)
    await driverStep(request, tokenD, ride.id, 'accept', { confirmProximity: true })
    await driverStep(request, tokenD, ride.id, 'start')
    const dctx = await newUserContext(browser, testInfo.project, tag)
    const driver = await dctx.newPage()
    try {
      await loginViaUi(driver, ids.driver)
      await driver.goto(`/app/forare/kor/${ride.id}`)
      await expect(driver.getByRole('button', { name: 'Jag är framme' })).toBeVisible()
      const supported = await driver.evaluate(() => 'wakeLock' in navigator)
      const indicator = driver.getByTestId('wake-lock')
      await expect(indicator).toHaveText(/Skärmen hålls tänd|Håll skärmen tänd medan du kör/)
      console.log(`[m7] wakeLock API supported=${supported}; indicator="${(await indicator.innerText()).trim()}"`)
    } finally {
      await dctx.close()
    }
  })
})
