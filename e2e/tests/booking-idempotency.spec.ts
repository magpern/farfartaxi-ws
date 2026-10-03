import { test, expect, type Page } from '@playwright/test'
import { readFileSync } from 'node:fs'
import type { Identities } from '../global-setup'

const ids: Identities = JSON.parse(readFileSync(new URL('../.e2e-users.json', import.meta.url), 'utf8'))

async function loginViaUi(page: Page, u: { email: string; password: string }) {
  await page.goto('/login')
  const passwordToggle = page.getByRole('button', { name: /E-post och lösenord/ })
  const emailField = page.getByLabel('E-post')
  await expect(passwordToggle.or(emailField).first()).toBeVisible()
  if (await passwordToggle.isVisible()) await passwordToggle.click()
  await emailField.fill(u.email)
  await page.getByLabel('Lösenord').fill(u.password)
  await page.getByRole('button', { name: 'Fortsätt' }).click()
  await expect(page).toHaveURL(/\/app/)
  const later = page.getByRole('button', { name: 'Inte nu' })
  await later.waitFor({ state: 'visible', timeout: 5_000 }).then(() => later.click(), () => {})
}

test('double-tapping "Åka nu" creates exactly one ride', async ({ browser, request }, testInfo) => {
  const tag = `${Date.now().toString(36)}idem`
  const ctx = await browser.newContext({
    ...(testInfo.project.use as object),
    geolocation: { latitude: 59.3293, longitude: 18.0686 },
    permissions: ['geolocation']
  })
  const item = (name: string, lat: string, lon: string) => ({
    display_name: `${name}, Stockholm, Sverige`,
    name,
    lat,
    lon,
    address: { municipality: 'Stockholms kommun' }
  })
  await ctx.route('**/api/public/geocode/search**', (route) => {
    const q = (new URL(route.request().url()).searchParams.get('q') ?? '').toLowerCase()
    return route.fulfill({
      json: [q.includes('slut') ? item(`E2E Slut ${tag}`, '59.3400', '18.0900') : item(`E2E Start ${tag}`, '59.3293', '18.0686')]
    })
  })
  await ctx.route('**/api/public/geocode/reverse**', (route) => route.fulfill({ json: item(`E2E Start ${tag}`, '59.3293', '18.0686') }))
  await ctx.route('**/api/public/route/**', (route) =>
    route.fulfill({
      json: {
        code: 'Ok',
        routes: [{ distance: 2500, duration: 420, geometry: { type: 'LineString', coordinates: [[18.0686, 59.3293], [18.09, 59.34]] } }]
      }
    })
  )
  await ctx.route(/^https?:\/\/[^/]*(tile\.openstreetmap|openstreetmap\.org|osm\.org|unpkg\.com)[^/]*\//, (route) => route.abort())

  const page = await ctx.newPage()
  const baseURL = process.env.BASE_URL ?? 'http://127.0.0.1:8099'
  let token = ''
  const created: number[] = []
  try {
    await loginViaUi(page, ids.passenger)
    token = await page.evaluate(() => JSON.parse(localStorage.getItem('farfartaxi-auth') ?? '{}').token as string)
    const auth = { Authorization: `Bearer ${token}` }
    const myIds = async (): Promise<number[]> => {
      const all: number[] = []
      for (const h of ['true', 'false']) {
        const r = await request.get(`${baseURL}/api/rides/my?history=${h}`, { headers: auth })
        expect(r.ok()).toBeTruthy()
        for (const ride of (await r.json()) as { id: number }[]) all.push(ride.id)
      }
      return all
    }
    const before = new Set(await myIds())

    const posts: number[] = []
    page.on('response', async (r) => {
      if (r.url().endsWith('/api/rides') && r.request().method() === 'POST' && r.ok()) {
        posts.push((await r.json()).id)
      }
    })

    await page.goto('/app')
    for (const [label, q] of [['Startadress', 'start'], ['Destination', 'slut']]) {
      const input = page.getByLabel(label, { exact: true })
      await input.click()
      await input.fill(q)
      await page.locator('.sheet-search button', { hasText: tag }).first().click()
      await expect(input).toHaveValue(new RegExp(tag))
    }
    // Two rapid clicks on the same button.
    await page.getByRole('button', { name: /^Åk(a)? nu$/ }).dblclick({ timeout: 10_000 }).catch(() => {})
    await expect(page).toHaveURL(/\/app\/bekraftelse/)

    const after = (await myIds()).filter((id) => !before.has(id))
    created.push(...after)
    expect(after).toHaveLength(1)
    // Any POST that succeeded must have returned that one ride.
    for (const id of posts) expect(id).toBe(after[0])
  } finally {
    // Clean up through the API (cancel everything this test created).
    if (token) {
      for (const id of created) {
        await request.post(`${baseURL}/api/rides/${id}/cancel`, {
          headers: { Authorization: `Bearer ${token}` },
          data: { reason: 'e2e cleanup', confirm: true }
        })
      }
    }
    await ctx.close()
  }
})
