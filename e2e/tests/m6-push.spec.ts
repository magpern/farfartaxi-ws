import { test, expect, type APIRequestContext, type BrowserContext, type Page, type Worker } from '@playwright/test'
import { apiLogin, bearer, baseURL, bookRideApi, cancelAllMine, cancelRideApi, ids, loginViaUi, newUserContext } from '../helpers'

/**
 * M6 gate (browser side): the REAL built service worker (served by nginx) shows notifications for synthetic `push`
 * events and routes `notificationclick`; onboarding card, Mer -> Notiser and logout-unsubscribe work against the real backend.
 *
 * Real vs stubbed:
 *  - SW gate: fully real (built sw.js, real Notification API, real PushEvent/NotificationEvent dispatched inside the worker).
 *  - Onboarding / logout: real backend + real UI + real permission prompt path. Headless Chromium has no push service (FCM),
 *    so `PushManager.subscribe/getSubscription` are replaced by an in-page fake (E2E_REAL_PUSH_SUBSCRIBE=1 disables the fake).
 *  - Denied: `Notification.permission` is overridden to 'denied' (Playwright cannot put a context into the denied state).
 * The iOS-UA project (chromium with an iPhone user agent) only asserts the "install first" guide; everything else is pixel-7.
 */

// The default chromium-headless-shell reports Notification.permission === 'denied' no matter what is granted;
// the new headless mode ('chromium' channel, same download) honours grantPermissions(['notifications']).
test.use({ channel: 'chromium' })

const REAL_SUBSCRIBE = process.env.E2E_REAL_PUSH_SUBSCRIBE === '1'
// Allowlisted push host (FCM-shaped URL with a bogus token): accepted by both the e2e and the production allowlist.
// The backend may later try to deliver to it; FCM answers 404 and the subscription is deleted automatically.
const FAKE_ENDPOINT = 'https://fcm.googleapis.com/fcm/send/e2e-fake-'

const isIphone = (name: string) => name === 'iphone-13'

/** Replaces the browser's push subscription with a fake whose key material is syntactically valid (any base64url). */
async function fakePushSubscription(ctx: BrowserContext) {
  await ctx.addInitScript((endpointBase) => {
    const w = window as unknown as { __fakeSub?: unknown }
    const b64 = (n: number) => btoa(String.fromCharCode(...Array.from({ length: n }, (_, i) => (i * 7 + 3) & 255))).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
    const stored = () => sessionStorage.getItem('__fakeSubEndpoint')
    const make = (endpoint: string) => ({
      endpoint,
      options: {},
      toJSON: () => ({ endpoint, keys: { p256dh: b64(65), auth: b64(16) } }),
      unsubscribe: async () => {
        sessionStorage.removeItem('__fakeSubEndpoint')
        w.__fakeSub = undefined
        return true
      }
    })
    const proto = PushManager.prototype as unknown as Record<string, unknown>
    proto.getSubscription = async () => {
      const e = stored()
      return e ? (w.__fakeSub ??= make(e)) : null
    }
    proto.subscribe = async () => {
      const e = endpointBase + Math.random().toString(36).slice(2)
      sessionStorage.setItem('__fakeSubEndpoint', e)
      return (w.__fakeSub = make(e))
    }
  }, FAKE_ENDPOINT)
}

async function ctxFor(browser: import('@playwright/test').Browser, project: { use: Record<string, unknown> }, tag: string, opts: { grant?: boolean; denied?: boolean } = {}) {
  const ctx = await newUserContext(browser, project, tag)
  // newUserContext lists only 'geolocation', which makes notifications 'denied'; reset so the state is the real 'default'.
  await ctx.clearPermissions()
  if (opts.grant) await ctx.grantPermissions(['notifications'])
  if (opts.denied) await ctx.addInitScript(() => Object.defineProperty(Notification, 'permission', { get: () => 'denied' }))
  if (!REAL_SUBSCRIBE) await fakePushSubscription(ctx)
  return ctx
}

