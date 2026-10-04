#!/usr/bin/env bash
# Post-deploy smoke test. Usage: scripts/smoke.sh <base-url>
# Env: TEST_PASSENGER_PASSWORD, TEST_DRIVER_PASSWORD (never printed).
# Uses only the seeded test accounts and their own rides.
# Secrets (passwords, bearer tokens) never appear on a command line: JSON bodies go to curl via
# stdin (--data-binary @-), headers via a mode-600 curl config file removed on exit.
set -u
umask 077
TMPD=$(mktemp -d) || { echo "mktemp failed" >&2; exit 2; }
trap 'rm -rf "$TMPD"' EXIT
CURL_CFG="$TMPD/curl.cfg"
BASE="${1:-}"
[ -n "$BASE" ] || { echo "usage: $0 <base-url>" >&2; exit 2; }
BASE="${BASE%/}"
: "${TEST_PASSENGER_PASSWORD:?TEST_PASSENGER_PASSWORD not set}"
: "${TEST_DRIVER_PASSWORD:?TEST_DRIVER_PASSWORD not set}"
PASSENGER_EMAIL="${SMOKE_PASSENGER_EMAIL:-test-passenger@farfartaxi.invalid}"
DRIVER_EMAIL="${SMOKE_DRIVER_EMAIL:-test-driver@farfartaxi.invalid}"

fail() { echo "SMOKE FAIL: $*" >&2; exit 1; }
ok() { echo "ok: $*"; }

# req METHOD PATH [TOKEN] [BODY] [IDEMPOTENCY_KEY] -> sets CODE and BODY_OUT
# Authorization header goes in a mode-600 curl config file (-K); the body is fed on stdin.
req() {
  local method="$1" path="$2" token="${3:-}" body="${4:-}" idem="${5:-}" out
  local args=(-sS -m 30 -o - -w $'\n%{http_code}' -X "$method" -K "$CURL_CFG")
  : > "$CURL_CFG"
  printf 'header = "Accept: application/json"\n' >> "$CURL_CFG"
  [ -n "$token" ] && printf 'header = "Authorization: Bearer %s"\n' "$token" >> "$CURL_CFG"
  [ -n "$idem" ] && printf 'header = "Idempotency-Key: %s"\n' "$idem" >> "$CURL_CFG"
  if [ -n "$body" ]; then
    printf 'header = "Content-Type: application/json"\n' >> "$CURL_CFG"
    args+=(--data-binary @-)
    out=$(printf '%s' "$body" | curl "${args[@]}" "$BASE$path") || fail "curl failed for $method $path"
  else
    out=$(curl "${args[@]}" "$BASE$path" </dev/null) || fail "curl failed for $method $path"
  fi
  CODE="${out##*$'\n'}"
  BODY_OUT="${out%$'\n'*}"
}
expect() { # expect <label> <code>
  [ "$CODE" = "$2" ] || fail "$1: expected HTTP $2, got $CODE"
}
jget() { # jget <python expr over d>
  printf '%s' "$BODY_OUT" | python3 -c "import sys,json; d=json.load(sys.stdin); print($1)" 2>/dev/null
}
login() { # login EMAIL PASSWORD -> prints token (password passed via env, never argv)
  local body
  body=$(EMAIL="$1" PW="$2" python3 -c 'import json,os; print(json.dumps({"email":os.environ["EMAIL"],"password":os.environ["PW"]}))')
  req POST /api/auth/login "" "$body"
  expect "login $1" 200
  jget 'd["token"]'
}

# 1. frontend + public endpoints
req GET / ; expect "frontend /" 200
ok "frontend 200"
req GET /api/public/version ; expect "/api/public/version" 200
VERSION=$(jget 'd["version"]'); COMMIT=$(jget 'd["commit"]')
[ -n "$VERSION" ] || fail "version endpoint returned no version"
ok "version=$VERSION commit=$COMMIT"
req GET /api/public/push-config ; expect "public health (/api/public/push-config)" 200
ok "backend reachable via public endpoint"
[ -n "$(jget 'd.get("publicKey") or ""')" ] || fail "push-config returned an empty publicKey (VAPID not configured)"
ok "push-config has a publicKey"

