import { test, expect } from '@playwright/test'
import { ids, loginViaUi, newUserContext, pickAddress } from '../helpers'

test('golden flow: passenger books, driver accepts, drives and completes, passenger sees history', async ({ browser }, testInfo) => {
  const tag = `${Date.now().toString(36)}${testInfo.project.name.replace(/\W/g, '')}`
  const passengerCtx = await newUserContext(browser, testInfo.project, tag)
  const driverCtx = await newUserContext(browser, testInfo.project, tag)
  const passenger = await passengerCtx.newPage()
  const driver = await driverCtx.newPage()

  try {
    // Passenger logs in and books "Åka nu" through the UI, confirming in the bottom sheet.
    await loginViaUi(passenger, ids.passenger)
    await passenger.goto('/app')
    await pickAddress(passenger, 'Startadress', 'start', tag)
    await pickAddress(passenger, 'Destination', 'slut', tag)
    await passenger.getByRole('button', { name: /^Åk(a)? nu$/ }).click()
    const sheet = passenger.getByRole('dialog', { name: 'Stämmer det här?' })
    await expect(sheet).toContainText(`E2E Start ${tag}`)
    await expect(sheet).toContainText(`E2E Slut ${tag}`)
    await sheet.getByRole('button', { name: 'Ja, boka nu' }).click()

    // The new ride is the passenger's home from now on.
    await expect(passenger).toHaveURL(/\/app\/resa\/\d+/)
    await expect(passenger.getByRole('heading', { name: 'Vi letar efter en förare…' })).toBeVisible()
    await expect(passenger.getByText(`E2E Start ${tag}`).first()).toBeVisible()

    // Driver logs in (second context), lands on the driver home and sees the open ride.
    await loginViaUi(driver, ids.driver)
    await expect(driver).toHaveURL(/\/app\/forare$/)
    const rideCard = driver.locator('article.ride-item').filter({ hasText: `E2E Start ${tag}` })
    await expect(rideCard).toBeVisible()
    await rideCard.getByRole('button', { name: 'Ta resan' }).click()
    // Proximity warning appears when other test rides are close in time; confirm it if shown.
    const proximity = driver.getByRole('button', { name: 'Ta ändå' })
    await proximity.waitFor({ state: 'visible', timeout: 3_000 }).then(() => proximity.click(), () => {})

    // The accepted ride becomes the driver's active ride: open driving mode.
    await driver.getByRole('button', { name: /Öppna körläge|Fortsätt köra/ }).first().click()
    await expect(driver).toHaveURL(/\/app\/forare\/kor\/\d+/)
    await expect(driver.getByText(`E2E Start ${tag}`).first()).toBeVisible()

    // One big step button at a time.
    await driver.getByRole('button', { name: 'Kör nu' }).click()
    await driver.getByRole('button', { name: 'Jag är framme' }).click()
    await driver.getByRole('button', { name: 'Hämtat upp' }).click()
    await driver.getByRole('button', { name: 'Klar', exact: true }).click()
    await expect(driver.getByText('Klart! Bra jobbat')).toBeVisible()
    await driver.getByRole('button', { name: 'Klart – tillbaka' }).click()
    await expect(driver).toHaveURL(/\/app\/forare$/)

    // Passenger's ride screen follows along, and the ride ends up under "Tidigare" in Mina resor.
    await expect(passenger.getByRole('heading', { name: 'Framme! Tack för åkturen' })).toBeVisible({ timeout: 20_000 })
    await passenger.goto('/app/resor')
    await expect(passenger.getByRole('heading', { name: 'Mina resor' })).toBeVisible()
    const row = passenger.locator('article.ride-item').filter({ hasText: `E2E Start ${tag}` })
    await expect(row).toContainText('Klar')
  } finally {
    await passengerCtx.close()
    await driverCtx.close()
  }
})
