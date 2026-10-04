import { test, expect, type Page } from '@playwright/test'
import { ids, loginViaUi, pickAddress, newUserContext } from '../helpers'

// Real touch gestures (Chrome DevTools Protocol touch events) on the 24h clock picker in the pre-book screen.
// Regression for: swiping the clock hand locked onto the first number and never followed the finger.

type Pt = { x: number; y: number }

/** Page (client) coordinates of a point on the clock face; angle clockwise from 12 o'clock, r in viewBox units. */
async function clockPoint(page: Page, angleDeg: number, r: number): Promise<Pt> {
  return page.evaluate(
    ({ angleDeg, r }) => {
      const svg = document.querySelector('svg.mtp-clock') as SVGSVGElement
      const a = ((angleDeg - 90) * Math.PI) / 180
      const pt = svg.createSVGPoint()
      pt.x = 120 + r * Math.cos(a)
      pt.y = 120 + r * Math.sin(a)
      const s = pt.matrixTransform(svg.getScreenCTM()!)
      return { x: s.x, y: s.y }
    },
    { angleDeg, r }
  )
}

test('swiping the clock follows the finger (hours, then minutes)', async ({ browser }, testInfo) => {
  test.skip(testInfo.project.name.includes('iphone'), 'real-touch CDP gesture runs once, on the Android profile')
  const tag = `${Date.now().toString(36)}clk`
  const ctx = await newUserContext(browser, testInfo.project, tag)
  const page = await ctx.newPage()
  try {
    await loginViaUi(page, ids.passenger)
    await page.goto('/app')
    await pickAddress(page, 'Startadress', 'start', tag)
    await pickAddress(page, 'Destination', 'slut', tag)
    await page.getByRole('button', { name: 'Förboka' }).click()
    await page.getByRole('button', { name: 'Öppna tidsväljare' }).click()
    const dialog = page.getByRole('dialog', { name: 'Ange tid' })
    await expect(dialog).toBeVisible()
    const hourSeg = dialog.locator('.mtp-digital-seg').nth(0)
    const minSeg = dialog.locator('.mtp-digital-seg').nth(1)

    const cdp = await ctx.newCDPSession(page)
    const touch = (type: 'touchStart' | 'touchMove' | 'touchEnd', p?: Pt) =>
      cdp.send('Input.dispatchTouchEvent', { type, touchPoints: p ? [{ x: p.x, y: p.y, id: 1 }] : [] })

    // --- hours: press on 3 (outer ring), slide round the dial to 9 without lifting.
    await touch('touchStart', await clockPoint(page, 90, 88))
    await expect(hourSeg).toHaveText('03')
    for (const a of [120, 150, 180, 210, 240, 270]) await touch('touchMove', await clockPoint(page, a, 88))
    await expect(hourSeg).toHaveText('09') // the hand followed the whole swipe
    await expect(hourSeg).toHaveClass(/mtp-digital-active/) // still on the hour dial while the finger is down
    await touch('touchEnd')
    await expect(minSeg).toHaveClass(/mtp-digital-active/) // lifting the finger moves on to the minutes
    await expect(hourSeg).toHaveText('09')

    // --- minutes: press on 15, slide to 30 and then past the edge of the dial to 45.
    await touch('touchStart', await clockPoint(page, 90, 80))
    await expect(minSeg).toHaveText('15')
    for (const a of [120, 150, 180]) await touch('touchMove', await clockPoint(page, a, 80))
    await expect(minSeg).toHaveText('30')
    for (const a of [210, 240, 270]) await touch('touchMove', await clockPoint(page, a, 112))
    await expect(minSeg).toHaveText('45')
    await touch('touchEnd')
    await expect(minSeg).toHaveText('45')
    await expect(minSeg).toHaveClass(/mtp-digital-active/) // stays on minutes after the last swipe

    // --- the header lets you go back to the hours; confirm the chosen time.
    await hourSeg.click()
    await expect(hourSeg).toHaveClass(/mtp-digital-active/)
    await dialog.getByRole('button', { name: 'OK' }).click()
    await expect(dialog).toBeHidden()
    await expect(page.locator('.time-trigger-h')).toHaveText('09')
    await expect(page.locator('.time-trigger-m')).toHaveText('45')
  } finally {
    await ctx.close()
  }
})
