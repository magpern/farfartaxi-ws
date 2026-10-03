import { test, expect, type Page } from '@playwright/test'
import { ids, loginViaUi } from '../helpers'

const refreshFromPage = (page: Page) =>
  page.evaluate(async () => {
    const r = await fetch('/api/auth/refresh', { method: 'POST', credentials: 'same-origin' })
    return r.status
  })

test('expired access token is refreshed silently via the cookie; logout revokes the session', async ({ browser }, testInfo) => {
  const ctx = await browser.newContext({ ...(testInfo.project.use as object) })
  const page = await ctx.newPage()
  try {
    await loginViaUi(page, ids.passenger)

    // Simulate access-token expiry: keep the user object, replace the token with garbage.
    await page.evaluate(() => {
      const stored = JSON.parse(localStorage.getItem('farfartaxi-auth') ?? '{}')
      localStorage.setItem('farfartaxi-auth', JSON.stringify({ ...stored, token: 'expired.invalid.token' }))
    })

    const refreshed = page.waitForResponse(
      (r) => r.url().includes('/api/auth/refresh') && r.request().method() === 'POST'
    )
    await page.goto('/app/resor')
    expect((await refreshed).status()).toBe(200)

    // Still in the app, rides page rendered, and a real token is stored again.
    await expect(page).toHaveURL(/\/app\/resor/)
    await expect(page.getByRole('heading', { name: 'Mina resor' })).toBeVisible()
    const token = await page.evaluate(() => JSON.parse(localStorage.getItem('farfartaxi-auth') ?? '{}').token as string)
    expect(token).not.toBe('expired.invalid.token')
    expect(token.split('.')).toHaveLength(3)

    // Logout lives under the "Mer" tab and revokes the refresh cookie.
    await page.getByRole('link', { name: 'Mer' }).click()
    await expect(page).toHaveURL(/\/app\/mer/)
    const loggedOut = page.waitForResponse(
      (r) => r.url().includes('/api/auth/logout') && r.request().method() === 'POST'
    )
    await page.getByRole('button', { name: 'Logga ut' }).click()
    expect((await loggedOut).status()).toBe(204)
    await expect(page).toHaveURL(/\/login/)

    expect(await refreshFromPage(page)).toBe(401)
  } finally {
    await ctx.close()
  }
})
