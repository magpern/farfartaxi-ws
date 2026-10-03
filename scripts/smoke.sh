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

# 2. API golden flow
PTOKEN=$(login "$PASSENGER_EMAIL" "$TEST_PASSENGER_PASSWORD") || exit 1
[ -n "$PTOKEN" ] || fail "no passenger token"
ok "passenger login"
DTOKEN=$(login "$DRIVER_EMAIL" "$TEST_DRIVER_PASSWORD") || exit 1
[ -n "$DTOKEN" ] || fail "no driver token"
ok "driver login"

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

req POST "/api/driver/rides/$RID/accept" "$DTOKEN" '{"confirmProximity":true}'; expect "accept" 200
[ "$(jget 'd["status"]')" = "ACCEPTED" ] || fail "accept did not return ACCEPTED"
req POST "/api/driver/rides/$RID/start" "$DTOKEN"; expect "start" 200
[ "$(jget 'd["status"]')" = "EN_ROUTE" ] || fail "start did not return EN_ROUTE"
req POST "/api/driver/rides/$RID/arrive" "$DTOKEN"; expect "arrive" 200
[ "$(jget 'd["status"]')" = "ARRIVED" ] || fail "arrive did not return ARRIVED"
req POST "/api/driver/rides/$RID/location" "$DTOKEN" '{"lat":59.335,"lon":18.08}'
case "$CODE" in 200|204) ;; *) fail "location: expected 200/204, got $CODE";; esac
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

echo "SMOKE PASS ($BASE, version=$VERSION)"
