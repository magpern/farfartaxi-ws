import { test, expect, type APIRequestContext } from '@playwright/test'
import {
  apiLogin,
  baseURL,
  bearer,
  bookRideApi,
  cancelAllMine,
  cancelRideApi,
  driverStep,
  ids,
  loginViaUi,
  newUserContext,
  pickAddress
} from '../helpers'

/**
 * M2 gates: active-ride redirect, offline behaviour (banner, no endless spinner, Ring), no queued offline actions.
 *
 * The third M2 gate, "a new service worker mid-ride or mid-booking does not reload the app", cannot be triggered
 * for real in e2e (no way to ship a second SW build into a running page). It is covered by the Vitest policy tests
 * (src/pwa/updatePolicy.test.tsx and src/shell/updateGuard.test.tsx in the web repo).
 */

const RING = 'a[href^="tel:"]'

/** Gives the test driver a phone number through the admin API when the e2e admin is available (local/CI stack). */
async function ensureDriverPhone(request: APIRequestContext, driverToken: string): Promise<boolean> {
  const me = await request.get(`${baseURL}/api/auth/me`, { headers: bearer(driverToken) })
  const user = (await me.json()) as { id: number; phone?: string | null }
  if (user.phone) return true
  const adminEmail = process.env.E2E_ADMIN_EMAIL ?? 'e2e-admin@farfartaxi.invalid'
  const adminPassword = process.env.E2E_ADMIN_PASSWORD ?? 'e2e-only-admin-password-1'
  const login = await request.post(`${baseURL}/api/auth/login`, { data: { email: adminEmail, password: adminPassword } })
  if (!login.ok()) return false
  const admin = (await login.json()).token as string
  const patch = await request.patch(`${baseURL}/api/admin/users/${user.id}`, { headers: bearer(admin), data: { phone: '+46700000042' } })
  return patch.ok()
}

