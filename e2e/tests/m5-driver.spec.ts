import { test, expect, type BrowserContext, type Page } from '@playwright/test'
import { apiLogin, bearer, baseURL, bookRideApi, cancelAllMine, cancelRideApi, ids, loginViaUi, newUserContext } from '../helpers'

/**
 * M5 gate: the driver completes a full ride in driving mode using ONLY the big step button
 * (Kör nu -> Jag är framme -> Hämtat upp -> Klar) and the Navigera / Ring buttons.
 * Request card: mini map + Ta resan / Kan inte. External maps are never reached (popups are aborted).
 * The test accounts have no phone numbers, so the ride responses are patched in the browser to carry one.
 * SHOTS_DIR (optional) saves screenshots there.
 */

const PICKUP = '59.329300,18.068600'
const DEST = '59.340000,18.090000'
const PHONE = '070-123 45 67'

/** Adds a passenger phone to every ride object the driver's browser receives. */
async function injectPhone(ctx: BrowserContext) {
  const patch = (o: unknown): unknown => {
    if (Array.isArray(o)) return o.map(patch)
    if (o && typeof o === 'object') {
      const r = o as Record<string, unknown>
      if ('fromLat' in r && 'status' in r) return { ...r, passengerPhone: '070-123 45 67' }
      return Object.fromEntries(Object.entries(r).map(([k, v]) => [k, patch(v)]))
    }
    return o
  }
  await ctx.route(/\/api\/(driver\/)?rides\//, async (route) => {
    if (route.request().method() !== 'GET') return route.fallback()
    const res = await route.fetch()
    const ct = res.headers()['content-type'] ?? ''
    if (!res.ok() || !ct.includes('json')) return route.fulfill({ response: res })
    return route.fulfill({ response: res, json: patch(await res.json()) })
  })
  await ctx.route(/^https:\/\/(www\.google\.com\/maps|maps\.apple\.com)\//, (route) => route.abort())
}

const shot = async (page: Page, name: string) => {
  if (process.env.SHOTS_DIR) await page.screenshot({ path: `${process.env.SHOTS_DIR}/${name}.png` })
}

