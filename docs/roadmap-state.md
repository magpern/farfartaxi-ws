# Farfartaxi roadmap execution state

Canonical frozen roadmap: [roadmap.md](roadmap.md) (rev 4.1). This file holds non-sensitive execution state only. **Never put secrets here.**

## Production baseline (2026-10-03, before M0A)
- Public URL: https://farfartaxi.pernemark.se (SWAG on gateway Pi -> frontend :5124 -> backend :8080 on compose network)
- Stack dir on Pi: `/home/magpern/docker/farfartaxi` (docker-compose.yml + .env)
- Running: release `v1.2.0` (ws commit `c705272`)
  - backend  `ghcr.io/magpern/farfartaxi-backend@sha256:432edd39bc6aa26277341f8e27861e019fa72f711a3013a04565d338c6e47820`
  - frontend `ghcr.io/magpern/farfartaxi-frontend@sha256:21f4f9b593855cc037451c76136c3dc3ae1508f7a9ee8b04dcc6cb1a6c48f8dd`
- Flyway: V1 init, V2 google oauth
- Data: 2 ADMIN (they drive; no DRIVER-role accounts), 4 USER, 32 rides (all COMPLETED), 0 push subscriptions, 0 saved places
- Release mechanism: pushing tag `v*` on farfartaxi-ws builds and pushes both images (frontend from same tag in farfartaxi-web, else main), ~8 min.
- Admin bootstrap (`AdminBootstrapConfig`) re-applies the .env admin password on every start.

## Flyway migration reservations (owned by master)
| Version | Milestone | Purpose |
|---|---|---|
| V3 | M0A | users.approved, credentials_changed_at, version (deployed) |
| V4 | M0B | test identities (users.is_test, rides.is_test) (deployed) |
| V5 | M0C | refresh_tokens (deployed) |
| V6 | M1 | ride model: statuses, kind, offers, availability, messages, notifications_sent, idempotency, rides.version (deployed) |
| V7 | M3 | place_selections (learned ranking) (deployed) |
| V8 | M4 | saved_places: provider, provider_place_id, formatted_address, kind, icon |

## Milestones
| Milestone | Status |
|---|---|
| M0A Security containment | **done** (v1.3.0, deployed 2026-10-03 17:13) |

| M0B Baseline + safety net | **done** (v1.4.0, deployed 2026-10-03 ~17:55) |
| M0C Auth modernization | **done** (v1.5.0, deployed 2026-10-03 ~18:20) |
| M1 Core ride model | **done** (v1.6.0, deployed 2026-10-03 ~19:15) |
| M2 Mobile UX shell | **done** (v1.7.0, deployed 2026-10-03 ~20:40) |
| M3 Search and places | **done** (v1.8.0, deployed 2026-10-03 ~22:30) |
| M4 Places + one-tap trips | in progress |
| M5 Driver workflow | pending |
| M6 Notifications | pending |
| M7 Live tracking | pending |
| M8 Observability + ops | pending |

## M0A evidence
- Ops containment done on Pi (2026-10-03 16:42):
  - Pre-change backups: `.env.bak-20261003T164211` (chmod 600, temporary), `docker-compose.yml.bak-20261003T164211`, `backups/farfartaxi-20261003T164211.dump`.
  - Secret check before: JWT=custom (not rotated, per conditional gate), DB password=DEFAULT, admin password=DEFAULT, VAPID empty.
  - Rotated DB password (ALTER USER + .env) and admin password (.env, applied by bootstrap). After: DB=custom, ADMIN=custom, JWT=custom.
  - Postgres no longer published (5433 closed). Backend bound to 127.0.0.1:8081 only. Compose has no secret defaults (`:?required`).
  - Images pinned by tag+digest (v1.2.0) with rollback refs in compose comments.
  - Verified: admin login with new password 200, with old default 401; frontend 200; no new ERROR lines.
- **Incident (found by M0A review, 2026-10-03 16:51):** the initial JWT check compared only against `replace-this-...`. Re-check against all 4 JWT secret values ever published in the repo found that the production JWT secret **matched a published value** (forgeable tokens). Rotated immediately (64-char random, .env backup `.env.bak-20261003T165108`). Note: `docker compose up -d backend` did NOT recreate the container on an .env-only change; `--force-recreate` was required. Verified: container has new secret, token signed with old secret -> 401, new token -> 200, frontend 200. All users must log in once again.
  - Lesson applied for the rest of the run: secret checks compare against every value ever published; after secret changes always `--force-recreate` and verify inside the container.
