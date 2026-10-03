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
| V3 | M0A | user approval (pending accounts) |

## Milestones
| Milestone | Status |
|---|---|
| M0A Security containment | in progress |
| M0B Baseline + safety net | pending |
| M0C Auth modernization | pending |
| M1 Core ride model | pending |
| M2 Mobile UX shell | pending |
| M3 Search and places | pending |
| M4 Places + one-tap trips | pending |
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
- Code part: pending (approval flow, role checks, repo secret defaults).

## Deviations
- None yet.