# 2. API golden flow
PTOKEN=$(login "$PASSENGER_EMAIL" "$TEST_PASSENGER_PASSWORD") || exit 1
[ -n "$PTOKEN" ] || fail "no passenger token"
ok "passenger login"
DTOKEN=$(login "$DRIVER_EMAIL" "$TEST_DRIVER_PASSWORD") || exit 1
[ -n "$DTOKEN" ] || fail "no driver token"
ok "driver login"

# M3: authenticated places search (public geocode endpoints are gone). Empty results are fine (SL may be down).
req GET "/api/places/search?q=mcdonalds" "$PTOKEN"; expect "places search" 200
printf '%s' "$BODY_OUT" | python3 -c 'import sys,json; d=json.load(sys.stdin); sys.exit(0 if isinstance(d.get("results"), list) else 1)' \
  || fail "places search did not return a JSON object with a results array"
ok "places search 200 ($(jget 'len(d["results"])') results)"
req GET "/api/places/search?q=mcdonalds"; [ "$CODE" = "401" ] || [ "$CODE" = "403" ] || fail "places search without token: expected 401/403, got $CODE"
ok "places search requires auth"
req GET "/api/public/geocode/search?q=mcdonalds"; expect "removed public geocode search" 404
ok "public geocode search is gone (404)"

# M4: saved places (create, list, delete) and recent places.
SP_BODY=$(python3 -c 'import json; print(json.dumps({"label":"Smoke plats","address":"Smoke Plats 3, Test","formattedAddress":"Smoke Plats 3, Test","lat":59.34,"lon":18.09,"kind":"OTHER"}))')
req POST /api/saved-places "$PTOKEN" "$SP_BODY"; expect "create saved place" 200
SPID=$(jget 'd["id"]')
[ -n "$SPID" ] || fail "create saved place returned no id"
req GET /api/saved-places "$PTOKEN"; expect "list saved places" 200
printf '%s' "$BODY_OUT" | SPID="$SPID" python3 -c 'import sys,json,os; sys.exit(0 if any(str(p["id"])==os.environ["SPID"] for p in json.load(sys.stdin)) else 1)' \
  || fail "saved place $SPID not in the passenger's list"
req DELETE "/api/saved-places/$SPID" "$PTOKEN"; expect "delete saved place" 200
req GET /api/saved-places "$PTOKEN"; expect "list saved places after delete" 200
printf '%s' "$BODY_OUT" | SPID="$SPID" python3 -c 'import sys,json,os; sys.exit(1 if any(str(p["id"])==os.environ["SPID"] for p in json.load(sys.stdin)) else 0)' \
  || fail "saved place $SPID still listed after delete"
ok "saved place create/list/delete"
req GET "/api/places/recent?limit=3" "$PTOKEN"; expect "recent places" 200
printf '%s' "$BODY_OUT" | python3 -c 'import sys,json; sys.exit(0 if isinstance(json.load(sys.stdin), list) else 1)' || fail "recent places is not a JSON array"
ok "recent places 200 ($(jget 'len(d)') items)"

# M6: notification prefs round-trip (original values restored) and locale.
req GET /api/me/notification-prefs "$PTOKEN"; expect "get notification prefs" 200
ORIG_PREFS="$BODY_OUT"
FLIPPED=$(printf '%s' "$ORIG_PREFS" | python3 -c 'import sys,json; d=json.load(sys.stdin); print(json.dumps({"rideRequests":not d["rideRequests"],"rideUpdates":not d["rideUpdates"],"reminders":not d["reminders"]}))') \
  || fail "notification prefs response is not the expected JSON"
req PUT /api/me/notification-prefs "$PTOKEN" "$FLIPPED"; expect "put notification prefs" 200
req GET /api/me/notification-prefs "$PTOKEN"; expect "get notification prefs after put" 200
printf '%s' "$BODY_OUT" | FLIPPED="$FLIPPED" python3 -c 'import sys,json,os; g=json.load(sys.stdin); w=json.loads(os.environ["FLIPPED"]); sys.exit(0 if all(g[k]==w[k] for k in w) else 1)' \
  || fail "notification prefs did not round-trip"
