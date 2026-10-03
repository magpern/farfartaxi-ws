import { test, expect, type APIRequestContext, type Browser, type Page } from '@playwright/test'
import { apiLogin, baseURL, bearer, bookRideApi, cancelAllMine, cancelRideApi, driverStep, ids, loginViaUi, newUserContext } from '../helpers'

/**
 * M4 gates: "Åk hem" books in <= 3 taps from a cold start, favourites, "Spara ditt hem", Mina platser CRUD,
 * driver managing a passenger's places (?userId=) and "Boka igen". External services are stubbed; our backend is real.
 * The test passenger's own saved places are snapshotted before each test, cleared, and restored afterwards.
 */

type Saved = { id: number; label: string; address: string; formattedAddress: string | null; lat: number; lon: number; kind: string; provider: string | null; providerPlaceId: string | null }

const listPlaces = async (request: APIRequestContext, token: string, userId?: number): Promise<Saved[]> => {
  const r = await request.get(`${baseURL}/api/saved-places${userId != null ? `?userId=${userId}` : ''}`, { headers: bearer(token) })
  expect(r.ok(), `list saved places: ${r.status()}`).toBeTruthy()
  return (await r.json()) as Saved[]
}

const createPlace = async (request: APIRequestContext, token: string, label: string, kind: string, tag: string, lat = 59.34, lon = 18.09): Promise<Saved> => {
  const r = await request.post(`${baseURL}/api/saved-places`, {
    headers: bearer(token),
    data: { label, address: `${label} ${tag}`, formattedAddress: `${label} ${tag}, Stockholm`, lat, lon, kind }
  })
  expect(r.ok(), `create saved place: ${r.status()}`).toBeTruthy()
  return (await r.json()) as Saved
}

async function clearPlaces(request: APIRequestContext, token: string) {
  for (const p of await listPlaces(request, token)) await request.delete(`${baseURL}/api/saved-places/${p.id}`, { headers: bearer(token) })
}

const destination = (page: Page) => page.getByRole('combobox', { name: 'Destination', exact: true })
const pickup = (page: Page) => page.getByRole('combobox', { name: 'Startadress', exact: true })

async function openApp(browser: Browser, project: { use: Record<string, unknown> }, tag: string, user = ids.passenger) {
  const ctx = await newUserContext(browser, project, tag)
  const page = await ctx.newPage()
  await loginViaUi(page, user)
  await page.goto('/app')
  return { ctx, page }
}

