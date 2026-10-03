# Farfartaxi: analysis and improvement plan (rev 4, frozen; autonomous execution)

> Rev 4: the Process section is replaced by an autonomous, agent-driven execution model with machine gates. Production test identities and pre-migration backups with expand/contract migrations are added. Real-device criteria move to a final human checklist, and the needed permissions are listed as a one-time prerequisite.
>
> Rev 4.1 safety corrections:
> - The JWT gate is conditional on rotation.
> - Test/real ride isolation is enforced server-side in both directions, with random test credentials.
> - Milestones run strictly serially, with central migration numbering.
> - The M6 gate proves our code, with real push delivery best effort.
> - Expiry rules are proven with a controlled clock.
> - Secrets are backed up as a root-only `.env` copy and never appear in output.

> Rev 2 included the first external review: security moved to the front, the state machine and offer model come first, the UX shell comes before features, and the code split is gradual.
>
> Rev 3 applies the second review's targeted corrections:
> - M0A split into containment and auth modernization (M0C).
> - Recoverable `NO_DRIVER`.
> - "Available now" vs "away" semantics.
> - Material-change edit rules.
> - The proximity warning sits behind an interface.
> - `ride_messages` separated from `ride_events`.
> - Decaying learned ranking.
> - Linear-scan nearest stop.
> - Update guard covers booking drafts.
> - 1 h location retention.
> - Plus: Book again, stale-offer messages, call fallback and a confirmation sheet.
>
> See **Review reconciliation** and **Process** at the end.

## Context

Farfartaxi is a family ride-booking PWA. **Passengers are teenagers and drivers are grandparents.** It runs on the gateway Pi as `farfartaxi-frontend` (nginx, port 5124), `farfartaxi-backend` (Spring Boot 4 / Java 21, port 8081) and `farfartaxi-postgres`, behind SWAG. The code lives in `magpern/farfartaxi-web` (React 18 + Vite + Leaflet; nearly everything is in one 2,366-line `src/App.tsx`) and `magpern/farfartaxi-ws`.

Phones are a mix of iOS and Android. All rides happen in Stockholm, Järfälla, Solna, Sundbyberg, Upplands Väsby and Vallentuna, which is inside SL's area. Decisions already made: stay a **PWA** (no native wrapper for now) and keep telemetry **self-hosted** (Postgres + Grafana).

## Findings (verified in code and production)

