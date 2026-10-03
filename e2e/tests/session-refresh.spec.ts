import { test, expect, type Page } from '@playwright/test'
import { readFileSync } from 'node:fs'
import type { Identities } from '../global-setup'

const ids: Identities = JSON.parse(readFileSync(new URL('../.e2e-users.json', import.meta.url), 'utf8'))

async function loginViaUi(page: Page, u: { email: string; password: string }) {
  await page.goto('/login')
  // When Google sign-in is configured (production), the password form sits behind a toggle button.
  const passwordToggle = page.getByRole('button', { name: /E-post och lösenord/ })
  const emailField = page.getByLabel('E-post')
  await expect(passwordToggle.or(emailField).first()).toBeVisible()
  if (await passwordToggle.isVisible()) await passwordToggle.click()
  await emailField.fill(u.email)
  await page.getByLabel('Lösenord').fill(u.password)
  await page.getByRole('button', { name: 'Fortsätt' }).click()
  await expect(page).toHaveURL(/\/app/)
  // The PWA-install modal appears once after login and blocks the page; dismiss it.
  const later = page.getByRole('button', { name: 'Inte nu' })
  await later.waitFor({ state: 'visible', timeout: 5_000 }).then(() => later.click(), () => {})
}

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
    await expect(page.getByText('Kommande resor')).toBeVisible()
    const token = await page.evaluate(() => JSON.parse(localStorage.getItem('farfartaxi-auth') ?? '{}').token as string)
    expect(token).not.toBe('expired.invalid.token')
    expect(token.split('.')).toHaveLength(3)

    // Logout through the side menu revokes the refresh cookie.
    await page.getByRole('button', { name: 'Meny' }).click()
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