test.describe('M4 places', () => {
  let tokenP = ''
  let snapshot: Saved[] = []

  test.beforeEach(async ({ request }) => {
    tokenP = await apiLogin(request, ids.passenger)
    await cancelAllMine(request, tokenP)
    snapshot = await listPlaces(request, tokenP)
    await clearPlaces(request, tokenP)
  })

  test.afterEach(async ({ request }) => {
    await cancelAllMine(request, tokenP)
    await clearPlaces(request, tokenP)
    for (const p of snapshot) {
      await request.post(`${baseURL}/api/saved-places`, {
        headers: bearer(tokenP),
        data: { label: p.label, address: p.address, formattedAddress: p.formattedAddress, lat: p.lat, lon: p.lon, kind: p.kind, provider: p.provider, providerPlaceId: p.providerPlaceId }
      })
    }
  })

  test('GATE: "Åk hem" books in 3 taps from a cold start (Åk hem -> Nu -> Ja, boka nu)', async ({ browser, request }, testInfo) => {
    const tag = `${Date.now().toString(36)}${testInfo.project.name.replace(/\W/g, '')}home`
    const home = await createPlace(request, tokenP, 'Hem', 'HOME', tag)
    const ctx = await newUserContext(browser, testInfo.project, tag)
    const page = await ctx.newPage()
    let rideId: number | null = null
    try {
      await loginViaUi(page, ids.passenger)
      await page.goto('/app') // cold start of the home screen; everything below is a user tap
      await expect(page.getByRole('button', { name: /Åk hem/ })).toBeVisible()
      await expect(pickup(page)).toHaveValue('📍 Min position')

      const posted = page.waitForRequest((r) => r.url().endsWith('/api/rides') && r.method() === 'POST')
      const response = page.waitForResponse((r) => r.url().endsWith('/api/rides') && r.request().method() === 'POST')
      let taps = 0
      await page.getByRole('button', { name: /Åk hem/ }).click(); taps++ // 1
      await page.getByRole('dialog', { name: 'När vill du åka?' }).getByRole('button', { name: 'Nu', exact: true }).click(); taps++ // 2
      await page.getByRole('dialog', { name: 'Stämmer det här?' }).getByRole('button', { name: 'Ja, boka nu' }).click(); taps++ // 3
      expect(taps).toBe(3)

      const res = await response
      expect(res.status()).toBe(200)
      const body = (await posted).postDataJSON()
      expect(body.toAddress).toBe(home.formattedAddress)
      expect(body.toLat).toBeCloseTo(home.lat, 5)
      expect(body.toLon).toBeCloseTo(home.lon, 5)
      expect(body.fromAddress).not.toMatch(/Min position/)
      expect(body.fromAddress).toContain(`E2E Start ${tag}`)
      await expect(page).toHaveURL(/\/app\/resa\/\d+/)
      rideId = Number(/\/resa\/(\d+)/.exec(page.url())?.[1])
    } finally {
      await ctx.close()
      if (rideId) await cancelRideApi(request, tokenP, rideId)
    }
  })

  test('favourite chip fills the destination with one tap', async ({ browser, request }, testInfo) => {
    const tag = `${Date.now().toString(36)}${testInfo.project.name.replace(/\W/g, '')}chip`
    const school = await createPlace(request, tokenP, 'Skolan', 'SCHOOL', tag)
    const { ctx, page } = await openApp(browser, testInfo.project, tag)
    try {
      await page.getByRole('group', { name: 'Favoriter' }).getByRole('button', { name: /Skolan/ }).click()
      await expect(destination(page)).toHaveValue(new RegExp(tag))
      await expect(destination(page)).toHaveValue(school.formattedAddress!)
    } finally {
      await ctx.close()
    }
  })

  test('no HOME: "Spara ditt hem" leads to the places page and saving makes it the Åk hem target', async ({ browser, request }, testInfo) => {
    const tag = `${Date.now().toString(36)}${testInfo.project.name.replace(/\W/g, '')}nohome`
    const { ctx, page } = await openApp(browser, testInfo.project, tag)
    try {
      await expect(page.getByRole('button', { name: /Åk hem/ })).toHaveCount(0)
      await page.getByRole('button', { name: /Spara ditt hem/ }).click()
      await expect(page).toHaveURL(/\/app\/platser\?add=HOME/)
      const search = page.getByRole('combobox', { name: 'Sök plats att spara', exact: true })
      await search.fill('slut')
      await page.getByRole('option', { name: new RegExp(tag) }).first().click()
      const sheet = page.getByRole('dialog', { name: 'Spara plats' })
      await expect(sheet.getByLabel('Namn')).toHaveValue('Hem')
      await expect(sheet.getByRole('button', { name: /Hem/, pressed: true })).toBeVisible()
      await sheet.getByRole('button', { name: 'Spara', exact: true }).click()
      await expect(page.locator('.saved-row').filter({ hasText: 'Hem' })).toContainText(tag)
      const saved = await listPlaces(request, tokenP)
      expect(saved.filter((p) => p.kind === 'HOME')).toHaveLength(1)

      await page.goto('/app')
      await expect(page.getByRole('button', { name: /Åk hem/ })).toBeVisible()
      await expect(page.getByRole('button', { name: /Spara ditt hem/ })).toHaveCount(0)
    } finally {
      await ctx.close()
    }
  })

  test('places page: add, rename and delete round-trip', async ({ browser, request }, testInfo) => {
    const tag = `${Date.now().toString(36)}${testInfo.project.name.replace(/\W/g, '')}crud`
    const { ctx, page } = await openApp(browser, testInfo.project, tag)
    try {
      await page.goto('/app/platser')
      await expect(page.getByRole('heading', { name: 'Mina platser' })).toBeVisible()
      await expect(page.getByText('Spara hem, skolan')).toBeVisible()

      // add
      await page.getByRole('button', { name: /Lägg till plats/ }).click()
      await page.getByRole('combobox', { name: 'Sök plats att spara', exact: true }).fill('slut')
      await page.getByRole('option', { name: new RegExp(tag) }).first().click()
      const addSheet = page.getByRole('dialog', { name: 'Spara plats' })
      await addSheet.getByLabel('Namn').fill('Mormor')
      await addSheet.getByRole('button', { name: /Familj/ }).click()
      await addSheet.getByRole('button', { name: 'Spara', exact: true }).click()
      const row = page.locator('.saved-row').filter({ hasText: 'Mormor' })
      await expect(row).toContainText(`E2E Slut ${tag}`)
      let saved = await listPlaces(request, tokenP)
      expect(saved).toHaveLength(1)
      expect(saved[0]).toMatchObject({ label: 'Mormor', kind: 'FAMILY' })

      // rename
      await page.getByRole('button', { name: 'Ändra Mormor' }).click()
      const editSheet = page.getByRole('dialog', { name: 'Ändra plats' })
      await editSheet.getByLabel('Namn').fill('Mormor Maja')
      await editSheet.getByRole('button', { name: 'Spara', exact: true }).click()
      await expect(page.locator('.saved-row').filter({ hasText: 'Mormor Maja' })).toBeVisible()
      saved = await listPlaces(request, tokenP)
      expect(saved.map((p) => p.label)).toEqual(['Mormor Maja'])

      // delete
      await page.getByRole('button', { name: 'Ta bort Mormor Maja' }).click()
      await page.getByRole('dialog').getByRole('button', { name: 'Ta bort', exact: true }).click()
      await expect(page.locator('.saved-row')).toHaveCount(0)
      await expect(page.getByText('Spara hem, skolan')).toBeVisible()
      expect(await listPlaces(request, tokenP)).toHaveLength(0)
    } finally {
      await ctx.close()
    }
  })

  test('driver manages the test passenger\'s places via "Platser för…" (?userId=)', async ({ browser, request }, testInfo) => {
    const tag = `${Date.now().toString(36)}${testInfo.project.name.replace(/\W/g, '')}drv`
    const tokenD = await apiLogin(request, ids.driver)
    const candidates = await (await request.get(`${baseURL}/api/users/for-booking`, { headers: bearer(tokenD) })).json() as { id: number; fullName: string; email: string }[]
    const me = candidates.find((u) => u.email === ids.passenger.email)
    expect(me, 'test passenger is bookable by the test driver').toBeTruthy()
    await createPlace(request, tokenP, 'Skolan', 'SCHOOL', tag)

    const { ctx, page } = await openApp(browser, testInfo.project, tag, ids.driver)
    try {
      await page.goto('/app/mer')
      await page.getByRole('link', { name: 'Platser för…' }).click()
      await expect(page).toHaveURL(/\/app\/platser$/)
      await expect(page.getByRole('heading', { name: 'Mina platser' })).toBeVisible()
      // The driver's own list must not show the passenger's place.
      await expect(page.locator('.saved-row').filter({ hasText: 'Skolan' })).toHaveCount(0)

      await page.getByLabel('Platser för…').selectOption(String(me!.id))
      await expect(page).toHaveURL(new RegExp(`userId=${me!.id}`))
      await expect(page.getByRole('heading', { name: `Platser för ${me!.fullName}` })).toBeVisible()
      await expect(page.locator('.saved-row').filter({ hasText: 'Skolan' })).toBeVisible()

      // add for the passenger
      await page.getByRole('button', { name: /Lägg till plats/ }).click()
      await page.getByRole('combobox', { name: 'Sök plats att spara', exact: true }).fill('slut')
      await page.getByRole('option', { name: new RegExp(tag) }).first().click()
      const sheet = page.getByRole('dialog', { name: 'Spara plats' })
      await sheet.getByLabel('Namn').fill('Hos Greta')
      await sheet.getByRole('button', { name: 'Spara', exact: true }).click()
      await expect(page.locator('.saved-row').filter({ hasText: 'Hos Greta' })).toBeVisible()
      const mine = await listPlaces(request, tokenP)
      expect(mine.map((p) => p.label).sort()).toEqual(['Hos Greta', 'Skolan'])
      expect((await listPlaces(request, tokenD)).map((p) => p.label)).not.toContain('Hos Greta')

      // delete for the passenger
      await page.getByRole('button', { name: 'Ta bort Hos Greta' }).click()
      await page.getByRole('dialog').getByRole('button', { name: 'Ta bort', exact: true }).click()
      await expect(page.locator('.saved-row').filter({ hasText: 'Hos Greta' })).toHaveCount(0)
      expect((await listPlaces(request, tokenP)).map((p) => p.label)).toEqual(['Skolan'])
    } finally {
      await ctx.close()
    }
  })

  test('"Boka igen" from history fills the draft and asks Nu / Välj tid', async ({ browser, request }, testInfo) => {
    const tag = `${Date.now().toString(36)}${testInfo.project.name.replace(/\W/g, '')}again`
    const tokenD = await apiLogin(request, ids.driver)
    const ride = await bookRideApi(request, tokenP, tag)
    for (const step of ['accept', 'start', 'arrive', 'pickup', 'complete']) {
      await driverStep(request, tokenD, ride.id, step, step === 'accept' ? { confirmProximity: true } : undefined)
    }
    const { ctx, page } = await openApp(browser, testInfo.project, tag)
    try {
      await page.goto('/app/resor')
      const row = page.locator('article.ride-item').filter({ hasText: `E2E Start ${tag}` })
      await expect(row).toBeVisible()
      await row.getByRole('button', { name: new RegExp(`Boka resan till E2E Slut ${tag}`) }).click()
      await expect(page).toHaveURL(/\/app$|\/app\/boka/)
      await expect(pickup(page)).toHaveValue(`E2E Start ${tag}`)
      await expect(destination(page)).toHaveValue(`E2E Slut ${tag}`)
      await expect(page.getByRole('dialog', { name: 'När vill du åka?' })).toBeVisible()
      await expect(page.getByRole('dialog', { name: 'När vill du åka?' }).getByRole('button', { name: 'Välj tid' })).toBeVisible()
    } finally {
      await ctx.close()
    }
  })
})
