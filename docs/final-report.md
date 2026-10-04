# Farfartaxi rebuild: final delivery report

Roadmap: [roadmap.md](roadmap.md) (rev 4.1, frozen). Evidence log: [roadmap-state.md](roadmap-state.md).
Period: 2026-10-03 16:46 to 2026-10-04 ~12:00 (about 19 h wall-clock, roughly 10 h of active work; two long pauses: token limit and a power outage).

## 1. Result
All milestones M0A to M8 are implemented, independently reviewed, and live in production at https://farfartaxi.pernemark.se, followed by one post-review hardening release. Production runs **v1.13.1**.

## 2. Milestones
| | Release | Summary |
|---|---|---|
| M0A Security containment | v1.3.0 | New accounts wait for admin approval; role guards; secrets rotated; DB and backend ports closed; images pinned |
| M0B Safety net | v1.4.0 | Isolated test accounts, ride history log, metrics, e2e + smoke, CI |
| M0C Auth | v1.5.0 | 1 h access token + rotating refresh cookie, reuse detection, logout everywhere |
| M1 Ride model | v1.6.0 | State machine incl. ARRIVED, per-driver offers, NOW/SCHEDULED, edit/return/cancel rules, idempotent booking, messages |
| M2 Mobile shell | v1.7.0 | Tabs, role landing, active ride is the home screen, offline handling, update guards |
| M3 Search | v1.8.0 | SL-backed search (stops, addresses, POIs), learned ranking, nearest stop |
| M4 Places | v1.9.0 | Favorites, recents, one-tap "Åk hem" (3 taps), "Boka igen" |
| M5 Driver | v1.10.0 | Big step button, navigation hand-off, call/SMS, mini maps |
| M6 Notifications | v1.11.0, v1.11.1 | Real Web Push, preferences, app icon, install guide |
| M7 Live tracking | v1.12.0 | Live map, ETA, stale warning, share links, 1 h retention |
| M8 Observability | v1.13.0 | Prometheus + Grafana, privacy-safe telemetry, protected metrics, JSON logs |
| Post-review hardening | v1.13.1 | See section 6 |

## 3. Production state (v1.13.1)
- Backend `ghcr.io/magpern/farfartaxi-backend:v1.13.1@sha256:7a7550e83bbb749148dda53b3450a1ce61ed29c52c2f6d83e0ffe0eda45c3b55` (commit `3aacdfb`)
- Frontend `ghcr.io/magpern/farfartaxi-frontend:v1.13.1@sha256:8e194b4b8a70c93918181636551630a4c3467b64103ae3054371cc2377b61622` (commit `3c2f1ef`)
- Database: Flyway V1 to V12 applied; Postgres 17 pinned by digest and not published.
- Compose: `/home/magpern/docker/farfartaxi/` on the gateway Pi; previous image pins are kept as rollback references at the top of the compose file.
- Observability: `/home/magpern/farfartaxi-observability/` on newhomeserver; Grafana at `http://192.168.1.222:3030`; Prometheus scrapes `192.168.1.151:8090` with basic auth.

## 4. Architecture changes
Ride state machine with optimistic locking; per-driver offers; timer service with once-only notification markers; provider-abstracted place search; Web Push with VAPID and an endpoint allowlist; unversioned guarded position updates (no contention with state changes); refresh-token families; server-side test/real world isolation (404 across worlds); Micrometer metrics on a protected management port; allowlisted telemetry with PII/coordinate rejection; ECS JSON logs.

## 5. Security improvements
- The production JWT secret matched a value published in the repo (forgeable tokens), as did the database and admin passwords: all replaced.
- Self-registered accounts no longer see rides or children's addresses until approved.
- Account-takeover paths found in review and closed: Google pre-hijack, token reuse after credential change, same-second login race, lost update.
- Postgres and the backend port are no longer reachable from the LAN; metrics need a password.
- Anonymous geocode and route proxies removed; auth endpoints rate limited; driver location deleted 1 h after the ride; share links expire.

## 6. Reviews
Every milestone had an independent Opus review that saw the diffs and evidence, not the workers' reasoning. Most found blocking defects that were fixed before release, for example: forgeable tokens, accept-without-offer, the retention job never running, a returned ride showing the previous driver's position, raw error text reaching telemetry, and a GPS update racing the driver's step button. An external reviewer's final pass led to the post-review hardening release: raw `frontend_error` text is never stored (V12 scrubs old rows), the anonymous route proxy is replaced by authenticated and share-scoped endpoints, telemetry quota counts valid events only, and auth endpoints are rate limited.

## 7. Verification evidence
Each milestone passed backend integration tests, frontend unit tests, CI (including a Postgres-backed e2e job), a local full-stack Playwright run, a migration check against a copy of production data, and a post-deploy run of the smoke script and Playwright suite against production with isolated test identities. Real rides were unchanged (32 before and after every run). Final v1.13.1: 53 Playwright tests passed, smoke passed, no ERROR log lines.

## 8. Deviations from the frozen roadmap
- Grafana reads Prometheus only (the database port stays closed).
- Observability deploys to `/home/magpern/farfartaxi-observability/` (no root for `/srv`).
- A real admin may delete test rides (only exception to world isolation).
- M7 development overlapped M6's last production gate, which waited on Cloudflare's cache; nothing merged out of order.
- An unrequested but necessary hotfix (v1.11.1): Cloudflare cached the service worker for 4 h.

## 9. Known limitations
- Live tracking works only while the driver's driving screen is open; the app shows a clear stale warning otherwise.
- Push, share and tracking were verified with browser automation, not on real phones.
- A lost refresh response on a flaky network can log that device out.
- Someone who knows a family member's email can block their password login for 15 minutes (Google sign-in still works).
- The client-IP header is trusted: a device on the home LAN could reach the frontend port directly and spoof it.
- `release-docker.yml` has not yet run on the upgraded Docker actions.

## 10. Recommendations (outside the frozen scope)
1. **Schedule database backups.** Only manual pre-deploy dumps exist (`backups/` on the Pi, 14 files). There is no cron job and no off-device copy.
2. Cloudflare: add a cache rule or purge access so future cache issues can be fixed without waiting.
3. Dependabot for GitHub Actions; `ubuntu-latest` migrates to Ubuntu 26 on 2026-10-19 (CI should be checked then).
4. A scheduled restart test of the Pi (the outage showed the stack now recovers on its own after the fixes in v1.13.1).

## 11. Parking lot (not built, per scope)
Recurring rides, passenger live location during pickup, no-show handling, travel-time-aware conflict detection, in-app chat, native wrapper for background tracking, additional search providers, a render error boundary hook (`trackRenderError` exists).

## 12. Human device checklist
1. Installed iPhone app: locked screen receives the four notifications (new ride / accepted / "5 min bort" / "framme").
2. Android phone: the same four notifications.
3. One short real drive to check live tracking (the passenger sees the car move; stale warning when the driver's screen is off).
4. Sharing a ride from an iPhone (Share sheet and copy link) and opening the link logged out.
5. Retrieval: the new admin password is in `/home/magpern/docker/farfartaxi/.env` on the gateway Pi (`FARFARTAXI_ADMIN_PASSWORD`); the Grafana admin password is in `/home/magpern/farfartaxi-observability/.env`.
