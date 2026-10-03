import { request } from '@playwright/test'
import { writeFileSync } from 'node:fs'

export const USERS_FILE = new URL('./.e2e-users.json', import.meta.url).pathname

export type Identities = {
  passenger: { email: string; password: string }
  driver: { email: string; password: string }
}

/**
 * Normal mode: the seeded test identities (test-passenger/test-driver @farfartaxi.invalid) with passwords from env.
 * E2E_CREATE_USERS=1 (local fallback only, needs the e2e admin from docker-compose.e2e.yml): register two throwaway
 * users, approve them via the admin API and promote one to DRIVER.
 */
export default async function globalSetup() {
  const baseURL = process.env.BASE_URL ?? 'http://127.0.0.1:8099'
  let ids: Identities

  if (process.env.E2E_CREATE_USERS === '1') {
    const adminEmail = process.env.E2E_ADMIN_EMAIL ?? 'e2e-admin@farfartaxi.invalid'
    const adminPassword = process.env.E2E_ADMIN_PASSWORD ?? 'e2e-only-admin-password-1'
    const api = await request.newContext({ baseURL })
    const tag = Date.now().toString(36)
    const password = `e2e-only-${tag}-pw`
    const admin = await (await api.post('/api/auth/login', { data: { email: adminEmail, password: adminPassword } })).json()
    const auth = { Authorization: `Bearer ${admin.token}` }
    const mk = async (kind: string, driver: boolean) => {
      const email = `e2e-${kind}-${tag}@farfartaxi.invalid`
      const reg = await api.post('/api/auth/register', { data: { email, password, fullName: `E2E ${kind} ${tag}` } })
      if (!reg.ok()) throw new Error(`register ${kind} failed: ${reg.status()}`)
      const id = (await reg.json()).user.id
      const ap = await api.post(`/api/admin/users/${id}/approve`, { headers: auth })
      if (!ap.ok()) throw new Error(`approve ${kind} failed: ${ap.status()}`)
      if (driver) {
        const pr = await api.post(`/api/admin/users/${id}/promote-driver`, { headers: auth })
        if (!pr.ok()) throw new Error(`promote ${kind} failed: ${pr.status()}`)
      }
      return { email, password }
    }
    ids = { passenger: await mk('passenger', false), driver: await mk('driver', true) }
    await api.dispose()
  } else {
    const passengerPw = process.env.TEST_PASSENGER_PASSWORD
    const driverPw = process.env.TEST_DRIVER_PASSWORD
    if (!passengerPw || !driverPw) {
      throw new Error('TEST_PASSENGER_PASSWORD and TEST_DRIVER_PASSWORD must be set (or E2E_CREATE_USERS=1 locally)')
    }
    ids = {
      passenger: { email: 'test-passenger@farfartaxi.invalid', password: passengerPw },
      driver: { email: 'test-driver@farfartaxi.invalid', password: driverPw }
    }
  }
  writeFileSync(USERS_FILE, JSON.stringify(ids), { mode: 0o600 })
}