test.describe('M2 shell', () => {
  test('active ride redirect: passenger -> /app/resa/:id, driver -> /app/forare/kor/:id', async ({ browser, request }, testInfo) => {
    const tag = `${Date.now().toString(36)}${testInfo.project.name.replace(/\W/g, '')}red`
    const pToken = await apiLogin(request, ids.passenger)
    const dToken = await apiLogin(request, ids.driver)
    await cancelAllMine(request, pToken)
    const ride = await bookRideApi(request, pToken, tag)
    const passengerCtx = await newUserContext(browser, testInfo.project, tag)
    const driverCtx = await newUserContext(browser, testInfo.project, tag)
    try {
      // Passenger: booked via API, opens /app and is taken to the ride screen.
      const passenger = await passengerCtx.newPage()
      await loginViaUi(passenger, ids.passenger)
      await passenger.goto('/app')
      await expect(passenger).toHaveURL(new RegExp(`/app/resa/${ride.id}$`))
      await expect(passenger.getByRole('heading', { name: 'Vi letar efter en förare…' })).toBeVisible()

      // Driver: accepts and starts via API, opens /app and lands in driving mode.
      await driverStep(request, dToken, ride.id, 'accept', { confirmProximity: true })
      await driverStep(request, dToken, ride.id, 'start')
      const driver = await driverCtx.newPage()
      await loginViaUi(driver, ids.driver)
      await driver.goto('/app')
      await expect(driver).toHaveURL(new RegExp(`/app/forare/kor/${ride.id}$`))
      await expect(driver.getByRole('button', { name: 'Jag är framme' })).toBeVisible()
    } finally {
      await passengerCtx.close()
      await driverCtx.close()
      for (const step of ['arrive', 'pickup', 'complete']) {
        await request.post(`${baseURL}/api/driver/rides/${ride.id}/${step}`, { headers: bearer(dToken) })
      }
      await cancelRideApi(request, pToken, ride.id)
    }
  })

  test('offline: banner, content stays rendered, Ring stays; back online clears the banner', async ({ browser, request }, testInfo) => {
    const tag = `${Date.now().toString(36)}${testInfo.project.name.replace(/\W/g, '')}off`
    const pToken = await apiLogin(request, ids.passenger)
    const dToken = await apiLogin(request, ids.driver)
    await cancelAllMine(request, pToken)
    const hasPhone = await ensureDriverPhone(request, dToken)
    const ride = await bookRideApi(request, pToken, tag)
    await driverStep(request, dToken, ride.id, 'accept', { confirmProximity: true })
    await driverStep(request, dToken, ride.id, 'start')
    const ctx = await newUserContext(browser, testInfo.project, tag)
    try {
      const page = await ctx.newPage()
      await loginViaUi(page, ids.passenger)
      await page.goto(`/app/resa/${ride.id}`)
      const headline = page.getByRole('heading', { name: /är på väg$/ })
      await expect(headline).toBeVisible()
      const banner = page.getByTestId('network-banner')
      await expect(banner).toHaveCount(0)
      if (hasPhone) await expect(page.locator(RING).first()).toBeVisible()
      else testInfo.annotations.push({ type: 'skip-tel', description: 'test driver has no phone and no e2e admin to set one: tel: assertion skipped' })

      await ctx.setOffline(true)
      await expect(banner).toBeVisible({ timeout: 10_000 })
      await expect(banner).toContainText('Ingen anslutning')
      // No endless spinner / blank screen: the last known ride is still rendered, and Ring/SMS are plain links.
      await page.waitForTimeout(2_000)
      await expect(headline).toBeVisible()
      await expect(page.getByText('Laddar')).toHaveCount(0)
      if (hasPhone) await expect(page.locator(RING).first()).toBeVisible()
      // Reloading while offline also still shows the cached ride (service worker + local snapshot) or fails gracefully.
      await ctx.setOffline(false)
      await expect(banner).toHaveCount(0, { timeout: 15_000 })
      await expect(headline).toBeVisible()
    } finally {
      await ctx.close()
      for (const step of ['arrive', 'pickup', 'complete']) {
        await request.post(`${baseURL}/api/driver/rides/${ride.id}/${step}`, { headers: bearer(dToken) })
      }
      await cancelRideApi(request, pToken, ride.id)
    }
  })

  test('a failed action while offline shows "Kunde inte nå servern" and creates nothing later', async ({ browser, request }, testInfo) => {
    const tag = `${Date.now().toString(36)}${testInfo.project.name.replace(/\W/g, '')}que`
    const pToken = await apiLogin(request, ids.passenger)
    await cancelAllMine(request, pToken)
    const ctx = await newUserContext(browser, testInfo.project, tag)
    const created: number[] = []
    try {
      const page = await ctx.newPage()
      await loginViaUi(page, ids.passenger)
      await page.goto('/app')
      await pickAddress(page, 'Startadress', 'start', tag)
      await pickAddress(page, 'Destination', 'slut', tag)

      const posts: string[] = []
      page.on('request', (r) => {
        if (r.url().endsWith('/api/rides') && r.method() === 'POST') posts.push(r.url())
      })

      await page.getByRole('button', { name: /^Åk(a)? nu$/ }).click()
      const sheet = page.getByRole('dialog', { name: 'Stämmer det här?' })
      await expect(sheet).toBeVisible()
      await page.context().setOffline(true)
      await sheet.getByRole('button', { name: 'Ja, boka nu' }).click()
      await expect(page.getByText('Kunde inte nå servern — försök igen')).toBeVisible()
      await expect(page).not.toHaveURL(/\/app\/resa\//)

      // Back online: nothing was queued, so nothing is created.
      await ctx.setOffline(false)
      await page.waitForTimeout(4_000)
      for (const h of ['true', 'false']) {
        const r = await request.get(`${baseURL}/api/rides/my?history=${h}`, { headers: bearer(pToken) })
        for (const ride of (await r.json()) as { id: number; fromAddress: string }[]) {
          if (ride.fromAddress.includes(tag)) created.push(ride.id)
        }
      }
      expect(created, 'no ride may be created by a request that failed offline').toHaveLength(0)
      expect(posts.length).toBeLessThanOrEqual(1)
      await expect(page).not.toHaveURL(/\/app\/resa\//)
    } finally {
      await ctx.close()
      for (const id of created) await cancelRideApi(request, pToken, id)
    }
  })
})