async function swWorker(ctx: BrowserContext, page: Page): Promise<Worker> {
  await page.evaluate(async () => {
    await navigator.serviceWorker.ready
  })
  // wait until the page is controlled (activate -> clients.claim) so notificationclick can navigate it
  await page.waitForFunction(() => !!navigator.serviceWorker.controller, undefined, { timeout: 30_000 })
  await expect.poll(() => ctx.serviceWorkers().filter((w) => /\/sw\.js/.test(w.url())).length, { timeout: 15_000 }).toBeGreaterThan(0)
  return ctx.serviceWorkers().find((w) => /\/sw\.js/.test(w.url()))!
}

/** Dispatches the synthetic event inside the worker and waits for the handler's own event.waitUntil promise. */
async function pushIn(sw: Worker, payload: object) {
  const res = await sw.evaluate(async (p) => {
    const s = self as unknown as ServiceWorkerGlobalScope
    const ev = new PushEvent('push', { data: JSON.stringify(p) })
    const pending: Promise<unknown>[] = []
    ;(ev as unknown as { waitUntil: (p: Promise<unknown>) => void }).waitUntil = (x) => void pending.push(x)
    s.dispatchEvent(ev)
    try {
      await Promise.all(pending)
      return 'ok'
    } catch (e) {
      return `handler rejected: ${String(e)}`
    }
  }, payload)
  expect(res).toBe('ok')
}

type Shown = { title: string; body: string; tag: string; data: { url: string; rideId: unknown; kind: unknown } }
const shown = (sw: Worker) =>
  sw.evaluate(async (): Promise<Shown[]> => {
    const s = self as unknown as ServiceWorkerGlobalScope
    return (await s.registration.getNotifications()).map((n) => ({ title: n.title, body: n.body, tag: n.tag, data: n.data }))
  })
const clearShown = (sw: Worker) =>
  sw.evaluate(async () => {
    const s = self as unknown as ServiceWorkerGlobalScope
    for (const n of await s.registration.getNotifications()) n.close()
  })
/**
 * Chromium only allows WindowClient.focus() / Clients.openWindow() inside a browser-dispatched notificationclick
 * ("window interaction allowed" flag); a synthetic NotificationEvent is rejected with InvalidAccessError. So these two
 * browser-permission-gated calls (and only these) are wrapped to record their use and then no-op focus / skip openWindow.
 * Everything else in the real handler runs for real: matchAll, postMessage -> the page's router, safeUrl.
 */
const recordWindowApis = (sw: Worker) =>
  sw.evaluate(() => {
    const g = self as unknown as { __calls?: { focus: number; open: string[] } }
    g.__calls = { focus: 0, open: [] }
    WindowClient.prototype.focus = async function () {
      g.__calls!.focus++
      return this as WindowClient
    }
    Clients.prototype.openWindow = async function (url: string | URL) {
      g.__calls!.open.push(String(url))
      return null
    }
  })
const windowCalls = (sw: Worker) => sw.evaluate(() => (self as unknown as { __calls: { focus: number; open: string[] } }).__calls)
const clickFirst = (sw: Worker) =>
  sw.evaluate(async () => {
    const s = self as unknown as ServiceWorkerGlobalScope
    const [notification] = await s.registration.getNotifications()
    const ev = new NotificationEvent('notificationclick', { notification })
    const pending: Promise<unknown>[] = []
    ;(ev as unknown as { waitUntil: (p: Promise<unknown>) => void }).waitUntil = (p) => void pending.push(p)
    s.dispatchEvent(ev)
    try {
      await Promise.all(pending)
      return 'ok'
    } catch (e) {
      return `handler rejected: ${String(e)}`
    }
  })

