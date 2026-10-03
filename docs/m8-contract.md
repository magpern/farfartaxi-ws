# M8 contract (observability and ops hardening)

Implements roadmap "M8: Observability and ops hardening". *(clarification)* / *(deviation)* mark master decisions.

## Topology
- newhomeserver (192.168.1.222, this machine) runs Prometheus + Grafana in `/srv/farfartaxi-observability/` (compose + provisioning from the repo directory `ops/observability/`). Grafana on `192.168.1.222:3030` (LAN only), admin password generated into `/srv/farfartaxi-observability/.env` (chmod 600, never printed). Prometheus internal only (no published port, or bound to 127.0.0.1), retention 30 d.
- Gateway Pi: backend management port 8090 published **only on the Pi's LAN address** `192.168.1.151:8090` *(clarification: no firewall changes; instead)* protected by **HTTP basic auth** for `/actuator/prometheus` (user `prometheus`, password from env `MANAGEMENT_PROMETHEUS_PASSWORD`, generated on both hosts' .env; health stays unauthenticated on the management port). If the password env is absent, prometheus endpoint is denied (fail closed).
- *(deviation)* Grafana reads **Prometheus only**, not Postgres: Postgres was deliberately unpublished in M0A; all dashboard data (funnel, wait times, push) is exported as Micrometer metrics instead of SQL panels. Equivalent outcome, no DB exposure.

## Backend metrics (additions; all tagged `world=real|test`, dashboards filter `world="real"`)
- `farfartaxi.ride.time_to_accept` timer (REQUESTED→ACCEPTED, per acceptance), `farfartaxi.ride.pickup_wait` timer (ARRIVED→PICKED_UP), `farfartaxi.ride.no_driver` counter, existing `farfartaxi.ride.transitions{type,world}`.
- `farfartaxi.push.subscriptions` gauge (active subscriptions, world tag), existing `farfartaxi.push{outcome,kind}`.
- `farfartaxi.app_events{name,world}` counter for every accepted telemetry event (funnel).
- Existing: places search timers (p95), http_server_requests, JVM, Hikari.

## app_events (Flyway V11)
- `app_events(id BIGSERIAL, user_id BIGINT NULL REFERENCES users ON DELETE SET NULL, is_test BOOLEAN NOT NULL DEFAULT FALSE, session_id VARCHAR(64), name VARCHAR(40) NOT NULL, props TEXT (JSON, ≤ 1 KB), client_ts TIMESTAMPTZ, created_at TIMESTAMPTZ NOT NULL DEFAULT NOW())`, index `(name, created_at)`.
- `POST /api/telemetry/events` (authenticated; pending users allowed? no — approved users only) body `{sessionId, events:[{name, props, ts}]}` ≤ 50 events, request ≤ 32 KB. **Name allowlist**: `booking_started, booking_created, search_started, search_result_selected, search_empty, ride_accepted, ride_cancelled, push_permission, push_opened, frontend_error`. **Props allowlist per name** (keys + scalar types only): e.g. `search_*`: `{queryLength:int, provider:string, kind:string, rank:int, latencyMs:int}`; `booking_*`: `{kind:"NOW"|"SCHEDULED", source:"home"|"search"|"favorite"|"recent"|"rebook"}`; `push_permission`: `{state:"granted"|"denied"|"default"}`; `frontend_error`: `{message:string≤200 (URLs stripped of query/fragment), source:string≤120, line:int}`. Any other key → dropped; any value that looks like an address/coordinate (keys containing lat/lon/address/name/email/phone, or numbers with ≥4 decimals) → rejected. Unknown names → dropped (not 400) and counted.
- Retention: nightly job deletes rows older than 180 days.
- Rate limit: per user 600 events/hour (excess dropped).

## Frontend telemetry
- `src/lib/telemetry.ts`: in-memory queue, flush every 30 s and on `visibilitychange→hidden` via `navigator.sendBeacon` (fallback fetch keepalive with auth header? sendBeacon cannot send Authorization — *(clarification)*: use `fetch(..., {keepalive:true})` with the bearer token); never queue offline beyond 200 events; never blocks UI; no PII.
- Events wired at the defined points; `window.onerror`/`unhandledrejection` → `frontend_error` (deduped, max 10/session).

## Logs
- JSON structured console logs (`logging.structured.format.console=ecs`), MDC requestId/userId kept; no PII.

## Ops
- nginx cache headers: done in v1.11.1 (sw.js/app shell no-cache; hashed assets immutable).
- Production compose: management port publish `192.168.1.151:8090:8090`, `MANAGEMENT_PROMETHEUS_PASSWORD` env.

## Gate
- Grafana (provisioned dashboard "Farfartaxi") shows panels with data: booking funnel (app_events counters), search latency p95 + empty rate, time-to-accept + pickup wait, push opt-in (subscriptions) + push outcomes, errors (5xx rate, frontend_error count). Verified via Grafana API (`/api/dashboards/uid/...` exists; `/api/ds/query` returns non-empty series for each panel query after running the production smoke/e2e).
- DB check on production: `app_events` contains no props keys outside the allowlist and no values matching coordinate/address patterns.
- `/actuator/prometheus` on 192.168.1.151:8090 requires auth (401 without, 200 with); not reachable via the public URL.