RESTORE=$(printf '%s' "$ORIG_PREFS" | python3 -c 'import sys,json; d=json.load(sys.stdin); print(json.dumps({k:d[k] for k in ("rideRequests","rideUpdates","reminders")}))')
req PUT /api/me/notification-prefs "$PTOKEN" "$RESTORE"; expect "restore notification prefs" 200
ok "notification-prefs round-trip (restored)"
req PUT /api/me/locale "$PTOKEN" '{"locale":"sv"}'
case "$CODE" in 200|204) ;; *) fail "PUT /api/me/locale: expected 200/204, got $CODE";; esac
ok "locale set to sv"

req GET /api/auth/me "$PTOKEN"; expect "passenger me" 200
PID=$(jget 'd["id"]')
[ -n "$PID" ] || fail "could not read passenger id"

WHEN=$(python3 -c 'import datetime as d; print((d.datetime.now(d.timezone.utc)+d.timedelta(hours=1)).strftime("%Y-%m-%dT%H:%M:%SZ"))')
BOOK=$(WHEN="$WHEN" python3 -c 'import json,os; print(json.dumps({"kind":"SCHEDULED","fromAddress":"Smoke Start 1, Test","fromLat":59.3293,"fromLon":18.0686,"toAddress":"Smoke Slut 2, Test","toLat":59.3400,"toLon":18.0900,"scheduledAt":os.environ["WHEN"]}))')
IDEM="smoke-$(python3 -c 'import uuid; print(uuid.uuid4())')"
req POST /api/rides "$PTOKEN" "$BOOK" "$IDEM"; expect "book ride" 200
RID=$(jget 'd["id"]')
[ -n "$RID" ] || fail "book ride returned no id"
[ "$(jget 'd["status"]')" = "REQUESTED" ] || fail "new ride is not REQUESTED"
ok "booked ride $RID"

req POST /api/rides "$PTOKEN" "$BOOK" "$IDEM"; expect "book ride (idempotent repeat)" 200
[ "$(jget 'd["id"]')" = "$RID" ] || fail "idempotent repeat returned a different ride id (duplicate booking)"
ok "idempotent repeat returned same ride $RID"

req GET /api/driver/rides/open "$DTOKEN"; expect "driver open rides" 200
printf '%s' "$BODY_OUT" | RID="$RID" python3 -c 'import sys,json,os; sys.exit(0 if any(str(r["id"])==os.environ["RID"] for r in json.load(sys.stdin)) else 1)' \
  || fail "driver does not see ride $RID in open rides"
ok "driver sees ride in open rides"

# Isolation: every open ride visible to the test driver must belong to the test passenger.
printf '%s' "$BODY_OUT" | PID="$PID" python3 -c '
import sys,json,os
bad=[r["id"] for r in json.load(sys.stdin) if str(r["passengerId"])!=os.environ["PID"]]
if bad: print("foreign ride ids: %s" % bad, file=sys.stderr); sys.exit(1)' \
  || fail "test driver can see rides that are not the test passenger's (isolation broken)"
ok "driver open-rides list contains only test-passenger rides"

# M2: the booked ride is the passenger's active ride.
req GET /api/rides/active "$PTOKEN"; expect "passenger active ride" 200
[ "$(jget 'd["role"]')" = "PASSENGER" ] && [ "$(jget 'd["ride"]["id"]')" = "$RID" ] || fail "/api/rides/active did not return ride $RID for the passenger"
ok "passenger active ride is $RID"