- Code: ws PR #1 -> `44630b4`, web PR #1 -> `11c5b28`, release tag `v1.3.0` (both repos).
- Reviews (Opus, independent): #1 NOT CLEAN (working placeholders in .env.example; JWT evidence gap) -> fixed; #2 NOT CLEAN (pre-link token could re-set password) -> `credentials_changed_at`; #3 NOT CLEAN (same-second race) -> exact `cv` claim; #4 NOT CLEAN (lost update vs Google link) -> `@Version` optimistic locking; final master check CLEAN.
- Migration gate: V3 applied + validated against a restored copy of the production dump on the Pi (disposable containers), fresh registration 403 on open rides/booking.
- Deployed images: backend `v1.3.0@sha256:dc8f3e63d07f6d12ed2e31ec55d6fa8ef6744e49a76a0e51dd7ab996eaee6ef7`, frontend `v1.3.0@sha256:c13d94f6a68b78574e3bbf687db009c96575b5a00ae0898aa9317d5ff3aeb021`, postgres `17@sha256:28ed727a...`. Pre-deploy dump `backups/farfartaxi-*-pre-v1.3.0.dump`. Rollback refs v1.2.0 in prod compose (with pending-user rollback warning).
- Production gates (public URL): admin login ok, old default admin password 401; fresh registration approved=false, 403 PENDING_APPROVAL on /api/driver/rides/open, /api/rides/my, /api/saved-places, POST /api/rides; /me 200; after approval USER 403 on open rides, 200 on own rides; test user deleted; 5433 and 8081 closed from LAN; frontend 200; 0 ERROR log lines. Temporary .env backups deleted.
- Parking lot additions from review: open unauthenticated proxies `/api/public/geocode/**` and `/api/public/route/**` (geocode search is removed in M3); no rate limit on registration.

## M0B evidence
- Work packages: WP1b test identities + RideAccessPolicy + ride_events + cleanup (Sonnet), WP1a actuator/MDC/metrics/version (Sonnet, worktree), WP2 web ESLint/Vitest/CI (Sonnet), WP3 e2e/smoke/CI (Sonnet, worktree); master integration (world-tagged transition metric, framework errors -> real status).
- Review (Opus): CLEAN; non-blocking fixes applied (test accounts cannot become/act as admin, smoke.sh keeps secrets off argv, push recipient world test, no emails in push logs, test accounts disabled when not configured).
- PRs: ws #2 (`c4eb85f`), web #2, ws #3 (e2e Google-login fix, `fc6aa69`). Release `v1.4.0`.
- Deployed: backend `v1.4.0@sha256:130475f68f8f560034955c6bf523bf2570108363a4285bb64a3a55999c81ea41`, frontend `v1.4.0@sha256:bdfe129e9684c93d5c46a79c595c6961d27b38c52e4106b05437dfc8ae44b88c`; pre-deploy dump `backups/*-pre-v1.4.0.dump`; Flyway V4 applied.
- Test credentials generated randomly on the Pi into `.env` (chmod 600); never printed.
- Gates: CI backend/frontend/e2e green; 38 backend tests incl. two-way isolation for every ride endpoint; `/actuator/prometheus` reachable only on the compose network (:8090), LAN 8090 closed, public `/actuator/*` serves only the SPA HTML; `X-Request-Id` echoed; `/api/public/version` = v1.4.0/c4eb85f; `scripts/smoke.sh` PASS against production; Playwright golden flow 2/2 PASS against production (after login-page fix); real rides unchanged (32, same max updated_at) before/after; transitions metric shows only `world="test"` increments; 0 push log lines; 0 ERROR lines.

## M0C evidence
- Work packages: backend refresh tokens (Sonnet), frontend silent refresh + admin logout-everywhere + Playwright spec (Sonnet). CI now builds the same-named farfartaxi-web branch for cross-repo PRs.
- Review (Opus): NOT CLEAN (B1 cleanup deleted rotated rows -> broke reuse detection after 7 days; B2 hung refresh -> blank boot screen) -> fixed, plus logout revokes whole family + client generation guard, refresh rejects tokens older than credentials_changed_at, Clock injection.
- PRs: ws #4 (`48b5461`), web #3. Release `v1.5.0`. Deployed backend `v1.5.0@sha256:149ec43b...`, frontend `v1.5.0@sha256:29f5810f...`; Flyway V5; pre-deploy dump taken.
- Gates: 54 backend tests (rotation, race grace, family revocation incl. after 10 days, concurrent refresh, logout, logout-everywhere kills access+refresh, disabled user, 60-min lifetime); 32 frontend unit tests; production: login Set-Cookie `ft_refresh; Path=/api/auth; Max-Age=7776000; Secure; HttpOnly; SameSite=Strict`, body keys only token/user, access lifetime 3600 s; Playwright 4/4 on production incl. silent refresh after access-token expiry and logout revocation; smoke PASS; real rides unchanged; 0 ERROR lines.
- Known trade-off (documented): if a refresh response is lost on a flaky network and the client retries >30 s later with the old cookie, reuse detection logs that device out.

