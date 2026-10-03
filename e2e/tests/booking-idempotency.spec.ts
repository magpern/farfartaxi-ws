import { test, expect } from '@playwright/test'
import { ids, loginViaUi, pickAddress, stubExternal } from '../helpers'

test('double-tapping the booking confirmation creates exactly one ride', async ({ browser, request }, testInfo) => {
  const tag = `${Date.now().toString(36)}idem`
  const ctx = await browser.newContext({
    ...(testInfo.project.use as object),
    geolocation: { latitude: 59.3293, longitude: 18.0686 },
    permissions: ['geolocation']
  })
  await stubExternal(ctx, tag)

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
    await pickAddress(page, 'Startadress', 'start', tag)
    await pickAddress(page, 'Destination', 'slut', tag)
    // "Åka nu" opens the confirmation sheet; two rapid taps on its confirm button must still book once.
    await page.getByRole('button', { name: /^Åk(a)? nu$/ }).click()
    const sheet = page.getByRole('dialog', { name: 'Stämmer det här?' })
    await sheet.getByRole('button', { name: 'Ja, boka nu' }).dblclick({ timeout: 10_000 }).catch(() => {})
    await expect(page).toHaveURL(/\/app\/resa\/\d+/)

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
