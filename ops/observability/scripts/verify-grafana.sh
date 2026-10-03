#!/usr/bin/env bash
# Verifies Grafana health, the provisioned dashboard, and that each panel's main query returns series.
# Panels whose description contains "[optional]" (legitimately empty when nothing happened) only warn.
set -euo pipefail
cd "$(dirname "$0")/.."
GRAFANA_URL="${GRAFANA_URL:-http://192.168.1.222:3030}"
PW="$(grep -E '^GRAFANA_ADMIN_PASSWORD=' .env | head -1 | cut -d= -f2-)"
[ -n "$PW" ] || { echo "GRAFANA_ADMIN_PASSWORD missing in .env" >&2; exit 2; }
# password passed via a curl config on stdin, never on the command line or stdout
api() { printf 'user = "admin:%s"\n' "$PW" | curl -fsS -K - "$@"; }

curl -fsS "$GRAFANA_URL/api/health" >/dev/null && echo "health: ok"
dash="$(api "$GRAFANA_URL/api/dashboards/uid/farfartaxi")"
echo "dashboard: found"
export GRAFANA_URL
python3 - "$dash" <<'PY' > /tmp/.verify-panels.$$
import json,sys
d=json.loads(sys.argv[1])["dashboard"]
for p in d["panels"]:
    if p.get("type")=="row" or not p.get("targets"): continue
    t=p["targets"][0]
    print(json.dumps({"title":p["title"],"expr":t["expr"],"optional":"[optional]" in p.get("description","")}))
PY
trap 'rm -f /tmp/.verify-panels.$$' EXIT
fail=0
while IFS= read -r line; do
  title="$(python3 -c 'import json,sys;print(json.loads(sys.argv[1])["title"])' "$line")"
  opt="$(python3 -c 'import json,sys;print(json.loads(sys.argv[1])["optional"])' "$line")"
  body="$(python3 -c '
import json,sys
p=json.loads(sys.argv[1])
print(json.dumps({"from":"now-1h","to":"now","queries":[{"refId":"A","datasource":{"type":"prometheus","uid":"prometheus"},"expr":p["expr"],"range":True,"instant":False,"intervalMs":30000,"maxDataPoints":100}]}))' "$line")"
  n="$(printf '%s' "$body" | api -X POST -H 'Content-Type: application/json' --data-binary @- "$GRAFANA_URL/api/ds/query" \
      | python3 -c 'import json,sys;r=json.load(sys.stdin)["results"]["A"];print(len(r.get("frames",[])))')"
  if [ "$n" -ge 1 ]; then echo "OK    $title: $n series"
  elif [ "$opt" = "True" ]; then echo "WARN  $title: 0 series (optional)"
  else echo "FAIL  $title: 0 series"; fail=1; fi
done < /tmp/.verify-panels.$$
exit $fail
