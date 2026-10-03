# M6 contract (notifications)

Implements roadmap "M6: Notifications" and the rev 4.1 M6 machine gate. *(clarification)* marks master decisions.

## Principle
Push is an **attention mechanism, not a sync mechanism**: every state is visible in-app (polling/refresh on focus); the app must be fully usable with notifications denied.

## Backend
- Library `nl.martijndwars:web-push` (+ BouncyCastle provider). `PushService` sends real Web Push, `@Async` (dedicated small executor), never blocks or fails a ride action. Subscriptions answering **404/410 are deleted**; other failures are logged (no PII) and counted.
- Metrics: existing `AppMetrics.pushSent(ok)` → `farfartaxi.push{outcome}`; add `kind` tag (low cardinality).
- Payload JSON (encrypted): `{ "title", "body", "url", "tag", "rideId", "kind" }`. `url` is an in-app path (`/app/resa/{id}` for passengers, `/app/forare/kor/{id}` or `/app/forare` for drivers). `tag` = `ride-{id}` so updates replace older notifications for the same ride; `renotify` decided client-side per kind.
- **Locale** *(clarification)*: Flyway **V9** adds `users.locale VARCHAR(8) NOT NULL DEFAULT 'sv'`; `PUT /api/me/locale {"locale":"sv"|"en"}` (frontend calls it on login and on language switch). Texts per locale in a message bundle (correct å/ä/ö).
- **Preferences** (V9): `notification_prefs(user_id PK/FK ON DELETE CASCADE, ride_requests BOOLEAN DEFAULT TRUE, ride_updates BOOLEAN DEFAULT TRUE, reminders BOOLEAN DEFAULT TRUE)`; `GET/PUT /api/me/notification-prefs`. Missing row = all true.
- **Events → recipients** (all world-isolated; only through the M1 state machine domain events / timer service):
  - `ride_requests` (drivers): new ride offered ("Ny resa: Lisa 17:30, Jakobsberg → Hem"), NOW re-push at 10 min, ride re-offered after return/material edit (priority wording "Lisa ändrade resan — bekräfta"), urgent at T-60.
  - `reminders`: drivers T-24h / T-2h unaccepted; assigned driver T-30.
  - `ride_updates` (passenger): accepted ("Farfar tar resan"), EN_ROUTE ("Farfar är på väg"), **"Farfar är 5 min bort"** (first time ETA ≤ 5 min while EN_ROUTE, once only), ARRIVED ("Farfar är framme"), NO_DRIVER ("Ingen förare har tackat ja än"), cancelled by driver return → re-offered silently (no passenger push) *(clarification)*.
  - `ride_updates` (driver): passenger cancelled, passenger edited an accepted ride (minor), passenger quick message.
  - Messages: quick messages push to the other participant (`ride_updates`).
- **Once-only**: `ride_notifications_sent(ride_id, kind)` (exists since M1) guards every per-ride notification that must not repeat (`ETA_5MIN`, `ARRIVED`, timer kinds). Hysteresis: "5 min bort" fires the first time ETA crosses ≤ 5 and never again for that ride.
- **VAPID**: keys generated on the Pi into `.env` (never printed/committed); `VAPID_SUBJECT=https://farfartaxi.pernemark.se` *(clarification: a valid URL subject; `.local` mailto may be rejected by Apple)*. `/api/public/push-config` returns the public key.
- Subscriptions are user-scoped; logout deletes the current device's subscription (frontend sends endpoint).

## Frontend
- `vite-plugin-pwa` → `strategies: 'injectManifest'`, `src/sw.ts`: Workbox precache + navigation fallback, `push` handler (`showNotification(title, {body, tag, data:{url}, icon, badge, renotify})`), `notificationclick` (focus an existing client and navigate to `data.url`, else `openWindow`). The M2 prompt-based update flow must keep working (`registerSW` + `skipWaiting` on message).
- **App icon** *(clarification)*: replace the Vite default logo with a simple Farfartaxi icon (SVG source in repo: rounded square, deep blue background, white car pictogram); generated PNGs 192, 512, maskable 512, `apple-touch-icon` 180, monochrome badge 96; manifest `id`, `start_url: /app`, `scope: /`, `display: standalone`, theme/background colors; iOS meta tags (`apple-mobile-web-app-capable`, title, status bar style).
- **Onboarding** for everyone:
  - iOS Safari not installed → "Installera först" guide (extend `PwaInstallModal.tsx`): share → "Lägg till på hemskärmen", with clear steps; not shown again for 7 days if dismissed.
  - Installed (or Android/desktop) and permission `default` → one "Slå på notiser" card on Home/driver home that requests permission **from the tap**, subscribes, posts to `/api/push/subscriptions`.
  - `denied` → card explains how to enable in settings (once, dismissible).
  - Status + preferences in Mer ("Notiser: På/Av", three toggles mapped to prefs; drivers see all three, passengers see "Resuppdateringar" + "Påminnelser").
  - Re-subscribe on app start if permission granted but no/expired subscription; keep server in sync.
- Locale sync: call `PUT /api/me/locale` on login and on language switch.

## Machine gate (rev 4.1)
- Backend: integration test with a controlled push endpoint (WireMock or in-process HTTP server) that **decrypts** the Web Push payload with the test subscription's keys (aes128gcm) and asserts title/body/url/tag/kind for: Ny resa, Accepterad, 5 min bort (exactly once despite ETA oscillating 5↔6), Framme; recipient selection incl. world isolation and preferences; 410 → subscription deleted.
- Service worker: browser automation dispatches synthetic `push` events to the real built `sw.ts` and asserts `showNotification` args and that `notificationclick` focuses/opens `/app/resa/:id`.
- Production (best effort, not blocking): push-config returns a key; push metrics show sends.
- Final human checklist: locked iPhone (installed PWA) + Android receive the four notifications.
