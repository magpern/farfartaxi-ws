# M1 API contract (core ride model)

Implements roadmap "M1: Core ride model", "Ride state machine", "Driver offer model", "Ride kinds". This file is the fixed contract between backend and frontend for M1. Clarifications made by the master are marked *(clarification)*.

## Drivers
"Drivers" = users with role DRIVER **or ADMIN** in the same test/real world (production admins drive) *(clarification)*. All M0B world isolation rules apply to every new table and endpoint.

## Statuses
`REQUESTED, ACCEPTED, EN_ROUTE, ARRIVED, PICKED_UP, COMPLETED, CANCELLED, NO_DRIVER`
Data migration in V6: `PENDING_OPEN→REQUESTED`, `IN_PROGRESS→EN_ROUTE`, `REJECTED→NO_DRIVER`.
Transitions (single `RideStateMachine`, conditional updates / `@Version` on rides; every transition writes `ride_events`):
- `REQUESTED --accept(driver)--> ACCEPTED`
- `ACCEPTED --start(driver)--> EN_ROUTE --arrive--> ARRIVED --pickup--> PICKED_UP --complete--> COMPLETED`
- `ACCEPTED|EN_ROUTE --return(driver, reason; reason required from EN_ROUTE)--> REQUESTED` (driver's offer WITHDRAWN; others re-offered, DECLINED stay declined)
- `ACCEPTED --material edit(passenger)--> REQUESTED` (same driver's offer reset to OFFERED and flagged `priority`, others re-offered)
- `REQUESTED --NOW timeout 20 min | all offers DECLINED/EXPIRED | scheduled time reached while still REQUESTED--> NO_DRIVER` *(clarification: scheduled rides still unaccepted at their scheduled time become NO_DRIVER)*
- `NO_DRIVER --keep-waiting(passenger)--> REQUESTED` (re-offers drivers whose offer EXPIRED; not allowed if every driver DECLINED → 409 `ALL_DECLINED`; NOW rides get a fresh 20-min window from the keep-waiting moment)
- `NO_DRIVER --edit time--> REQUESTED` (re-offers everyone incl. decliners)
- cancel (passenger) from `REQUESTED, NO_DRIVER, ACCEPTED, EN_ROUTE, ARRIVED` → `CANCELLED`; from `EN_ROUTE|ARRIVED` requires body `{"confirm":true}` else 409 `CONFIRM_REQUIRED`. Not from `PICKED_UP`.

## Ride kinds
`kind: NOW | SCHEDULED`. Booking with `kind=NOW` ignores `scheduledAt` and uses server now (no `@Future` validation for NOW). SCHEDULED requires future `scheduledAt`.
Timers (config, defaults): NOW re-push to all drivers (also those not "available now", but never "away") at 10 min, NO_DRIVER at 20 min. SCHEDULED: driver reminders at T-24h and T-2h if unaccepted; `urgent=true` from T-60 min while REQUESTED; assigned-driver reminder T-30 min. Reminders/pushes go through `PushService` (still log-only until M6) and are recorded once-only in `ride_notifications_sent(ride_id, kind)` (unique) *(clarification: table created in M1, reused by M6)*. A scheduler runs every minute.

## Offers
`ride_offers(ride_id, driver_id, status OFFERED|VIEWED|ACCEPTED|DECLINED|EXPIRED|WITHDRAWN, priority boolean, offered_at, viewed_at, responded_at, comment)`, unique (ride_id, driver_id).
- Created at booking for each same-world driver: NOW → drivers with `driver_available_now=true` and not away now; SCHEDULED → drivers not away at the ride's scheduled date (ignores available-now).
- `GET /api/driver/rides/open` = rides in REQUESTED where the caller has an OFFERED/VIEWED offer; listing marks OFFERED→VIEWED. NOW re-push at 10 min also creates offers for previously skipped (not-available-now, not-away) drivers.
- When the last non-final offer becomes DECLINED/EXPIRED → NO_DRIVER.
- Accepting sets the winner's offer ACCEPTED and all other open offers WITHDRAWN.

## Availability
`users.driver_available_now BOOLEAN NOT NULL DEFAULT TRUE`, `users.driver_away_from DATE`, `users.driver_away_until DATE` (inclusive, Europe/Stockholm dates).
`GET /api/driver/availability` → `{availableNow, awayFrom, awayUntil}`; `PUT /api/driver/availability` same body.

## Idempotency
`POST /api/rides` accepts header `Idempotency-Key` (≤64 chars). Stored as `rides.client_request_id`, unique per passenger (`passenger_id, client_request_id`). Repeat with same key → 200 with the existing ride (no second ride). Frontend sends a UUID per booking attempt and disables the button while submitting.

## Edit
`PATCH /api/rides/{id}` (passenger only) body any of `{scheduledAt, fromAddress, fromLat, fromLon, toAddress, toLat, toLon, pickupNote}`.
- `REQUESTED|NO_DRIVER`: anything (time change from NO_DRIVER → REQUESTED + re-offer all).
- `ACCEPTED`: material change (pickup moved > 500 m, time shift > 30 min, or route > 25% or > 5 km longer — OSRM via `OsrmRouteProxyService`, haversine fallback) → REQUESTED with same-driver priority re-offer; else stays ACCEPTED (driver notified). Response field `lastEditMaterial: boolean`.
- `EN_ROUTE` and later: 409 `EDIT_NOT_ALLOWED`.

## Proximity warning
Accepting a ride within ±45 min of another ride the driver has ACCEPTED/EN_ROUTE/ARRIVED/PICKED_UP → 409 `{"code":"PROXIMITY_WARNING","error":...,"conflictingRide":{"id","scheduledAt","fromAddress"}}` unless body `{"confirmProximity":true}`. Behind interface `RideConflictChecker`.

## Stale / conflict codes
All 409 bodies: `{"error": "...", "code": "..."}` with codes: `RIDE_TAKEN` (accepted by someone else), `RIDE_CANCELLED`, `RIDE_CHANGED` (edited / no longer in the expected state), `OFFER_CLOSED` (caller's offer declined/withdrawn/expired), `CONFIRM_REQUIRED`, `ALL_DECLINED`, `EDIT_NOT_ALLOWED`, `PROXIMITY_WARNING`, `INVALID_TRANSITION`.

## Messages
`ride_messages(id, ride_id, sender_id, code, text, created_at, read_at)`. Canned codes only:
passenger: `PASSENGER_OUTSIDE` ("Jag står utanför"), `PASSENGER_TWO_MIN` ("Kommer om 2 min"), `PASSENGER_CALL_ME` ("Ring mig");
driver: `DRIVER_HERE` ("Jag är här"), `DRIVER_TWO_MIN` ("Är där om 2 min"), `DRIVER_LATE` ("Blir lite sen").
`GET /api/rides/{id}/messages` (participants), `POST /api/rides/{id}/messages {code}` (participants, allowed while ACCEPTED..PICKED_UP). Messages also go through `PushService.notifyUser` (log-only until M6). `ride_events` stays a pure state timeline.

## Endpoints (driver, all `/api/driver/rides/{id}/...`)
`accept {confirmProximity?}`, `decline {comment?}` (alias: existing `refuse`), `return {reason?}` (alias: existing `unaccept`), `start`, `arrive`, `pickup`, `complete`, `location {lat, lon, accuracy?}` (allowed EN_ROUTE..PICKED_UP).
Passenger: `POST /api/rides` (book), `PATCH /api/rides/{id}`, `POST /api/rides/{id}/cancel {reason?, confirm?}`, `POST /api/rides/{id}/keep-waiting`.

## Ride DTO (`RideResponse`, additive)
Existing fields plus: `kind`, `pickupNote`, `urgent`, `passengerName`, `passengerPhone`, `driverPhone`, `driverPhotoUrl`, `driverVehicleNote`, `arrivedAt`, `pickedUpAt`, `lastEditMaterial` (only on PATCH responses, else null), `myOfferStatus` (for drivers, else null), `availableActions` (string list computed for the caller: `CANCEL, CANCEL_CONFIRM, EDIT, KEEP_WAITING, ACCEPT, DECLINE, RETURN, START, ARRIVE, PICKUP, COMPLETE, MESSAGE`).
Phones are only included for participants of an accepted ride (passenger sees driver phone and vice versa).

## Rollback note
V6 renames status values. Rolling back to v1.5.0 requires mapping statuses back first:
`UPDATE rides SET status='PENDING_OPEN' WHERE status IN ('REQUESTED','NO_DRIVER'); UPDATE rides SET status='IN_PROGRESS' WHERE status IN ('EN_ROUTE','ARRIVED','PICKED_UP');`
