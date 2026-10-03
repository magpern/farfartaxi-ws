# M7 contract (live tracking)

Implements roadmap "M7: Live tracking (honest scope)" and the rev 4.1 M7 machine gate. *(clarification)* marks master decisions.

**Requirement wording:** live tracking is reliable while the driver's driving screen is active; the UI detects and clearly communicates stale location. No background-tracking promise.

## Backend
- Flyway **V10**: `rides.last_location_accuracy_m DOUBLE PRECISION`, `rides.eta_target VARCHAR(16)` (`PICKUP|DESTINATION`), `rides.eta_computed_at TIMESTAMPTZ`, `rides.eta_lat/eta_lon DOUBLE PRECISION` (position used for the last ETA computation). Expand-only.
- `POST /api/driver/rides/{id}/location {lat, lon, accuracy}` (exists): store latest position + accuracy + `last_location_at` (Clock). **No breadcrumb history.**
- **ETA**: target = pickup while EN_ROUTE/ARRIVED, destination while PICKED_UP. Recompute via OSRM (short-timeout path from M1, ≤3 s) only when (> 30 s since last ETA AND moved > 100 m since `eta_lat/lon`) OR the status changed OR no ETA yet; otherwise keep the previous ETA. OSRM failure → haversine at configured km/h. ETA_5MIN push logic (M6) uses the stored ETA.
- **RideResponse** additions: `lastLocationAccuracyM`, `etaTarget`, `locationStale` (true when EN_ROUTE..PICKED_UP and `last_location_at` older than 2 min or missing), existing `lastDriverLat/Lon`, `lastLocationAt`, `etaMinutes`.
- **Retention**: scheduled job clears `last_driver_lat/lon`, accuracy, `eta_lat/lon` **1 h after the ride ended** (COMPLETED/CANCELLED; ended = `completed_at` or the cancel event time) — same moment the share link expires.
- **Share link**: `POST /api/rides/{id}/share` → `{token, url, expiresAt}`; token = 128-bit random (base64url); `url = app.public-base-url + "/dela/" + token` (`app.public-base-url` default `https://farfartaxi.pernemark.se`, never derived from request host/port) *(clarification)*. Allowed for the passenger while the ride is REQUESTED..PICKED_UP. Expiry: while active the link stays valid; it expires **1 h after the ride ends**. `DELETE /api/rides/{id}/share` revokes (410 afterwards).
- Public `GET /api/public/share/{token}` (no auth) → `{passengerFirstName, driverFirstName, status, statusLabelKey, scheduledAt, pickup:{lat,lon,label}, destination:{lat,lon,label}, driver:{lat,lon,accuracyM,updatedAt}|null, etaMinutes, etaTarget, locationStale}`; 404 unknown, 410 expired/revoked. **No phones, no pickup note, no full names, no ids** *(clarification: first names only, needed for "Lisa åker med Farfar")*. Rate limited (simple per-IP limiter, 60/min). Old `/api/rides/share/{token}` JSON endpoint removed.
- World isolation: share links of test rides work anonymously (needed for e2e) but never expose real data.

## Frontend
- **Passenger active-ride screen**: Leaflet map in the M2 `live-map` slot: 🚗 marker moving smoothly between updates (animate over ~1 s), accuracy circle when > 100 m, pickup pin (and destination pin after pickup), route line pickup→destination (from `/api/public/route/driving`, fetched once per ride). Big status line + ETA ("Farfar är 6 min bort" / to destination after pickup "Framme om ca 12 min"), "uppdaterad för 20 s sedan" (relative, ticking), **stale warning after 2 min** ("Farfars position har inte uppdaterats på X min — han kanske har skärmen avstängd"). Polling every 5 s while visible (exists).
- **Driver driving mode**: request **Screen Wake Lock** while in driving mode (re-acquire on visibilitychange; release on leave/complete); show a small "Skärmen hålls tänd" indicator; if unsupported, show a hint "Håll skärmen tänd medan du kör". GPS sending (exists: ≥10 s or ≥50 m, first fix immediately) sends `accuracy`; **resumes automatically** after reload/reopen when a ride is EN_ROUTE/ARRIVED/PICKED_UP (exists via shell — keep); a red banner if location permission is denied/lost ("Platsdelning är av — farfar syns inte på kartan") with a button to retry.
- **Share**: "Dela resan" button on the active-ride screen (passenger) → native share sheet (`navigator.share`) with the URL, fallback copy-to-clipboard; "Sluta dela" revokes.
- **Public share page** `/dela/:token` (no login, no tab bar, works logged out): "Lisa åker med Farfar", status line, map with car + pickup/destination, ETA, "Senast uppdaterad", stale warning, auto-refresh 10 s; 410 → friendly "Länken har gått ut", 404 → "Länken finns inte".

## Machine gate (rev 4.1)
- Playwright with two browser contexts (test driver + test passenger) and **emulated geolocation** along a scripted route against production: the passenger screen shows the moved car within ~6 s of each position; reloading the driver context resumes tracking; stopping the driver context → "stale" warning after 2 min; ETA target switches pickup → destination after PICKED_UP; share link works logged out and returns 410 after revocation.
- Deterministic clock-controlled integration tests: share token 410 after ride end + 1 h; position cleared at ride end + 1 h; ETA recompute rule (30 s AND 100 m) and haversine fallback.
- Final human checklist: one real short drive.