req POST "/api/driver/rides/$RID/accept" "$DTOKEN" '{"confirmProximity":true}'; expect "accept" 200
[ "$(jget 'd["status"]')" = "ACCEPTED" ] || fail "accept did not return ACCEPTED"
req POST "/api/driver/rides/$RID/start" "$DTOKEN"; expect "start" 200
[ "$(jget 'd["status"]')" = "EN_ROUTE" ] || fail "start did not return EN_ROUTE"
req POST "/api/driver/rides/$RID/arrive" "$DTOKEN"; expect "arrive" 200
[ "$(jget 'd["status"]')" = "ARRIVED" ] || fail "arrive did not return ARRIVED"
req POST "/api/driver/rides/$RID/location" "$DTOKEN" '{"lat":59.335,"lon":18.08}'
case "$CODE" in 200|204) ;; *) fail "location: expected 200/204, got $CODE";; esac
# M7: live tracking + share link (ride is ARRIVED = active). Accuracy is accepted and echoed.
req POST "/api/driver/rides/$RID/location" "$DTOKEN" '{"lat":59.336,"lon":18.081,"accuracy":15}'
case "$CODE" in 200|204) ;; *) fail "location with accuracy: expected 200/204, got $CODE";; esac
req GET "/api/rides/$RID" "$PTOKEN"; expect "passenger ride after location" 200
[ -n "$(jget 'd.get("lastLocationAt") or ""')" ] || fail "ride has no lastLocationAt after a location post"
[ "$(jget 'd.get("locationStale")')" = "False" ] || fail "fresh position flagged locationStale"
ok "live position stored (not stale)"
req POST "/api/rides/$RID/share" "$PTOKEN"; expect "create share" 200
STOKEN=$(printf '%s' "$BODY_OUT" | python3 -c 'import sys,json,re; u=json.load(sys.stdin)["url"]; m=re.search(r"/dela/([A-Za-z0-9_-]+)$", u); print(m.group(1) if m else "")')
[ -n "$STOKEN" ] || fail "share response has no /dela/<token> url"
req GET "/api/public/share/$STOKEN" ; expect "public share (no auth)" 200
printf '%s' "$BODY_OUT" | python3 -c '
import sys,json,re
raw=sys.stdin.read(); d=json.loads(raw)
bad=[k for k in re.findall(r"\"([^\"]*)\"\s*:", raw) if re.search(r"phone|email|note|id$", k, re.I)]
if bad or "+46" in raw: print("leaky share fields: %s" % bad, file=sys.stderr); sys.exit(1)
if not (d.get("passengerFirstName") and d.get("pickup") and d.get("destination")): sys.exit(1)' \
  || fail "public share payload leaks private fields or lacks required fields"
ok "public share 200 without auth, no phone/email/note/id fields"
req DELETE "/api/rides/$RID/share" "$PTOKEN"
case "$CODE" in 200|204) ;; *) fail "revoke share: expected 200/204, got $CODE";; esac
req GET "/api/public/share/$STOKEN" ; expect "public share after revoke" 410
ok "revoked share link returns 410"
req GET "/api/public/share/not-a-real-token-0000000000000000" ; expect "unknown share token" 404
ok "unknown share token returns 404"

req POST "/api/driver/rides/$RID/pickup" "$DTOKEN"; expect "pickup" 200
[ "$(jget 'd["status"]')" = "PICKED_UP" ] || fail "pickup did not return PICKED_UP"
req POST "/api/driver/rides/$RID/complete" "$DTOKEN"; expect "complete" 200
[ "$(jget 'd["status"]')" = "COMPLETED" ] || fail "complete did not return COMPLETED"
ok "accept/start/arrive/location/pickup/complete"

# Passenger sees it COMPLETED (a future-dated ride is listed under history=false until its time passes).
FOUND=""
for h in true false; do
  req GET "/api/rides/my?history=$h" "$PTOKEN"; expect "passenger rides history=$h" 200
  S=$(printf '%s' "$BODY_OUT" | RID="$RID" python3 -c 'import sys,json,os; print(next((r["status"] for r in json.load(sys.stdin) if str(r["id"])==os.environ["RID"]),""))')
  [ -n "$S" ] && FOUND="$S"
done
[ "$FOUND" = "COMPLETED" ] || fail "passenger does not see ride $RID as COMPLETED (saw '${FOUND:-nothing}')"
ok "passenger sees ride COMPLETED"