## M1 evidence
- Contract: `docs/m1-contract.md` (master clarifications: ADMIN counts as driver; scheduled rides still REQUESTED at their time -> NO_DRIVER; `ride_notifications_sent` created in M1 for once-only timers).
- Work packages: backend ride model (Sonnet), frontend adaptation incl. messages view (Sonnet), e2e/smoke update + double-tap spec (Sonnet).
- Reviews (Opus): deep review NOT CLEAN (B1 accept without offer; B2 keep-waiting re-offered withdrawn/away drivers) + 16 non-blocking -> fixed (scheduler row lock, conditional markViewed, away rule everywhere, timer reset on edit/return, accept race mapping, V6 offer backfill + corrected rollback SQL, kind-less near-now = NOW, 3 s OSRM budget, stale NO_DRIVER to history, messages current participants only, offerPriority, driver deletion, zero-offer -> NO_DRIVER, frontend attribution/edit/GPS throttle/error text); re-review CLEAN; final small fixes (NOW-ride edit, accepted-ride offer backfill, deletion -> NO_DRIVER check, repush guard).
- Gates: 104 backend tests (all transitions/guards, concurrent accepts, decline-all/keep-waiting/ALL_DECLINED, NOW 10/20-min timers, scheduled-time NO_DRIVER, once-only reminders, material vs minor edit, away Saturday/Sunday, idempotency incl. concurrent same key, proximity, cancel rules, messages, availableActions, phone privacy, V6 data migration); 66 frontend tests incl. DST 2026-10-25 / 2027-03-28; local e2e smoke x2 + Playwright 6/6; V6 migration gate on fresh production dump (59 ms, validate OK, 38 rides intact); CI green.
- PRs ws #5 (`bb44c07`), web #4; release `v1.6.0`: backend `sha256:02540603...`, frontend `sha256:bd7fce1f...`; deployed with no in-flight rides; production smoke PASS (new flow), Playwright 6/6 on production, real rides unchanged, 0 ERROR lines.

## M2 evidence
- Work packages: shell (Sonnet), network/PWA update layer (Sonnet, worktree), backend active/history endpoints (Sonnet), integration + e2e gates + screenshots (Sonnet). Master UX check of screenshots: driver home buried requests below availability -> fixed (big toggle, requests first, away dates collapsed).
- Review (Opus): NOT CLEAN (B1 far-future booking hijacked home and blocked booking; B2 sheets stole focus every poll) -> fixed with 8 non-blocking items (ride cache scoped per user + TTL + cleared on logout, update guard counts cached pointer and in-flight mutations, 502-504 = unreachable, tap targets, confirmation shortcuts, visibility-gated polling, feedbackGiven). Master fix: losing concurrent accepts always RIDE_TAKEN (committed-status re-read), test tightened, 3 green runs.
- Gates: 114 backend tests, 145 frontend tests (redirect logic, tab bars, offline Ring/SMS from cache, confirmation sheet, rating, history grouping, update policy incl. mid-ride/mid-booking no reload); Playwright 12/12 local and on production (active-ride redirect passenger/driver, offline banner + content + Ring, offline mutation not queued); smoke PASS on production; real rides unchanged; 0 ERROR lines.
- PRs ws #6, web #5; release `v1.7.0` backend `sha256:bc632bde...`, frontend `sha256:ee317003...`.

## M3 evidence
- Contract `docs/m3-contract.md` (clarifications: places endpoints authenticated; public geocode endpoints removed; reverse moved to `/api/places/reverse`; route proxy kept; test-world selections isolated).
- Work packages: backend places (Sonnet), frontend place search (Sonnet) + master-caught fix (GPS pickup label must never be "Min position" for the driver), e2e (Sonnet).
- Review (Opus): CLEAN; non-blocking fixes applied (512-char provider ids, reverse upstream failure -> 204, language-independent GPS pickup flag + payload guard, gid ids, sites load retry, GPS accuracy gate, stop labels, map-tap fallback label, maxLength, busy state, dedupe permutations, selection upsert race, test selection cleanup, text_pattern_ops index).
- Gates: 137 backend tests, 172 frontend tests, local e2e 26/26 + smoke x2; CI green; production: smoke PASS (incl. live SL search, old geocode 404), Playwright 24/26 in full run on the Pi (2 pixel-7 timeouts under Pi load) and the two specs 8/8 when rerun -> prod gate now uses --retries=1; live search quality from Järfälla GPS: "McDonalds" -> McDonalds — Järfälla (hållplats) 5.15 km, "Sveav" -> nearest Sveavägen first, "Kista Galleria" -> POI, "Jakobsbergs centrum" 0.11 km, "S:t Eriksplan" ok, "donken" -> McDonalds; nearest stop Jakobsbergs centrum 114 m; latency 69 searches: endpoint avg 34 ms, max 166 ms (p95 < 300 ms; before: avg 1181 ms, max 4376 ms); real rides unchanged; 0 ERROR lines.
- Release `v1.8.0` backend `sha256:7a9f4a9d...`, frontend `sha256:79989427...`; Flyway V7.

## Deviations
- M0B: a real admin may delete test rides (`DELETE /api/admin/rides/{id}`); the only exception to the isolation invariant, accepted (cleanup convenience, harmless).
- None yet.
