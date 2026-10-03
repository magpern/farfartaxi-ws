import { expect, type APIRequestContext, type Browser, type BrowserContext, type Page } from '@playwright/test'
import { readFileSync } from 'node:fs'
import type { Identities } from './global-setup'

export const ids: Identities = JSON.parse(readFileSync(new URL('./.e2e-users.json', import.meta.url), 'utf8'))
export const baseURL = process.env.BASE_URL ?? 'http://127.0.0.1:8099'

export type StubPlace = {
  provider: 'SL' | 'NOMINATIM' | 'FAVORITE' | 'RECENT'
  providerPlaceId: string | null
  kind: 'STOP' | 'ADDRESS' | 'POI' | 'FAVORITE' | 'RECENT'
  name: string
  area: string | null
  formattedAddress: string
  lat: number
  lon: number
  distanceKm: number | null
}

export const stubPlace = (name: string, lat: number, lon: number, over: Partial<StubPlace> = {}): StubPlace => ({
  provider: 'SL',
  providerPlaceId: `e2e-${name.replace(/\W+/g, '-')}`,
  kind: 'ADDRESS',
  name,
  area: 'Stockholm',
  formattedAddress: `${name}, Stockholm`,
  lat,
  lon,
  distanceKm: null,
  ...over
})

export const ROUTE_STUB = {
  code: 'Ok',
  routes: [{ distance: 2500, duration: 420, geometry: { type: 'LineString', coordinates: [[18.0686, 59.3293], [18.09, 59.34]] } }]
}

/**
 * Canned responses so tests never depend on SL / Nominatim / OSRM / OSM tiles.
 * Search returns "E2E Start <tag>" (or "E2E Slut <tag>" when the query contains "slut"); nearest-stop answers 204.
 * Later `ctx.route` calls override these (Playwright matches the most recently registered route first).
 */
export async function stubExternal(ctx: BrowserContext, tag: string) {
  const start = stubPlace(`E2E Start ${tag}`, 59.3293, 18.0686)
  const slut = stubPlace(`E2E Slut ${tag}`, 59.34, 18.09)
  await ctx.route('**/api/places/search**', (route) => {
    const q = (new URL(route.request().url()).searchParams.get('q') ?? '').toLowerCase()
    return route.fulfill({ json: { results: [q.includes('slut') ? slut : start], hasMore: false, context: 'DEFAULT' } })
  })
  await ctx.route('**/api/places/reverse**', (route) => route.fulfill({ json: start }))
  await ctx.route('**/api/places/nearest-stop**', (route) => route.fulfill({ status: 204 }))
  await ctx.route('**/api/public/route/**', (route) => route.fulfill({ json: ROUTE_STUB }))
  await ctx.route(/^https?:\/\/[^/]*(tile\.openstreetmap|openstreetmap\.org|osm\.org|unpkg\.com)[^/]*\//, (route) => route.abort())
}

export async function newUserContext(browser: Browser, project: { use: Record<string, unknown> }, tag: string) {
  const ctx = await browser.newContext({
    ...(project.use as object),
    geolocation: { latitude: 59.3293, longitude: 18.0686 },
    permissions: ['geolocation']
  })
  await stubExternal(ctx, tag)
  return ctx
}

export async function loginViaUi(page: Page, u: { email: string; password: string }) {
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

/** Types into a place-search combobox and picks the first option containing `tag`. */
export async function pickAddress(page: Page, ariaLabel: string, query: string, tag: string) {
  const input = page.getByRole('combobox', { name: ariaLabel, exact: true })
  await input.click()
  await input.fill(query)
  await page.getByRole('option', { name: new RegExp(tag) }).first().click()
  await expect(input).toHaveValue(new RegExp(tag))
}

/** Password login straight against the API (no browser). */
export async function apiLogin(request: APIRequestContext, u: { email: string; password: string }): Promise<string> {
  const r = await request.post(`${baseURL}/api/auth/login`, { data: u })
  expect(r.ok(), `API login ${u.email}`).toBeTruthy()
  return (await r.json()).token as string
}

export const bearer = (token: string) => ({ Authorization: `Bearer ${token}` })

export type ApiRide = { id: number; status: string; driverPhone?: string | null; passengerPhone?: string | null }

/** Books a SCHEDULED ride (+1 h) as the passenger. */
export async function bookRideApi(request: APIRequestContext, token: string, tag: string): Promise<ApiRide> {
  const scheduledAt = new Date(Date.now() + 60 * 60_000).toISOString().replace(/\.\d+Z$/, 'Z')
  const r = await request.post(`${baseURL}/api/rides`, {
    headers: { ...bearer(token), 'Idempotency-Key': `e2e-${tag}-${Math.random().toString(36).slice(2)}` },
    data: {
      kind: 'SCHEDULED',
      fromAddress: `E2E Start ${tag}`,
      fromLat: 59.3293,
      fromLon: 18.0686,
      toAddress: `E2E Slut ${tag}`,
      toLat: 59.34,
      toLon: 18.09,
      scheduledAt
    }
  })
  expect(r.ok(), `book ride: ${r.status()}`).toBeTruthy()
  return (await r.json()) as ApiRide
}

export async function driverStep(request: APIRequestContext, token: string, id: number, step: string, data?: object) {
  const r = await request.post(`${baseURL}/api/driver/rides/${id}/${step}`, { headers: bearer(token), data })
  expect(r.ok(), `driver ${step}: ${r.status()}`).toBeTruthy()
}

/** Best-effort cleanup: cancel (with confirm) whatever is still open; ignore ones already finished. */
export async function cancelRideApi(request: APIRequestContext, token: string, id: number) {
  await request.post(`${baseURL}/api/rides/${id}/cancel`, { headers: bearer(token), data: { reason: 'e2e cleanup', confirm: true } })
}

/** Cancels every ride of the user still listed as ongoing/upcoming (e.g. left over from a failed run). */
export async function cancelAllMine(request: APIRequestContext, token: string) {
  const r = await request.get(`${baseURL}/api/rides/my?history=false`, { headers: bearer(token) })
  if (!r.ok()) return
  for (const ride of (await r.json()) as { id: number }[]) await cancelRideApi(request, token, ride.id)
}