test.describe('M5 driver workflow', () => {
  let tokenP = ''
  let rideId: number | null = null

  test.beforeEach(async ({ request }) => {
    tokenP = await apiLogin(request, ids.passenger)
    await cancelAllMine(request, tokenP)
  })
  test.afterEach(async ({ request }) => {
    if (rideId != null) await cancelRideApi(request, tokenP, rideId)
    await cancelAllMine(request, tokenP)
    rideId = null
  })

  test('GATE: full ride with only the big button + Navigera/Ring', async ({ browser, request }, testInfo) => {
    const tag = `${Date.now().toString(36)}${testInfo.project.name.replace(/\W/g, '')}m5`
    const ios = testInfo.project.name === 'iphone-13'
    const ride = await bookRideApi(request, tokenP, tag)
    rideId = ride.id

    const ctx = await newUserContext(browser, testInfo.project, tag)
    await injectPhone(ctx)
    const page = await ctx.newPage()
    const openMaps = async (link: ReturnType<Page['getByRole']>) => {
      const popup = ctx.waitForEvent('page')
      await link.click()
      const p = await popup
      await p.close()
    }
    try {
      await loginViaUi(page, ids.driver)
      await expect(page).toHaveURL(/\/app\/forare$/)

      // Request card: mini map + the two big answers.
      const card = page.locator('article.ride-item').filter({ hasText: `E2E Start ${tag}` })
      await expect(card).toBeVisible()
      await expect(card.getByTestId('mini-map')).toBeVisible()
      await expect(card.getByRole('button', { name: 'Kan inte' })).toBeVisible()
      await shot(page, `driver-home-${testInfo.project.name}`)
      await card.getByRole('button', { name: 'Ta resan' }).click()
      const proximity = page.getByRole('button', { name: 'Ta ändå' })
      await proximity.waitFor({ state: 'visible', timeout: 3_000 }).then(() => proximity.click(), () => {})
      await page.getByRole('button', { name: /Öppna körläge|Fortsätt köra/ }).first().click()
      await expect(page).toHaveURL(/\/app\/forare\/kor\/\d+/)

      // Step 1: Kör nu.
      await page.getByRole('button', { name: 'Kör nu' }).click()
      await expect(page.getByRole('button', { name: 'Jag är framme' })).toBeVisible()

      // Navigate to the passenger = pickup coordinates.
      let nav = page.getByRole('link', { name: /^Navigera till / })
      if (ios) {
        // First use on iOS: ask once, remember.
        const first = page.getByRole('button', { name: /^Navigera till / })
        await expect(page.getByRole('link', { name: /^Navigera till / })).toHaveCount(0)
        await first.click()
        const sheet = page.getByRole('dialog', { name: 'Vilken karta vill du använda?' })
        await expect(sheet.getByRole('link', { name: 'Apple Kartor' })).toBeVisible()
        await expect(sheet.getByRole('link', { name: 'Google Maps' })).toBeVisible()
        await expect(sheet.getByRole('link', { name: 'Apple Kartor' })).toHaveAttribute('href', `https://maps.apple.com/?daddr=${PICKUP}&dirflg=d`)
        await openMaps(sheet.getByRole('link', { name: 'Apple Kartor' }))
        await expect(sheet).toBeHidden()
        nav = page.getByRole('link', { name: /^Navigera till / })
        await expect(nav).toHaveAttribute('href', `https://maps.apple.com/?daddr=${PICKUP}&dirflg=d`)
      } else {
        await expect(nav).toHaveAttribute('href', `https://www.google.com/maps/dir/?api=1&destination=${PICKUP}&travelmode=driving`)
        await openMaps(nav)
      }
      await expect(nav).toHaveAttribute('target', '_blank')

      // Ring is a plain tel: link.
      await expect(page.getByRole('link', { name: /^Ring / })).toHaveAttribute('href', 'tel:0701234567')
      await expect(page.getByRole('link', { name: /^Skicka SMS till / })).toHaveAttribute('href', /^sms:0701234567[?&]body=Jag%20%C3%A4r%20h%C3%A4r$/)
      await shot(page, `driving-en-route-${testInfo.project.name}`)

      // Step 2: Jag är framme (navigate still targets the pickup).
      await page.getByRole('button', { name: 'Jag är framme' }).click()
      await expect(page.getByRole('button', { name: 'Hämtat upp' })).toBeVisible()
      await expect(page.getByRole('link', { name: /^Navigera till / })).toHaveAttribute('href', new RegExp(`${PICKUP.replace('.', '\\.')}`))

      // Step 3: Hämtat upp -> navigate switches to the destination, no new question on iOS.
      await page.getByRole('button', { name: 'Hämtat upp' }).click()
      await expect(page.getByRole('button', { name: 'Klar', exact: true })).toBeVisible()
      nav = page.getByRole('link', { name: `Navigera till E2E Slut ${tag}` })
      await expect(nav).toBeVisible()
      await expect(nav).toHaveAttribute(
        'href',
        ios ? `https://maps.apple.com/?daddr=${DEST}&dirflg=d` : `https://www.google.com/maps/dir/?api=1&destination=${DEST}&travelmode=driving`
      )
      await expect(page.getByRole('dialog')).toHaveCount(0)
      await shot(page, `driving-picked-up-${testInfo.project.name}`)
      await openMaps(nav)

      // Step 4: Klar.
      await page.getByRole('button', { name: 'Klar', exact: true }).click()
      await expect(page.getByText('Klart! Bra jobbat')).toBeVisible()
      await page.getByRole('button', { name: 'Klart – tillbaka' }).click()
      await expect(page).toHaveURL(/\/app\/forare$/)

      // The ride really is completed.
      const tokenD = await apiLogin(request, ids.driver)
      const r = await request.get(`${baseURL}/api/rides/${ride.id}`, { headers: bearer(tokenD) })
      expect((await r.json()).status).toBe('COMPLETED')
    } finally {
      await ctx.close()
    }
  })

  test('iOS: the remembered map is changeable under Mer -> Kartapp', async ({ browser }, testInfo) => {
    test.skip(testInfo.project.name !== 'iphone-13', 'iOS only')
    const ctx = await newUserContext(browser, testInfo.project, 'm5kart')
    const page = await ctx.newPage()
    try {
      await loginViaUi(page, ids.driver)
      await page.goto('/app/mer')
      const group = page.getByRole('group', { name: 'Kartapp' })
      await group.getByRole('button', { name: 'Google Maps' }).click()
      await expect(group.getByRole('button', { name: 'Google Maps' })).toHaveAttribute('aria-pressed', 'true')
      await group.getByRole('button', { name: 'Fråga nästa gång' }).click()
      await expect(group.getByRole('button', { name: 'Fråga nästa gång' })).toHaveAttribute('aria-pressed', 'true')
    } finally {
      await ctx.close()
    }
  })
})