let tokenP = ''
let rideId: number | null = null
const prefsUrl = `${baseURL}/api/me/notification-prefs`
let origPrefs: Record<string, boolean> | null = null
const pushEndpoints = new Set<string>()

async function dropSubscriptions(request: APIRequestContext) {
  for (const e of pushEndpoints) await request.delete(`${baseURL}/api/push/subscriptions?endpoint=${encodeURIComponent(e)}`, { headers: bearer(tokenP) })
  pushEndpoints.clear()
}

test.describe('M6 push', () => {
  test.beforeEach(async ({ request }) => {
    tokenP = await apiLogin(request, ids.passenger)
    origPrefs = await (await request.get(prefsUrl, { headers: bearer(tokenP) })).json()
  })
  test.afterEach(async ({ request }) => {
    if (origPrefs) await request.put(prefsUrl, { headers: bearer(tokenP), data: origPrefs })
    await dropSubscriptions(request)
    if (rideId != null) await cancelRideApi(request, tokenP, rideId)
    rideId = null
  })

  test('GATE: real built service worker shows push notifications and routes notificationclick', async ({ browser, request }, testInfo) => {
    test.skip(isIphone(testInfo.project.name), 'SW gate runs on the pixel-7 (Android UA) project')
    await cancelAllMine(request, tokenP)
    const ride = await bookRideApi(request, tokenP, 'm6sw') // a real ride so /app/resa/:id is a valid route
    rideId = ride.id
    const RID = ride.id
    const ctx = await ctxFor(browser, testInfo.project, 'm6sw', { grant: true })
    const page = await ctx.newPage()
    await loginViaUi(page, ids.passenger)
    const sw = await swWorker(ctx, page)
    await recordWindowApis(sw)

    // 1) push -> showNotification with the right args
    await pushIn(sw, { title: 'Farfar är framme', body: 'Farfar väntar utanför.', url: `/app/resa/${RID}`, tag: `ride-${RID}`, rideId: RID, kind: 'ARRIVED' })
    await expect.poll(async () => (await shown(sw)).length).toBe(1)
    const [n] = await shown(sw)
    expect(n.title).toBe('Farfar är framme')
    expect(n.body).toBe('Farfar väntar utanför.')
    expect(n.tag).toBe(`ride-${RID}`)
    expect(n.data).toEqual({ url: `/app/resa/${RID}`, rideId: RID, kind: 'ARRIVED' })

    // 2) a second push with the same tag replaces the first
    await pushIn(sw, { title: 'Farfar är på väg', body: 'x', url: `/app/resa/${RID}`, tag: `ride-${RID}`, rideId: RID, kind: 'EN_ROUTE' })
    await expect.poll(async () => (await shown(sw))[0]?.title).toBe('Farfar är på väg')
    expect(await shown(sw)).toHaveLength(1)

    // 3) notificationclick focuses the existing window and navigates it to data.url
    expect(await clickFirst(sw)).toBe('ok')
    await expect(page).toHaveURL(new RegExp(`/app/resa/${RID}$`))
    expect((await windowCalls(sw)).focus).toBe(1) // the existing window was focused, not a new one opened
    expect((await windowCalls(sw)).open).toEqual([])
    expect(await shown(sw)).toHaveLength(0) // the handler closes the notification

    // 4) foreign / protocol-relative URLs fall back to /app
    for (const bad of ['https://evil.example/phish', '//evil.example/x']) {
      await clearShown(sw)
      await pushIn(sw, { title: 'Hej', body: 'b', url: bad, tag: 'ride-9', rideId: 9, kind: 'ACCEPTED' })
      await expect.poll(async () => (await shown(sw)).length).toBe(1)
      expect((await shown(sw))[0].data.url).toBe('/app')
        expect(await clickFirst(sw)).toBe('ok')
      await expect(page).toHaveURL(new RegExp('/app$'))
      expect(new URL(page.url()).origin).toBe(new URL(baseURL).origin)
    }

    // 5) no window open -> openWindow(url) with the in-app URL
    await clearShown(sw)
    await pushIn(sw, { title: 'Ny', body: 'b', url: '/app/resa/77', tag: 'ride-77', rideId: 77, kind: 'ACCEPTED' })
    await expect.poll(async () => (await shown(sw)).length).toBe(1)
    await page.close()
    await expect.poll(() => sw.evaluate(async () => (await (self as unknown as ServiceWorkerGlobalScope).clients.matchAll({ type: 'window', includeUncontrolled: true })).length)).toBe(0)
    expect(await clickFirst(sw)).toBe('ok')
    expect((await windowCalls(sw)).open).toEqual(['/app/resa/77'])
    await ctx.close()
  })

  test('iOS Safari (not installed) is shown the "install first" guide', async ({ browser }, testInfo) => {
    test.skip(!isIphone(testInfo.project.name), 'iOS UA project only')
    const ctx = await ctxFor(browser, testInfo.project, 'm6ios')
    const page = await ctx.newPage()
    await page.goto('/login')
    const toggle = page.getByRole('button', { name: /E-post och lösenord/ })
    const email = page.getByLabel('E-post')
    await expect(toggle.or(email).first()).toBeVisible()
    if (await toggle.isVisible()) await toggle.click()
    await email.fill(ids.passenger.email)
    await page.getByLabel('Lösenord').fill(ids.passenger.password)
    await page.getByRole('button', { name: 'Fortsätt' }).click()
    await expect(page).toHaveURL(/\/app/)
    await expect(page.getByRole('heading', { name: 'Installera appen först' })).toBeVisible()
    await expect(page.getByText(/Lägg till på hemskärmen/).first()).toBeVisible()
    // not installed -> no enable-notifications card on Home
    await page.getByRole('dialog').getByRole('button', { name: 'Inte nu' }).click()
    await expect(page.getByRole('button', { name: 'Slå på notiser' })).toHaveCount(0)
    await ctx.close()
  })

  test('GATE: Home card "Slå på notiser" subscribes and registers it with the backend', async ({ browser }, testInfo) => {
    test.skip(isIphone(testInfo.project.name), 'pixel-7 only')
    const ctx = await ctxFor(browser, testInfo.project, 'm6on')
    const page = await ctx.newPage()
    await loginViaUi(page, ids.passenger)
    const card = page.getByRole('button', { name: 'Slå på notiser' })
    await expect(card).toBeVisible()
    await ctx.grantPermissions(['notifications']) // the user taps "Tillåt" in the browser prompt
    const posted = page.waitForRequest((r) => r.url().endsWith('/api/push/subscriptions') && r.method() === 'POST')
    const resp = page.waitForResponse((r) => r.url().endsWith('/api/push/subscriptions') && r.request().method() === 'POST')
    await card.click()
    const body = (await posted).postDataJSON()
    expect(body.endpoint).toMatch(/^https:\/\//)
    expect(body.p256dh).toBeTruthy()
    expect(body.auth).toBeTruthy()
    pushEndpoints.add(body.endpoint)
    expect((await resp).status()).toBe(200)
    await expect(card).toHaveCount(0)
    // backend SSRF guard: an endpoint on a non-push host is refused
    const evil = await ctx.request.post(`${baseURL}/api/push/subscriptions`, {
      headers: bearer(tokenP),
      data: { endpoint: 'https://evil.example/send/x', p256dh: body.p256dh, auth: body.auth }
    })
    expect(evil.status()).toBe(400)
    await ctx.close()
  })

  test('GATE: Mer -> Notiser toggles call PUT notification-prefs and persist after reload', async ({ browser }, testInfo) => {
    test.skip(isIphone(testInfo.project.name), 'pixel-7 only')
    const ctx = await ctxFor(browser, testInfo.project, 'm6prefs', { grant: true })
    const page = await ctx.newPage()
    await loginViaUi(page, ids.passenger)
    await page.getByRole('link', { name: 'Mer' }).click()
    await expect(page.getByTestId('push-status')).toHaveText('På')
    const updates = page.getByRole('checkbox', { name: /Resuppdateringar/ })
    const reminders = page.getByRole('checkbox', { name: /Påminnelser/ })
    await expect(page.getByRole('checkbox', { name: /Nya resor/ })).toHaveCount(0) // passengers only see two
    await expect(updates).toBeEnabled()
    const want = !(await updates.isChecked())
    const put = page.waitForRequest((r) => r.url().endsWith('/api/me/notification-prefs') && r.method() === 'PUT')
    const putResp = page.waitForResponse((r) => r.url().endsWith('/api/me/notification-prefs') && r.request().method() === 'PUT')
    await updates.setChecked(want)
    expect(put).toBeTruthy()
    expect((await put).postDataJSON()).toMatchObject({ rideUpdates: want })
    expect((await putResp).status()).toBe(200)
    const wantR = !(await reminders.isChecked())
    const putR = page.waitForResponse((r) => r.url().endsWith('/api/me/notification-prefs') && r.request().method() === 'PUT')
    await reminders.setChecked(wantR)
    expect((await putR).status()).toBe(200)
    await page.reload()
    await expect(page.getByRole('checkbox', { name: /Resuppdateringar/ })).toBeEnabled()
    await expect(page.getByRole('checkbox', { name: /Resuppdateringar/ })).toBeChecked({ checked: want })
    await expect(page.getByRole('checkbox', { name: /Påminnelser/ })).toBeChecked({ checked: wantR })
    await ctx.close()
  })

  test('denied permission shows the blocked card (and Mer says Blockerad)', async ({ browser }, testInfo) => {
    test.skip(isIphone(testInfo.project.name), 'pixel-7 only')
    const ctx = await ctxFor(browser, testInfo.project, 'm6denied', { denied: true })
    const page = await ctx.newPage()
    await loginViaUi(page, ids.passenger)
    await expect(page.getByRole('heading', { name: 'Notiser är blockerade' })).toBeVisible()
    await expect(page.getByRole('button', { name: 'Slå på notiser' })).toHaveCount(0)
    await page.getByRole('button', { name: 'Stäng' }).click()
    await expect(page.getByRole('heading', { name: 'Notiser är blockerade' })).toHaveCount(0)
    await page.getByRole('link', { name: 'Mer' }).click()
    await expect(page.getByTestId('push-status')).toHaveText('Blockerad')
    await ctx.close()
  })

  test('GATE: logout removes this device\'s subscription (DELETE with endpoint)', async ({ browser }, testInfo) => {
    test.skip(isIphone(testInfo.project.name), 'pixel-7 only')
    const ctx = await ctxFor(browser, testInfo.project, 'm6logout', { grant: true })
    const page = await ctx.newPage()
    // permission already granted -> the app re-subscribes on start and registers it
    const posted = page.waitForRequest((r) => r.url().endsWith('/api/push/subscriptions') && r.method() === 'POST')
    await loginViaUi(page, ids.passenger)
    const endpoint = (await posted).postDataJSON().endpoint as string
    pushEndpoints.add(endpoint)
    await page.getByRole('link', { name: 'Mer' }).click()
    const del = page.waitForRequest((r) => r.method() === 'DELETE' && r.url().includes('/api/push/subscriptions'))
    const delResp = page.waitForResponse((r) => r.request().method() === 'DELETE' && r.url().includes('/api/push/subscriptions'))
    await page.getByRole('button', { name: 'Logga ut' }).click()
    const url = new URL((await del).url())
    expect(url.searchParams.get('endpoint')).toBe(endpoint)
    expect([200, 204]).toContain((await delResp).status())
    await expect(page).toHaveURL(/\/(login)?$|\/login/)
    await ctx.close()
  })
})
