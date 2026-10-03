import { test, expect, type Browser, type BrowserContext, type Page } from '@playwright/test'
import { apiLogin, baseURL, bearer, cancelAllMine, cancelRideApi, ids, loginViaUi, pickAddress, stubExternal, stubPlace } from '../helpers'

/**
 * M3 gates: place search UI (kind icons, "Visa fler", ✕, learned selections, stale-response safety),
 * GPS default pickup with the nearest-stop hint, and graceful behaviour when geolocation is denied.
 * Every external dependency (SL / Nominatim / OSRM / tiles) is stubbed; only our own backend is real.
 */

const GEO = { latitude: 59.3293, longitude: 18.0686 }

async function newCtx(browser: Browser, project: { use: Record<string, unknown> }, tag: string, geo: boolean) {
  const ctx = await browser.newContext({
    ...(project.use as object),
    ...(geo ? { geolocation: GEO, permissions: ['geolocation'] } : {})
  })
  await stubExternal(ctx, tag)
  return ctx
}

async function openBooking(ctx: BrowserContext): Promise<Page> {
  const page = await ctx.newPage()
  await loginViaUi(page, ids.passenger)
  await page.goto('/app')
  return page
}

const destination = (page: Page) => page.getByRole('combobox', { name: 'Destination', exact: true })