| Area | Finding |
|---|---|
| **Security** | **Anyone can self-register** (`POST /api/auth/register`, enabled immediately as USER). `/api/driver/rides/open` has **no role check**. Together, a stranger who finds the URL can see the children's pickup addresses and times and create bookings. |
| Security | The public repo's `docker-compose.rpi.yml` has fallback defaults for the DB password, admin password and JWT secret. Postgres is published on `0.0.0.0:5433`. JWTs last one year and can't be revoked. (Couldn't check whether production uses the defaults: reading the container env was blocked.) |
| Search | Production: avg **1,181 ms**, max **4,376 ms**, **15% over 2 s**. Cause: a global `synchronized` + `Thread.sleep(1100)` in `NominatimProxyService`, and type-ahead requests queue up behind it. No abort (stale results overwrite newer ones), no location bias, no partial matching. OSMF policy **forbids autocomplete** on public Nominatim. |
| Search alternative | SL `stop-finder` (tested): ~120 ms, no key, prefix matching. Returns stops, `singlehouse` addresses and POIs (Westfield Mall of Scandinavia, Kista Galleria). No proximity ranking. SL Transport `GET /v1/sites`: **6,516 stops with lat/lon**, no key, so a local nearest-stop lookup is feasible. |
| Favorites | `saved_places` table and CRUD API exist; **the web app never uses them**. |
| Push | `PushService` only logs (0 log lines in production). No Web Push library. The service worker has no `push`/`notificationclick` handlers. Only drivers can subscribe. The manifest has no PNG/maskable/apple icons. |
| Live tracking | Passengers never see the driver on a map; there's a text ETA only. The ETA is straight-line distance to the **destination**, not the pickup. GPS stops when the driver leaves the page or reloads. SSE is never used, and is broken anyway (needs a Bearer header). The share link points to raw JSON. |
| Ride model | **One driver pressing "refuse" sets `REJECTED` for everyone.** No "arrived" or "picked up" states. No editing. Duplicate bookings are possible: the "Åk nu" and pre-booking buttons have no pending state, and there's no idempotency. |
| UX | Drivers land on the booking map. An active ride is never shown up front. Status shows raw enums. Rating always sends 5 stars. Accept/start errors aren't handled. |
| PWA updates | `registerType: 'autoUpdate'` can reload the app **mid-ride**. |
| Observability | One log line per request (no user/request id). No Actuator/metrics. **`ride_events` is never written.** No frontend error reporting. |
| Exists already (reuse) | Admin page: roles, enable/disable, delete, force password change, edit name/phone/vehicle. `users.phone`, `photo_url`, `vehicle_note`. Driver "unaccept" (backend + button). Booking on someone's behalf (`passengerUserId`). Share-token revoke. `TIMESTAMPTZ` columns in UTC. |

## Product model (what we're building toward)

**Global invariant:** if a user has an active ride, that ride *is* the home screen. Passengers see "Farfar är på väg"; drivers see "Hämta Lisa". No tabs or menus in the way.

**Passenger:** Home ("Vart ska du?" with the **Åk hem** big button, favorites, recents and search; pickup is implicitly 📍 Här) → Now / later → Confirm → Waiting for a driver → Accepted → On the way → **Arrived** → Picked up → Done (rate).

**Driver:** Home (availability toggle, new requests, today's rides) → Request card (**Ta resan** / **Kan inte**) → Upcoming → **Kör nu** → *Navigate to Lisa* → **Jag är framme** → **Hämtat upp** → *Navigate to Hem* → **Klar**.

### Ride state machine (M1)
```
REQUESTED ──accept──> ACCEPTED ──start──> EN_ROUTE ──arrive──> ARRIVED ──pickup──> PICKED_UP ──complete──> COMPLETED
  ▲   │                  │  ├─return (driver)──────────────> REQUESTED
  │   │                  │  └─material edit (passenger)────> REQUESTED (same driver re-offered first)
  │   ├─timeout / all offers declined or expired─> NO_DRIVER
  │   │                                              ├─"Fortsätt vänta" (keep waiting)─> REQUESTED (new timeout window)
  └───┼──────────────────────────────────────────────┘
      │                                              ├─edit time → REQUESTED (re-offers decliners too)
      │                                              └─cancel ─> CANCELLED
      └─cancel (passenger) allowed in REQUESTED, NO_DRIVER, ACCEPTED, EN_ROUTE, ARRIVED → CANCELLED
        (EN_ROUTE/ARRIVED cancel requires confirm + notifies driver; PICKED_UP: no cancel — driver ends ride)
ARRIVED is the hook for a future NO_SHOW (not built now).
```
- **`NO_DRIVER` is recoverable, not terminal.** The passenger sees "Ingen förare har tackat ja än" (no driver has accepted yet) with **Fortsätt vänta** (keep waiting), **Ring** (call) and **Avboka** (cancel).
  - "Fortsätt vänta" re-offers the drivers whose offers *expired*. It's hidden if every driver explicitly *declined*.
  - Changing the time re-offers everyone, including drivers who declined (their "can't" may have been about the time).
- Implemented as one `RideStateMachine` class with explicit allowed transitions plus guard roles. Every transition writes `ride_events` and sends a domain event that notifications listen to.
- Concurrency: transitions are conditional updates (`… WHERE id=? AND status=?`) or JPA `@Version`, so two drivers can't both accept. The loser gets a 409 with a clear UI message.
- Migrate existing rows: `PENDING_OPEN → REQUESTED`, `IN_PROGRESS → EN_ROUTE`, `REJECTED → NO_DRIVER`.

### Driver offer model (M1)
`ride_offers(ride_id, driver_id, status OFFERED|VIEWED|ACCEPTED|DECLINED|EXPIRED|WITHDRAWN, offered_at, viewed_at, responded_at, comment)`, unique on `(ride_id, driver_id)`.
- **Availability has two separate concepts:**
  - `driver_available_now` (the toggle on the driver home screen) affects **NOW rides only**: who gets offered and pushed immediately.
  - `driver_away_from` / `driver_away_until` (optional "Bortrest" (away) dates) suppress offers and pushes for **any ride whose scheduled time falls in that period**.
  - Scheduled rides are offered and pushed to every driver who isn't away at the ride's time, whatever their "available now" toggle says. Notification preferences (M6) still apply.
- **Kan inte** declines only that driver's offer. When every offer is declined or expired (or the NOW timeout hits), the ride becomes `NO_DRIVER` and the passenger gets a push.
- When a driver returns a ride, it goes back to `REQUESTED`: their offer becomes WITHDRAWN and the others are re-offered.
- **Stale offers:** opening an offer or notification for a ride that's already taken, cancelled or changed shows "Resan är redan tagen" (the ride has already been taken), or "har ändrats" (has changed), instead of an error. The API returns 409 with a reason code, and the UI maps each code to a message.

### Ride kinds (M1)
`ride_kind NOW | SCHEDULED`. Thresholds live in config.
- **NOW:** offered immediately. If not accepted in 10 min, re-push to all drivers. At 20 min it becomes `NO_DRIVER`, and the passenger is told to call.
- **SCHEDULED:** offered when booked. If still unaccepted at T-24h or T-2h, remind drivers. At T-60 min it's marked **urgent**. The assigned driver gets a reminder at T-30 min.
- **Editing:**
  - `REQUESTED` / `NO_DRIVER`: anything can change.
  - `ACCEPTED`: pickup, destination, time and note can change.
    - A **material change** sends the ride back to `REQUESTED`. The same driver is re-offered first ("Lisa ändrade resan — bekräfta", i.e. Lisa changed the ride, please confirm), then the others.
    - A material change is any one of:
      - The pickup moves more than 500 m.
      - The time shifts more than 30 min.
      - The new route is more than 25% or more than 5 km longer (OSRM, haversine fallback).
    - Smaller changes keep the ride `ACCEPTED`, and the driver is notified.
  - `EN_ROUTE` and later: **no edits in the app.** Use a quick message or call. This keeps the driving flow simple.
- **Time:** store UTC (already done) and always render with `timeZone: 'Europe/Stockholm'`. The time picker rejects times that don't exist on DST days and flags ambiguous ones. DST ends **2026-10-25**, so add tests for that.

---

## Milestones

### M0A: Security containment (immediate, small, ships first)
1. **Close the stranger hole:**
   - Self-registered and Google-created accounts start **disabled/pending** until an admin approves them. The admin page gets a "Pending" list, and the new user sees an "awaiting approval" screen.
   - Add the role check on `/api/driver/rides/open`.
   - Review every endpoint for role checks (`/api/users/for-booking` already has one).
2. **Secrets:** you check (I'm blocked from reading the env) whether production uses the repo defaults, without printing them. Example: `docker exec farfartaxi-backend sh -c '[ "$APP_JWT_SECRET" = "replace-this-in-production-with-a-long-secret-value" ] && echo DEFAULT || echo custom'`, and the same check for the DB and admin passwords. **If any is a default, rotate it** (`.env` on the Pi). Rotating the JWT secret logs everyone out once.
3. Remove the secret fallbacks from the public compose file and `.env.example` (use `${VAR:?required}`). Git history still contains them, so **rotation is the real fix**.
4. Stop publishing Postgres: remove the `5433` port mapping. First check that nothing (backups, tooling) on the Pi uses it.
5. Pin images by sha tag in the Pi compose file and keep a rollback comment.

Until M0C, disabling a user is the revocation mechanism (`JwtAuthFilter` already rejects disabled users). Rotating the JWT secret is the "log everyone out" switch.

### M0C: Auth modernization (separate from M0A so it doesn't delay containment; runs after M0B, before M1)
- Access JWTs last 1 h, with a **rotating refresh token** (90 days, hashed in `refresh_tokens`, reuse detection).
- Logout revokes the refresh token, and admins get "log out everywhere".
- Teens stay logged in, and tokens can be revoked.
- The frontend refreshes transparently. The existing one-year tokens are accepted until their natural expiry or the next secret rotation.

### M0B: Baseline measurement and safety net (small)
- Actuator + Micrometer Prometheus on an **internal management port** (`8090`, not proxied). This also fixes today's 404 on `/actuator/health`.
- A request-id + userId MDC filter: extend `config/ApiRequestLoggingFilter.java` and send `X-Request-Id` in and out.
- A handful of timers and counters: search latency/empty/error, booking created, push sent/failed, transition count.
- `ride_events` starts being written (at first from the current transitions; M1 extends it).
- A Playwright "golden flow" test at mobile viewport (book → accept → complete) against a dockerized backend in CI. Add ESLint and Vitest to the web repo.
- **Production test identities with two-way isolation.** Add `users.is_test` and `rides.is_test`; a ride's `is_test` comes from its passenger. Seeded accounts: "Test Passagerare" and "Test Förare".
  - **Invariant, enforced server-side:** *test users can see and act on only test rides; real users can see and act on only real rides.*
  - It's checked in one central place (a `RideAccessPolicy` used by every repository query and service method). It applies to every ride listing, lookup, offer creation, notification recipient set, message, share and mutation, and to booking on behalf: a test driver can only book for test users, and vice versa.
  - Test rides are excluded from Grafana and `app_events` dashboards and deleted nightly.
  - Integration tests prove both directions for every endpoint. This is part of the M0B gate.
  - **Test credentials are generated randomly at deploy time** and stored only in a root-readable file on the Pi (`chmod 600`). They are never committed, hard-coded, logged or reported.
  - This lets agents run post-deploy acceptance on production without the grandparents ever seeing or receiving anything.
- `scripts/smoke.sh` (in `farfartaxi-ws`): health, version endpoint, and the golden flow with test accounts against a given base URL. Every deploy uses it.
- **No up-front `App.tsx` split.** Refactor gradually instead: each milestone moves the code it touches into `pages/`, `components/`, `hooks/` and `api/`.

### M1: Core ride model
Build the state machine, offers, ride kinds, edit, return, cancel rules, idempotency and conflict warning described above.
- **Idempotency:** `POST /api/rides` takes an `Idempotency-Key`, stored as `(passenger_id, client_request_id)` unique. The UI disables the button while submitting.
- **Proximity warning (not conflict detection):** accepting a ride within ±45 min of another accepted ride returns a `warning`. The UI asks "Du har redan en resa 17:00 — ta ändå?" (you already have a ride at 17:00, take it anyway?).
  - The check sits behind a `RideConflictChecker` interface, so a later version can use ride duration plus travel time between rides.
- **Driver availability:** `users.driver_available_now` plus `driver_away_from/until` (semantics above). A toggle and a "Bortrest" date picker on the driver home screen.
- **Pickup note** at booking, plus **quick messages** during the ride: "Jag står utanför" (I'm outside), "Kommer om 2 min" (coming in 2 min), "Ring mig" (call me). Driver quick replies include "Jag är här" (I'm here).
  - Stored in their own table `ride_messages(id, ride_id, sender_id, code, text, created_at, read_at)` and pushed in M6.
  - **`ride_events` stays a pure audit/state timeline.**
- The ride DTO grows: driver name, `photo_url`, `vehicle_note` (car and plate), passenger and driver phone (for tel:/sms:), plus the pickup note.
- Integration tests cover every transition, role guard, race (two accepts) and the decline-all path.

### M2: Mobile UX shell
Lay down the structure before any features plug into it.
- Routing:
  - Passengers: Home `/app`, active ride `/app/resa/:id`, history `/app/resor`, places `/app/platser`, more `/app/mer`.
  - Drivers: `/app/forare` as their home; driving mode at `/app/forare/kor/:id`.
- **Active-ride redirect** follows the global invariant. Passengers get a bottom tab bar instead of the ☰ menu.
- Design system in `src/components/ui/`: Button (≥48 px, ≥56 px in driver mode), BottomSheet, StatusPill (real Swedish status words, no enums), Card, Toast, ConfirmDialog, plus a "Stor text" (large text) setting.
- **Network-state layer:**
  - An offline banner ("Ingen anslutning") and "Senast uppdaterad 14:32" (last updated) on ride data.
  - Fetch timeouts, so no endless spinners.
  - **Ride actions are never queued offline**: they show "Kunde inte nå servern — försök igen" (couldn't reach the server — try again).
- **PWA update flow:** `registerType: 'prompt'` and a "Ny version finns [Uppdatera]" (new version available, update) banner.
  - An update applies silently only on a cold start, or when the app is hidden **and** there is no active ride, **no booking draft in progress** and no open form.
  - Otherwise the banner waits for a tap.
  - API changes stay additive and backward compatible, so an old client mid-ride keeps working.
- **Always-available call fallback:** the active-ride screen caches the other party's name and phone. **Ring**/**SMS** still work offline, or when push, search or the backend fail.
- **Booking confirmation sheet:** before the final submit, show **who** (incl. "for Lisa" when booking on someone's behalf), **when** (with weekday), **pickup**, **destination** and **note**, with an edit shortcut on each line.
- History split: passengers see Pågående / Kommande / Tidigare (ongoing / upcoming / past); drivers see Idag / Kommande / Tidigare (today / upcoming / past).
- Real rating sheet (1–5 stars plus an optional comment).

### M3: Search and places (provider-agnostic)
- Backend contract: `PlacesController` → `PlaceSearchService` → `PlaceProvider` interface. **`SlPlaceProvider`** is the first and, for now, only search implementation. Photon, Mapbox or Google can be added later without frontend changes.
- Normalized DTO: `{provider, providerPlaceId, kind: STOP|ADDRESS|POI|FAVORITE|RECENT, name, area, formattedAddress, lat, lon, distanceKm}`.
- `SlPlaceProvider`: Java `HttpClient` (same style as `OsrmRouteProxyService`), 3 s timeout, **no global lock**, Caffeine cache (10 min).
- **Query normalization:** NFC, lowercase, collapse whitespace, strip punctuation, `mc donalds`/`mcdonald's` → `mcdonalds`, and a small synonym table (`donken` → `mcdonalds`). Match on tokens.
- **Ranking** (no hard cutoff):
  - Matches from favorites and recents come first.
  - Then provider results, by match quality times a distance tier: 0–20 km strong boost, 20–50 moderate, 50–100 weak, >100 km pushed down.
  - "Visa fler" (show more) shows the rest.
- **Learned ranking with time decay:** `place_selections(normalized_query, provider, provider_place_id, user_id, score, last_selected_at)`.
  - On each selection: `score = score · 0.5^(Δdays/90) + 1`. At read time the same decay is applied, so the half-life is 90 days.
  - A nightly job prunes rows with an effective score below 0.05.
  - The whole instance is one family, so learning applies family-wide with a stronger boost for the same user. No AI.
- **Search context fallback:** GPS (if accuracy < 1 km) → the chosen pickup → the user's Home place → the configured `app.places.default-center` (Järfälla).
- **Nearest stop (kept simple, no spike):** load SL `/v1/sites` (6,516 stops, verified) into memory at startup and nightly, then do a **linear haversine scan** per request. No spatial index. Pickup then offers "📍 Här (Kallhälls station, 120 m)".
- Map taps still use Nominatim **reverse** only, with a non-blocking rate limiter instead of `Thread.sleep`. Delete `/api/public/geocode/search`.
- Frontend: `components/place-search/`, `hooks/usePlaceSearch.ts`, `api/places.ts`.
  - Search 200 ms after typing stops, from 2 characters, and abort the previous request.
  - Kind icons, a "no results" state, and a ✕ clear that also resets the coordinates.

### M4: Places (favorites + recents) and one-tap trips
- Migrate `saved_places`: add `provider`, `provider_place_id`, `formatted_address`, `kind`, `icon`. Add `PATCH` and reorder endpoints.
- `GET /api/places/recent`: distinct recent pickups and destinations from the user's rides.
- Home screen: **[🏠 Åk hem]** as the big primary button (pickup Här, destination Home, then Nu / Välj tid, i.e. now / pick a time), favorite chips, recents, and the search field.
- If no Home is saved, the button reads "Spara ditt hem" (save your home) and guides the user to set it.
- "⭐ Spara" in search results and after booking. A Places page to rename, reorder and delete.
- Drivers and admins can **create places for a passenger** (grandparents set up "Skolan" for a grandchild).
- **Boka igen** (book again) on past rides in history: fills the draft with the same pickup and destination, then goes to Nu / Välj tid.

### M5: Driver workflow
- Driver home: availability toggle, request cards (passenger, time, from → to, mini map, **Ta resan** / **Kan inte**), today's rides, and "Lämna tillbaka resa" (return the ride) as a first-class action.
- Driving mode:
  - One huge button that changes with the step: Kör nu → Jag är framme → Hämtat upp → Klar.
  - **Navigate** by coordinates: on iOS, a remembered choice of Apple Maps or Google Maps; on Android, Google Maps. Use the `https://www.google.com/maps/dir/?api=1&destination=lat,lon&travelmode=driving` and `https://maps.apple.com/?daddr=lat,lon` links. Labels: "Navigera till Lisa", then "Navigera till Hem".
  - **Ring** (`tel:`) and **SMS** (`sms:` prefilled "Jag är här").
  - The passenger's pickup note and quick messages are shown prominently.

### M6: Notifications
- Backend: `nl.martijndwars:web-push` + BouncyCastle in an `@Async` `PushService`.
  - It listens to state-machine domain events and **deletes subscriptions that return 404/410**.
  - Texts are correct Swedish/English per user.
  - Generate VAPID keys and verify them with `/api/public/push-config`.
- **Preferences:** `notification_prefs(user_id, ride_requests, ride_updates, reminders)`, editable in the "Mer" tab. Drivers in particular can mute requests (availability covers most of this).
- **Hysteresis/once-only:** `ride_notifications_sent(ride_id, kind)` is unique. "5 min bort" (5 min away) fires the first time the ETA crosses ≤5 min; "Farfar är framme" fires on `ARRIVED`; neither repeats.
- **Push is an attention mechanism, not a sync mechanism.** All state is always visible in the app (polling and refresh on focus), so the app works with notifications denied.
- Frontend:
  - `injectManifest` with `src/sw.ts`: a `push` handler (`tag` + `renotify`) and `notificationclick` that focuses or opens `/app/resa/:id`.
  - PNG 192/512 + maskable icons, `apple-touch-icon`, manifest `id`, `start_url: /app`.
- Onboarding for everyone:
  - On iOS when not installed: a guided "install first" step (extend `PwaInstallModal.tsx`).
  - Once installed: a "Slå på notiser" (turn on notifications) card that asks for permission from the tap.
  - Status shown in "Mer", and re-subscribe on app start.

### M7: Live tracking (honest scope)
**Requirement wording:** *"Live tracking is reliable while the driver's driving screen is active. The UI must detect and clearly communicate stale location."* We don't promise background tracking. If that becomes a hard requirement, a Capacitor wrapper is the path, but not now.
- Driver:
  - **Wake Lock** while in driving mode.
  - `watchPosition` throttled to send at most every 10 s or every 50 m.
  - Sends `accuracyMeters`.
  - **Resumes automatically** on reload or reopen when a ride is `EN_ROUTE`/`ARRIVED`/`PICKED_UP`.
  - A banner if location permission is lost.
- Backend:
  - Store only the **latest** position on the ride (`lat`, `lon`, `accuracy_m`, `at`). **No breadcrumb history**, and the position is cleared **1 h after the ride ends**, the same moment the share link expires.
  - ETA via OSRM to the **pickup** before pickup and to the destination after. Recompute only when more than 30 s have passed **and** the driver has moved more than 100 m, or on a state change. Fall back to haversine.
- Passenger active-ride screen:
  - Map with 🚗 (accuracy circle when > 100 m), pickup pin and route.
  - Big status line, "uppdaterad för 20 s sedan" (updated 20 s ago), and a stale warning after 2 min.
  - Driver card (name, photo, car, plate), Ring/SMS, quick messages, Share, Cancel.
  - Polling every 5 s while visible.
- **Share page** `/dela/:token` (no login):
  - "Lisa åker med Farfar" (Lisa is riding with Farfar), map, ETA, last updated.
  - Token: 128-bit random. Expires 1 h after the ride ends. Revocable.
  - Built from configured `app.public-base-url`, not from request host/port.
- Later (not now): the passenger optionally shares their position during pickup.

### M8: Observability and ops hardening
- Prometheus + Grafana on newhomeserver. Scrape `gateway:8090`, allowed only from newhomeserver. Grafana gets a read-only Postgres role.
- Dashboards:
  - Search latency and empty rate.
  - Booking funnel.
  - Time-to-accept and ARRIVED→PICKED_UP wait time.
  - Push opt-in and open rates.
  - Errors.
- **`app_events`**, limited to a fixed allowlist of events:
  - `booking_started`, `booking_created`
  - `search_started`, `search_result_selected`, `search_empty`
  - `ride_accepted`, `ride_cancelled`
  - `push_permission`, `push_opened`
  - `frontend_error`

  Props must never contain addresses or coordinates. Keep 180 days, enforced by a scheduled delete. Sent with `sendBeacon`.
- JSON logs (`logging.structured.format.console=ecs`).
- nginx cache headers: immutable hashed assets, `no-cache` on `sw.js`/`index.html`.

---

## Critical files
- **Backend:**
  - Modified: `service/RideService.java` (logic moves into `RideStateMachine`), `service/PushService.java`, `service/NominatimProxyService.java`, `service/AuthService.java` (pending approval, refresh tokens), `api/DriverController.java`, `config/SecurityConfig.java`, `config/ApiRequestLoggingFilter.java`, `pom.xml`, `application.properties`, `docker-compose.rpi.yml`.
  - New: `service/places/{PlaceSearchService, PlaceProvider, SlPlaceProvider}.java`, `api/PlacesController.java`, `db/migration/V3+`.
  - Removed: `service/RideRealtimeService.java`.
- **Frontend:**
  - Modified: `src/App.tsx` (shrinks gradually), `vite.config.ts` (`prompt` + `injectManifest`), `src/PwaInstallModal.tsx`, `src/locales/{sv,en}.json`, `src/style.css`, `nginx.conf`.
  - New: `src/sw.ts`, `src/pages/*`, `src/components/{ui,place-search}/*`, `src/hooks/*`, `src/api/*`, `public/` (PNG icons).
- **Reuse:** `OsrmRouteProxyService` (ETA), `formatNominatimAddress` (reverse labels), `BookingDraft` + sessionStorage helpers, `Clock24hTimePicker`, the `passengerUserId` booking-on-behalf pattern, the admin page (extend with Pending), `users.phone/photo_url/vehicle_note`, `RideFlowIntegrationTest` (extend).

## Verification: hard machine gates per milestone
These are **gates the master agent must prove** before a milestone counts as done: automated tests, scripted checks against the deployed system, or captured runtime evidence. They are not evidence for a human to approve.

Criteria that need a physical phone (real iOS/Android push on a locked screen, a real drive) get an automated substitute gate, listed below. The real-device check is moved to the **final human device checklist** in the delivery report, which doesn't block milestones.
- **M0A:**
  - A fresh registration can't see open rides or book until approved.
  - Postgres isn't reachable on `:5433` from the LAN.
  - Each default-secret check prints `custom` (after rotation where needed).
  - **If** the JWT secret was rotated, a token signed with the previous secret is rejected. **If** no rotation was needed, the state file records that the existing secret was verified as non-default (never the value itself).
- **M0C:**
  - An access token expires after 1 h and the app refreshes silently.
  - Logout and "log out everywhere" revoke refresh tokens.
  - A reused refresh token is rejected.
- **M0B:**
  - `/actuator/prometheus` responds on 8090 only.
  - The Playwright golden flow passes in CI.
  - Two-way test isolation integration tests pass for every ride endpoint.
  - `scripts/smoke.sh` passes against production with test accounts, and no real user receives an offer or push.
- **M1:**
  - Integration tests cover every transition and guard.
  - Two concurrent accepts give one success and one 409 with the "already taken" reason.
  - Decline by all drivers gives `NO_DRIVER`, and "keep waiting" returns it to `REQUESTED`.
  - A material edit of an `ACCEPTED` ride re-offers the same driver first; a minor edit keeps it `ACCEPTED`.
  - A driver marked away on Saturday gets no offer for a Saturday ride but does for Sunday.
  - Double-tapping "Åk nu" creates one ride.
  - DST tests pass for 2026-10-25.
- **M2:**
  - An active ride redirects to its screen.
  - Offline mode shows the banner and doesn't spin forever, and Ring still works offline.
  - A new SW version mid-ride **or mid-booking** does not reload the app.
- **M3:**
  - p95 search < 300 ms (today: avg 1,181 ms).
  - "McDonalds", "Sveav", "Kista Galleria" and "donken" (after one selection) return useful hits, nearest first.
  - It still works with GPS denied, using the fallback center.
- **M4:** "Åk hem" books in ≤3 taps from a cold start.
- **M5:** the driver completes a full ride, navigation hand-off included, using only the big button and the Navigate/Call buttons.
- **M6 (machine gate: proves our implementation, not the browser vendors' push plumbing):**
  - **Backend:** an integration test against a controlled push endpoint (WireMock) decrypts the Web Push payload with the test subscription's keys.
    - The state-machine events produce "Ny resa", "Accepterad", "5 min bort" (asserted once only) and "Framme".
    - The payload has the correct recipients, including test isolation.
    - A 410 response removes the subscription.
  - **Service worker:** browser automation dispatches synthetic `push` events to the real `sw.ts`. Assert that `showNotification` is called with the expected title, body and tag, and that `notificationclick` focuses or opens `/app/resa/:id`.
  - **Production (best effort, not blocking):** `/api/public/push-config` returns a key. If the environment allows a real test subscription, the sent/failed metrics show a successful send to it.
  - *Final human checklist:* locked iPhone (installed PWA) and Android phone receive the four notifications.
- **M7 (machine gate):**
  - Playwright with two browser contexts (test driver and test passenger) and **emulated geolocation** along a scripted route against production.
  - The passenger screen updates within about 6 s of each position.
  - Reloading the driver context resumes tracking.
  - Stopping the driver context shows "stale" after 2 min.
  - The ETA switches from the pickup to the destination after `PICKED_UP`.
  - Production smoke: the share link is created, works logged out, and returns 410 **after revocation**.
  - The **1 h rules are proven only in deterministic clock-controlled integration tests** (an injected `Clock`): the share token returns 410 after ride end + 1 h, and the position is cleared at ride end + 1 h. No gate waits on real time.
  - *Final human checklist:* one real short drive.
- **M8:** Grafana shows the funnel, search, wait-time and push panels. `app_events` has no addresses or coordinates.

## Process: autonomous agentic implementation

The roadmap is frozen. Execution order is **strictly serial: M0A → M0B → M0C → M1 → M2 → M3 → M4 → M5 → M6 → M7 → M8.**

Parallelism happens **within** a milestone, not across milestones. **The master reserves Flyway migration numbers centrally** before handing out work packages, and only one backend branch integrates or merges at a time. Any other branch is rebased and re-verified after each merge.

### Operating model
Implementation is autonomous. Magnus is the product owner and wants the completed result, not milestone-by-milestone project management.

A persistent **master/orchestrator agent (Opus 5.5, this session)** owns the whole roadmap across `farfartaxi-ws`, `farfartaxi-web` and the Pi deployment. Its jobs:
- Maintain milestone state in a state file (`docs/roadmap-state.md` in `farfartaxi-ws`) so the run survives context compaction.
- Break each milestone into work packages with **explicit file/module ownership** before running parallel writes.
- Delegate implementation and investigation, integrate results, and run every gate.
- Arrange independent review and get findings fixed.
- Deploy, verify after deployment, roll back on failure, then go straight on to the next milestone.

The master acts as tech lead and release manager. It does not implement everything itself.

### Agent allocation (cheapest model that is reliably capable)
- **Sonnet 5.5 workers:** implementation, migrations, frontend components, backend services, integration tests, Docker/nginx, CI fixes. They run in **isolated git worktrees** when working in parallel in the same repo.
- **Haiku 4.5 workers:** reconnaissance, finding usages, log analysis, test enumeration, documentation checks, mechanical refactors, verification runs.
- **Opus 5.5 reviewer:** independent review at **every** milestone. Mandatory deep review at M0A, M1, M3, M6 and M7; lighter at the others.
  - It gets the frozen milestone spec, the diffs from both repos, the migrations, test output and runtime evidence, and **not** the workers' reasoning or self-assessment.
  - It checks for:
    - missing requirements or incorrect interpretation
    - security regressions
    - state-machine holes and concurrency problems
    - migration or rollback problems
    - frontend/backend contract mismatches
    - mobile UX regressions
    - insufficient tests, or mock-only "acceptance" where real verification is required
    - scope creep
  - The master marks each finding blocking or non-blocking. Blocking findings are fixed by workers, then review and verification repeat until clean.

### Milestone loop
1. Re-read the frozen requirements and gates for the milestone.
2. Inspect the current code, migrations, deployment and state file.
3. Break it into work packages with owners.
4. Delegate them, in parallel where write scopes don't overlap.
5. Integrate the results.
6. Verify each repo (`./mvnw verify`, `npm run build`, lint, Vitest), then both repos together (Playwright against a local docker-compose of both images).
7. Independent Opus review of **both repos as one feature**.
8. Fix every blocking finding, then repeat 6–7 until clean.
9. Open PRs in both repos, let CI go green, then squash-merge. The milestone's commits stay scoped and reviewable.
10. GHCR images are built by the existing release workflows. Pin them by sha tag in the Pi compose file, keeping the previous known-good tags as a rollback comment.
11. **Before any deploy with a migration:** `pg_dump` the farfartaxi DB to a timestamped file on the Pi. Migrations follow **expand/contract**: additive first and readable by the previous image, so an image rollback never needs a schema rollback. Destructive steps go in a later milestone.
12. Deploy to the Pi, recreating only the changed containers.
13. Post-deploy checks: health, `scripts/smoke.sh`, the milestone's machine gates against production with test accounts, and a log scan for new ERRORs.
14. **On failure: roll back first** (previous pinned tags; DB restore only if expand/contract was impossible and that was planned in advance). Then diagnose, fix, redeploy and re-verify. **Never leave production degraded while debugging.**
15. Record a short evidence entry in the state file: commits, image tags, gate results, review summary.
16. Continue straight to the next milestone. No "shall I continue?".

A milestone is complete only when all of its gates pass, both locally and against production where applicable.

### Human interaction
No approval between milestones. Escalate to Magnus **only** when:
1. a required product decision isn't defined by the frozen roadmap;
2. a required credential, permission or external access is unavailable;
3. new evidence makes the frozen design materially unsafe, impossible or contradictory, and resolving it would change the agreed scope.

Build or test failures, flaky tests, merge conflicts, migration issues, failed deploys, API mismatches, UI bugs and reviewer findings are **not** escalation conditions. Agents resolve them.

**Authorized autonomous operations** (explicit, so they don't count as "hard to reverse, ask first"):
- Pushing branches and merging PRs on `magpern/farfartaxi-ws` and `magpern/farfartaxi-web`.
- Over SSH to the gateway Pi:
  - Editing `.env` and the compose file in the farfartaxi stack directory.
  - `docker compose pull/up -d` for the farfartaxi services only.
  - `pg_dump`, and `psql` against the farfartaxi DB (schema checks, test-data seeding and cleanup, `ALTER USER` for password rotation).
  - Reading container env and logs for the farfartaxi containers.
  - Rotating the farfartaxi secrets (JWT, DB password, admin password, new VAPID keys).

  Before changing secrets, take a **temporary root-only backup of the existing `.env`** (`cp -p .env .env.bak-<timestamp>`, `chmod 600`) for rollback. Delete it once the milestone verifies.
  - The new admin password exists only in the Pi's `.env` (root-only). The final report says **where to retrieve it**.
  - **Secrets (JWT, DB, admin, VAPID private key, test credentials) are never written to logs, command output, the state file, commits, PR text or the final report.** Checks compare values without printing them.
- Prometheus and Grafana on newhomeserver (M8).
- Nothing else on the Pi (SWAG, Forgejo, WordPress, …) or on newhomeserver may be touched.

### Prerequisites before the run starts (one-time, needs Magnus)
- **Permission rules for this session.** The auto-mode classifier blocked `docker inspect` (container env) and `psql` reads on the Pi during analysis. Without allow-rules covering the operations above, M0A's secret check and every DB step hits escalation condition 2 at once. Magnus adds the rules (or approves them when prompted at the start of the run).
- `gh` authenticated with push/merge rights on both repos (verify with `gh auth status`).

### Scope control
New ideas found during implementation go to the parking lot. They don't expand the active milestone unless they're needed to pass an existing gate or to fix a correctness or security defect.

### Final delivery
One final report, published as an artifact, containing:
- milestones completed
- significant architecture changes
- gate evidence for each milestone
- production image tags and commits
- deviations from the frozen roadmap and why
- known limitations (e.g. no background tracking on locked phones)
- the remaining parking lot
- the short **human device checklist**: real-iPhone and Android push, one real drive, and the location of the new admin password

## Out of scope / parking lot
Recurring rides (only if the family turns out to need them), passenger live-location during pickup, no-show handling (`ARRIVED` keeps the door open), duration/travel-time-aware conflict detection, in-app chat, a native wrapper for background tracking, and additional search providers.

## Decisions (confirmed 2026-10-03)
1. **Account approval:** every new account (password or Google) is **pending until an admin approves it**. No invite links.
2. **Booking on behalf:** any driver or admin can book for any enabled user, as today. No relation model.
3. **Timeouts:**
   - NOW rides: re-push to all drivers at 10 min, `NO_DRIVER` at 20 min.
   - Scheduled rides: driver reminders at T-24h and T-2h if unaccepted, urgent at T-60 min, assigned-driver reminder at T-30 min.
   - All are config values.
4. **Share link:** expires **1 h after the ride ends**, revocable.

## Review reconciliation
- **Accepted:**
  - Security first (M0A).
  - State machine with `ARRIVED` before push/tracking.
  - `ride_offers` model.
  - UX shell before features.
  - Provider abstraction.
  - Distance tiers instead of a hard cutoff.
  - GPS fallback chain.
  - Normalization and learned ranking.
  - Places model with provider ids.
  - Åk hem.
  - Pickup notes and quick messages.
  - Call/SMS both ways.
  - Driver card.
  - Availability.
  - NOW vs SCHEDULED.
  - Edit, return and cancel rules.
  - Idempotency.
  - Conflict warning.
  - Offline UX.
  - PWA update prompt.
  - Navigation deep links.
  - Accuracy and location retention.
  - Share page.
  - Smaller M0B.
  - Gradual refactor.
  - Event allowlist.
  - Push as attention, not sync.
  - Notification preferences.
  - Hysteresis.
  - Explicit timezone/DST.
  - History split.
- **Corrected:**
  - Family/user admin is **not** "almost entirely missing". The admin page already handles roles, enable/disable, delete, password reset and phone/vehicle edits. The real gaps are the **open-registration hole** (now M0A) and the booking-on-behalf rules (open decision 2).
  - "Unaccept" already exists in the backend and UI; the work is UX, re-offering and notifications.
  - The "nearest stop" spike is mostly de-risked: SL `/v1/sites` returns all 6,516 stops with coordinates and needs no key.
- **Adjusted:**
  - Share-link expiry shortened to ride end + 1 h (a child's live location). Confirmed.
  - Passenger location sharing and no-show handling are deferred; `ARRIVED` keeps the door open.
