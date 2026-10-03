# M3 API contract (search and places)

Implements roadmap "M3: Search and places". Fixed contract between backend and frontend for M3. *(clarification)* marks master decisions.

## Endpoints (all authenticated, approved users; NOT under /api/public — closes the open geocode proxy)
- `GET /api/places/search?q=&lat=&lon=&accuracy=&pickupLat=&pickupLon=&limit=`
  - `q` ≥ 2 chars after normalization (else `[]`), ≤ 100 chars.
  - Response `{ "results": PlaceResult[], "hasMore": boolean, "context": "GPS"|"PICKUP"|"HOME"|"DEFAULT" }`. Default `limit` 8; "Visa fler" calls again with `limit=25`.
- `GET /api/places/nearest-stop?lat=&lon=` → `{ "name", "area", "lat", "lon", "distanceM", "providerPlaceId" }` or 204 if none within 2 km.
- `POST /api/places/selections` body `{ "query", "provider", "providerPlaceId", "name", "lat", "lon" }` → 204. Records a learned selection (called by the frontend when the user picks a result).
- `GET /api/places/reverse?lat=&lon=` → `PlaceResult` (kind `ADDRESS`, provider `NOMINATIM`) or 204. Replaces `/api/public/geocode/reverse` *(clarification: moved behind auth; public reverse/search endpoints removed)*. Nominatim reverse only, with a non-blocking rate limiter (no `Thread.sleep` under a global lock; if the 1 req/s budget is exhausted return 429 quickly).
- `/api/public/geocode/search` and `/api/public/geocode/reverse` are deleted. `/api/public/route/driving` stays (booking route line) *(clarification)*.

## PlaceResult
`{ provider: "SL"|"NOMINATIM"|"FAVORITE"|"RECENT", providerPlaceId: string|null, kind: "STOP"|"ADDRESS"|"POI"|"FAVORITE"|"RECENT", name: string, area: string|null, formattedAddress: string, lat: number, lon: number, distanceKm: number|null }`
- Readable labels: stop `name="McDonalds"`, `area="Järfälla"`, `formattedAddress="McDonalds — Järfälla (hållplats)"`; address `name="Sveavägen 12"`, `area="Stockholm"`, `formattedAddress="Sveavägen 12, Stockholm"`; POI `name="Kista Galleria"`, `area` if known.
- SL `type` mapping: `stop`→STOP, `singlehouse`/`street`/`address`→ADDRESS, `poi`→POI; others dropped.

## Architecture
`PlacesController → PlaceSearchService → PlaceProvider` (interface). `SlPlaceProvider` = only search provider now: `https://journeyplanner.integration.sl.se/v2/stop-finder?name_sf=<q>&type_sf=any&any_obj_filter_sf=46` (verify the filter value; if 46 drops POIs or addresses, use 0), Java `HttpClient`, 3 s timeout, no global lock, Caffeine cache keyed by normalized query (10 min, max 2000 entries). Provider failure → results from favorites/recents only + `results` still 200 (never 500).

## Normalization
NFC, lowercase, collapse whitespace, strip punctuation (keep åäö), `mc donalds`/`mcdonald's`/`mcdonalds` → `mcdonalds`, synonym table (at least `donken`→`mcdonalds`, `maccen`→`mcdonalds`, `ica maxi`/`icamaxi`); token matching for favorites/recents (all query tokens must prefix-match tokens of the label/address).

## Ranking
1. Matching favorites (`saved_places` of the caller) then matching recents (distinct pickups/destinations of the caller's rides, last 90 days) — max 3 each.
2. Provider results scored: `score = matchQuality(normalized 0..1) × distanceFactor + learnedBoost`. distanceFactor tiers from the context point: 0–20 km 1.0, 20–50 km 0.7, 50–100 km 0.4, >100 km 0.1 (no hard cutoff; >100 km results appear only after "Visa fler" or if nothing else matches).
3. Dedupe exact duplicates (same normalized name within 50 m).
4. `learnedBoost` from `place_selections`: effective score = `score · 0.5^(Δdays/90)`; same-user selections weigh ×2 vs other family members; boost is added for the exact `(normalized_query prefix match, provider, providerPlaceId)`.

## Learned ranking storage (Flyway V7, reserved)
`place_selections(id, user_id FK users ON DELETE CASCADE, normalized_query VARCHAR(100), provider VARCHAR(16), provider_place_id VARCHAR(128), name VARCHAR(256), lat, lon, score DOUBLE, last_selected_at TIMESTAMPTZ, UNIQUE(user_id, normalized_query, provider, provider_place_id))`. On selection: `score = score·0.5^(Δdays/90) + 1`. Nightly job prunes effective score < 0.05. World isolation: test users' selections never influence real users and vice versa *(clarification)*.

## Context fallback
Server chooses the context point: GPS (`lat/lon` with `accuracy` < 1000 m) → `pickupLat/pickupLon` → caller's "Home" saved place (label/kind home, case-insensitive "hem"/"home") → configured `app.places.default-center` (Järfälla: 59.4235, 17.8350). `context` field reports which one was used.

## Nearest stop
SL Transport `GET https://transport.integration.sl.se/v1/sites` loaded into memory at startup (async, non-blocking startup) and nightly; linear haversine scan; result within 2 km. If the load failed, endpoint returns 204 (frontend just omits the hint).

## Metrics
Timer `farfartaxi.places.search{provider, outcome=ok|empty|error}` with percentile histogram enabled (for p95), counter for cache hits, timer for nearest-stop and reverse.

## Frontend
`src/components/place-search/`, `src/hooks/usePlaceSearch.ts`, `src/api/places.ts`: 200 ms debounce, abort previous request, from 2 chars, spinner, "Inga träffar" empty state, kind icons (🚏 stop, 🏠 address, 📍 POI, ⭐ favorite, 🕘 recent), "Visa fler", ✕ clear also resets coordinates, sends GPS (with accuracy) or pickup as context, records selection via `POST /api/places/selections`. Pickup defaults to "📍 Min position" with nearest-stop hint "📍 Här (Kallhälls station, 120 m)" when GPS is available. Map tap uses `/api/places/reverse`.