test.describe('M3 place search', () => {
  test('shows results with kind icons, "Visa fler" only when hasMore, and ✕ clears', async ({ browser }, testInfo) => {
    const tag = `${Date.now().toString(36)}icons`
    const ctx = await newCtx(browser, testInfo.project, tag, false)
    const limits: string[] = []
    await ctx.route('**/api/places/search**', (route) => {
      const u = new URL(route.request().url())
      const limit = u.searchParams.get('limit') ?? ''
      limits.push(limit)
      const stop = stubPlace('Donken Kallhäll', 59.45, 17.8, { kind: 'STOP', area: 'Järfälla' })
      const poi = stubPlace('Donken Kista', 59.4, 17.93, { kind: 'POI', area: 'Kista' })
      const more = stubPlace('Donken Solna', 59.36, 18.0, { kind: 'POI', area: 'Solna' })
      return route.fulfill({
        json: limit === '25' ? { results: [stop, poi, more], hasMore: false, context: 'DEFAULT' } : { results: [stop, poi], hasMore: true, context: 'DEFAULT' }
      })
    })
    try {
      const page = await openBooking(ctx)
      const input = destination(page)
      await input.click()
      await input.fill('donk')
      const options = page.getByRole('option')
      await expect(options).toHaveCount(2)
      await expect(options.nth(0)).toContainText('🚏')
      await expect(options.nth(0)).toContainText('Donken Kallhäll')
      await expect(options.nth(0)).toContainText('Järfälla')
      await expect(options.nth(1)).toContainText('📍')

      const more = page.getByRole('button', { name: 'Visa fler' })
      await expect(more).toBeVisible()
      await more.click()
      await expect(options).toHaveCount(3)
      await expect(more).toHaveCount(0)
      expect(limits).toContain('25')

      await page.getByRole('button', { name: 'Rensa destination' }).click()
      await expect(input).toHaveValue('')
      await expect(page.getByRole('listbox')).toHaveCount(0)
    } finally {
      await ctx.close()
    }
  })

  test('no "Visa fler" when hasMore is false; empty result shows "Inga träffar"', async ({ browser }, testInfo) => {
    const tag = `${Date.now().toString(36)}empty`
    const ctx = await newCtx(browser, testInfo.project, tag, false)
    await ctx.route('**/api/places/search**', (route) => route.fulfill({ json: { results: [], hasMore: false, context: 'DEFAULT' } }))
    try {
      const page = await openBooking(ctx)
      await destination(page).fill('zzzz')
      await expect(page.getByText('Inga träffar.')).toBeVisible()
      await expect(page.getByRole('button', { name: 'Visa fler' })).toHaveCount(0)
    } finally {
      await ctx.close()
    }
  })

  test('selecting a result records the selection via POST /api/places/selections', async ({ browser }, testInfo) => {
    const tag = `${Date.now().toString(36)}sel`
    const ctx = await newCtx(browser, testInfo.project, tag, false)
    try {
      const page = await openBooking(ctx)
      const posted = page.waitForRequest((r) => r.url().includes('/api/places/selections') && r.method() === 'POST')
      await pickAddress(page, 'Destination', 'slut', tag)
      const body = (await posted).postDataJSON()
      expect(body).toMatchObject({ query: 'slut', provider: 'SL', name: `E2E Slut ${tag}`, lat: 59.34, lon: 18.09 })
      expect(body.providerPlaceId).toContain('e2e-')
    } finally {
      await ctx.close()
    }
  })

  test('a slow stale response never overwrites a newer one', async ({ browser }, testInfo) => {
    const tag = `${Date.now().toString(36)}stale`
    const ctx = await newCtx(browser, testInfo.project, tag, false)
    const seen: string[] = []
    await ctx.route('**/api/places/search**', async (route) => {
      const q = new URL(route.request().url()).searchParams.get('q') ?? ''
      seen.push(q)
      if (q === 'do') {
        await new Promise((r) => setTimeout(r, 2_500))
        // The page aborted this request meanwhile; fulfilling may throw, which is fine.
        await route.fulfill({ json: { results: [stubPlace('STALE-RESULT', 59.3, 18.0)], hasMore: false, context: 'DEFAULT' } }).catch(() => {})
        return
      }
      await route.fulfill({ json: { results: [stubPlace('FRESH-RESULT', 59.31, 18.01)], hasMore: false, context: 'DEFAULT' } })
    })
    try {
      const page = await openBooking(ctx)
      const input = destination(page)
      await input.click()
      await input.fill('do')
      await expect.poll(() => seen.includes('do')).toBe(true)
      await input.pressSequentially('nk')
      await expect(page.getByRole('option', { name: /FRESH-RESULT/ })).toBeVisible()
      await page.waitForTimeout(3_000) // let the slow first response "arrive"
      await expect(page.getByRole('option', { name: /FRESH-RESULT/ })).toBeVisible()
      await expect(page.getByText('STALE-RESULT')).toHaveCount(0)
      expect(seen).toContain('donk')
    } finally {
      await ctx.close()
    }
  })

  test('geolocation granted: "Min position" default, "Här (… m)" hint, booking sends a real pickup label', async ({ browser, request }, testInfo) => {
    const tag = `${Date.now().toString(36)}${testInfo.project.name.replace(/\W/g, '')}geo`
    const pToken = await apiLogin(request, ids.passenger)
    await cancelAllMine(request, pToken)
    const ctx = await newCtx(browser, testInfo.project, tag, true)
    await ctx.route('**/api/places/nearest-stop**', (route) =>
      route.fulfill({ json: { name: 'Kallhälls station', area: 'Järfälla', lat: 59.3295, lon: 18.0688, distanceM: 120, providerPlaceId: 'e2e-stop' } })
    )
    const searchUrls: string[] = []
    ctx.on('request', (r) => {
      if (r.url().includes('/api/places/search')) searchUrls.push(r.url())
    })
    let rideId: number | null = null
    try {
      const page = await openBooking(ctx)
      await expect(page.getByRole('combobox', { name: 'Startadress', exact: true })).toHaveValue('📍 Min position')
      await expect(page.getByText(/📍 Här \(Kallhälls station, 120 m\)/)).toBeVisible()

      await pickAddress(page, 'Destination', 'slut', tag)
      // GPS (with accuracy) is sent as the search context.
      expect(searchUrls.at(-1)).toMatch(/lat=59\.3293.*lon=18\.0686/)

      // Re-open the page state: pickup is still "Min position" when we book (typing in the destination does not touch it).
      await expect(page.getByRole('combobox', { name: 'Startadress', exact: true })).toHaveValue('📍 Min position')
      const posted = page.waitForRequest((r) => r.url().endsWith('/api/rides') && r.method() === 'POST')
      await page.getByRole('button', { name: /^Åk(a)? nu$/ }).click()
      const sheet = page.getByRole('dialog', { name: 'Stämmer det här?' })
      await expect(sheet).toBeVisible()
      await expect(sheet).not.toContainText('Min position')
      await sheet.getByRole('button', { name: 'Ja, boka nu' }).click()
      const body = (await posted).postDataJSON()
      expect(body.fromAddress).not.toMatch(/Min position/)
      expect(body.fromAddress).toContain(`E2E Start ${tag}`) // resolved through the stubbed /reverse
      await expect(page).toHaveURL(/\/app\/resa\/(\d+)/)
      rideId = Number(/\/resa\/(\d+)/.exec(page.url())?.[1])
    } finally {
      await ctx.close()
      if (rideId) await cancelRideApi(request, pToken, rideId)
      await cancelAllMine(request, pToken)
    }
  })

  test('geolocation granted but reverse finds nothing: pickup label falls back to "Nära <hållplats>"', async ({ browser, request }, testInfo) => {
    const tag = `${Date.now().toString(36)}${testInfo.project.name.replace(/\W/g, '')}near`
    const pToken = await apiLogin(request, ids.passenger)
    await cancelAllMine(request, pToken)
    const ctx = await newCtx(browser, testInfo.project, tag, true)
    await ctx.route('**/api/places/nearest-stop**', (route) =>
      route.fulfill({ json: { name: 'Kallhälls station', area: 'Järfälla', lat: 59.3295, lon: 18.0688, distanceM: 120, providerPlaceId: 'e2e-stop' } })
    )
    await ctx.route('**/api/places/reverse**', (route) => route.fulfill({ status: 204 }))
    try {
      const page = await openBooking(ctx)
      await expect(page.getByText(/📍 Här \(Kallhälls station, 120 m\)/)).toBeVisible()
      await pickAddress(page, 'Destination', 'slut', tag)
      const posted = page.waitForRequest((r) => r.url().endsWith('/api/rides') && r.method() === 'POST')
      await page.getByRole('button', { name: /^Åk(a)? nu$/ }).click()
      await page.getByRole('dialog', { name: 'Stämmer det här?' }).getByRole('button', { name: 'Ja, boka nu' }).click()
      const body = (await posted).postDataJSON()
      expect(body.fromAddress).toBe('Nära Kallhälls station')
      await expect(page).toHaveURL(/\/app\/resa\/\d+/)
    } finally {
      await ctx.close()
      await cancelAllMine(request, pToken)
    }
  })

  test('geolocation denied: no default pickup, search still works without GPS context', async ({ browser }, testInfo) => {
    const tag = `${Date.now().toString(36)}${testInfo.project.name.replace(/\W/g, '')}deny`
    const ctx = await newCtx(browser, testInfo.project, tag, false)
    const searchUrls: string[] = []
    ctx.on('request', (r) => {
      if (r.url().includes('/api/places/search')) searchUrls.push(r.url())
    })
    try {
      const page = await openBooking(ctx)
      await expect(page.getByRole('combobox', { name: 'Startadress', exact: true })).toHaveValue('')
      await expect(page.getByText(/📍 Här/)).toHaveCount(0)
      await pickAddress(page, 'Startadress', 'start', tag)
      await pickAddress(page, 'Destination', 'slut', tag)
      expect(searchUrls.length).toBeGreaterThan(0)
      for (const u of searchUrls) expect(new URL(u).searchParams.has('lat')).toBe(false)
    } finally {
      await ctx.close()
    }
  })
})