# M2: driver history lists the finished ride; nothing is active any more.
req GET "/api/driver/rides/history?limit=20" "$DTOKEN"; expect "driver history" 200
printf '%s' "$BODY_OUT" | RID="$RID" python3 -c 'import sys,json,os; sys.exit(0 if any(str(r["id"])==os.environ["RID"] for r in json.load(sys.stdin)) else 1)' \
  || fail "driver history does not contain ride $RID"
ok "driver history contains ride $RID"
req GET /api/rides/active "$PTOKEN"; expect "passenger active ride after completion" 204
ok "no active ride after completion"

# M7: returning an ongoing ride clears tracking -> the passenger's ride exposes no driver position (second ride).
BOOK2=$(python3 -c 'import datetime as d,json; print(json.dumps({"kind":"SCHEDULED","fromAddress":"Smoke Start 1, Test","fromLat":59.3293,"fromLon":18.0686,"toAddress":"Smoke Slut 2, Test","toLat":59.34,"toLon":18.09,"scheduledAt":(d.datetime.now(d.timezone.utc)+d.timedelta(hours=2)).strftime("%Y-%m-%dT%H:%M:%SZ")}))')
req POST /api/rides "$PTOKEN" "$BOOK2" "smoke-r-$(python3 -c 'import uuid; print(uuid.uuid4())')"; expect "book ride 2" 200
RID2=$(jget 'd["id"]')
[ -n "$RID2" ] || fail "book ride 2 returned no id"
req POST "/api/driver/rides/$RID2/accept" "$DTOKEN" '{"confirmProximity":true}'; expect "accept ride 2" 200
req POST "/api/driver/rides/$RID2/start" "$DTOKEN"; expect "start ride 2" 200
req POST "/api/driver/rides/$RID2/location" "$DTOKEN" '{"lat":59.331,"lon":18.07,"accuracy":10}'
case "$CODE" in 200|204) ;; *) fail "location ride 2: expected 200/204, got $CODE";; esac
req GET "/api/rides/$RID2" "$PTOKEN"; expect "passenger ride 2 while en route" 200
[ "$(jget 'd.get("lastDriverLat") is not None')" = "True" ] || fail "en-route ride exposes no driver position"
req POST "/api/driver/rides/$RID2/return" "$DTOKEN" '{"reason":"smoke"}'; expect "return ride 2" 200
req GET "/api/rides/$RID2" "$PTOKEN"; expect "passenger ride 2 after return" 200
[ "$(jget 'd.get("lastDriverLat") is None and d.get("lastDriverLon") is None and d.get("lastLocationAt") is None')" = "True" ] \
  || fail "passenger ride still exposes a driver position after return"
ok "no driver position on the passenger ride after return"
req POST "/api/rides/$RID2/cancel" "$PTOKEN" '{"reason":"smoke cleanup","confirm":true}'; ok "ride 2 cleaned up (cancel -> HTTP $CODE)"

# M8: telemetry ingestion (allowlisted event accepted; coordinate-like props rejected; unknown names dropped)
req POST /api/telemetry/events "$PTOKEN" '{"sessionId":"smoke","events":[{"name":"search_started","props":{"queryLength":4}},{"name":"search_started","props":{"queryLength":4,"lat":59.42351}},{"name":"not_an_event","props":{}}]}'
expect "telemetry" 200
[ "$(jget 'd["accepted"]')" = "1" ] || fail "telemetry: expected 1 accepted, got $BODY_OUT"
[ "$(jget 'd["dropped"]')" = "2" ] || fail "telemetry: expected 2 dropped, got $BODY_OUT"
req POST /api/telemetry/events "" '{"events":[]}'
[ "$CODE" = "401" ] || [ "$CODE" = "403" ] || fail "telemetry without token: expected 401/403, got $CODE"
ok "telemetry: allowlisted event accepted, PII/unknown dropped, auth required"

echo "SMOKE PASS ($BASE, version=$VERSION)"
