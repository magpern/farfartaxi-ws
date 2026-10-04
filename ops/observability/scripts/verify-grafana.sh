#!/usr/bin/env bash
# Verifies Grafana health, the provisioned dashboard, and that each panel's main query returns series.
# A panel has data only if at least one frame has >=2 fields and a non-empty data.values[1] (an empty frame
# with just a header does not count).
# Usage: verify-grafana.sh [--selftest]   (--selftest runs one known-empty query, up{job="nope"}, and expects 0 data frames)
# Panels whose description contains "[optional]" (legitimately empty when nothing happened) only warn.
set -euo pipefail
cd "$(dirname "$0")/.."
GRAFANA_URL="${GRAFANA_URL:-http://192.168.1.222:3030}"
PW="$(grep -E '^GRAFANA_ADMIN_PASSWORD=' .env | head -1 | cut -d= -f2-)"
[ -n "$PW" ] || { echo "GRAFANA_ADMIN_PASSWORD missing in .env" >&2; exit 2; }
# password passed via a curl config on stdin, never on the command line or stdout
api() { printf 'user = "admin:%s"\n' "$PW" | curl -fsS -K - "$@"; }

# runs one query through /api/ds/query; prints the number of frames that really carry data
count_frames() { # $1 = JSON body
  printf '%s' "$1" | api -X POST -H 'Content-Type: application/json' --data-binary @- "$GRAFANA_URL/api/ds/query" \
    | python3 -c '
import json,sys
r=json.load(sys.stdin)["results"]["A"]
n=0
for f in r.get("frames",[]):
    d=f.get("schema",{}).get("fields",[])
    v=f.get("data",{}).get("values",[])
    if len(d)>=2 and len(v)>=2 and len(v[1])>0:
        n+=1
print(n)'
}

if [ "${1:-}" = "--selftest" ]; then
  body='{"from":"now-1h","to":"now","queries":[{"refId":"A","datasource":{"type":"prometheus","uid":"prometheus"},"expr":"up{job=\"nope\"}","range":true,"instant":false,"intervalMs":30000,"maxDataPoints":100}]}'
  n="$(count_frames "$body")"
  if [ "$n" = "0" ]; then echo "selftest OK: no-data query yields 0 (would FAIL as a panel)"; exit 0; fi
  echo "selftest BROKEN: no-data query yielded $n" >&2; exit 1
fi

curl -fsS "$GRAFANA_URL/api/health" >/dev/null && echo "health: ok"
dash="$(api "$GRAFANA_URL/api/dashboards/uid/farfartaxi")"
echo "dashboard: found"
export GRAFANA_URL
tmp="$(mktemp)"
trap 'rm -f "$tmp"' EXIT
python3 - "$dash" <<'PY' > "$tmp"
import json,sys
d=json.loads(sys.argv[1])["dashboard"]
for p in d["panels"]:
    if p.get("type")=="row" or not p.get("targets"): continue
    t=p["targets"][0]
    print(json.dumps({"title":p["title"],"expr":t["expr"],"optional":"[optional]" in p.get("description","")}))
PY
fail=0
while IFS= read -r line; do
  title="$(python3 -c 'import json,sys;print(json.loads(sys.argv[1])["title"])' "$line")"
  opt="$(python3 -c 'import json,sys;print(json.loads(sys.argv[1])["optional"])' "$line")"
  body="$(python3 -c '
import json,sys,os
p=json.loads(sys.argv[1])
print(json.dumps({"from":"now-"+os.environ.get("RANGE","1h"),"to":"now","queries":[{"refId":"A","datasource":{"type":"prometheus","uid":"prometheus"},"expr":p["expr"].replace("$world", os.environ.get("WORLD","real")).replace("$__range", os.environ.get("RANGE","1h")),"range":True,"instant":False,"intervalMs":30000,"maxDataPoints":100}]}))' "$line")"
  n="$(count_frames "$body")"
  if [ "$n" -ge 1 ]; then echo "OK    $title: $n series"
  elif [ "$opt" = "True" ]; then echo "WARN  $title: no data (optional)"
  else echo "FAIL  $title: no data"; fail=1; fi
done < "$tmp"
exit $fail
