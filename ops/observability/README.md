# Farfartaxi observability (Prometheus + Grafana)

Runs on newhomeserver (192.168.1.222). Prometheus scrapes the backend management port
`192.168.1.151:8090/actuator/prometheus` (basic auth, user `prometheus`) every 30 s, 30 d retention,
published on `127.0.0.1:9090` only. Grafana (provisioned dashboard "Farfartaxi", uid `farfartaxi`) is on
`http://192.168.1.222:3030` (LAN only, no anonymous access, no sign-up). Prometheus is the only datasource.

## Deploy
Deploy directory: `/home/magpern/farfartaxi-observability/`.

```bash
D=/home/magpern/farfartaxi-observability
mkdir -p $D/secrets $D/prometheus-data
cp -r ops/observability/{docker-compose.yml,prometheus,grafana,scripts,.gitignore} $D/
cd $D
# secrets (never printed / committed); prometheus runs as uid 1000 so files must be readable by it
umask 077
[ -f .env ] || echo "GRAFANA_ADMIN_PASSWORD=$(openssl rand -base64 24 | tr -d '=+/')" > .env
[ -f secrets/farfartaxi_password ] || openssl rand -hex 24 > secrets/farfartaxi_password
chmod 600 .env secrets/farfartaxi_password
docker compose up -d
```
The same value as `secrets/farfartaxi_password` must be set as `MANAGEMENT_PROMETHEUS_PASSWORD` in the
Pi backend's `.env`. `prometheus-data/` must be owned by uid 1000 (it is the Prometheus TSDB bind dir).

## Verify
```bash
cd /home/magpern/farfartaxi-observability
docker compose ps
./scripts/verify-grafana.sh        # health, dashboard, series per panel; non-zero if a required panel is empty
# manual:
PW=$(grep ^GRAFANA_ADMIN_PASSWORD= .env | cut -d= -f2-)
curl -s http://192.168.1.222:3030/api/health
printf 'user = "admin:%s"\n' "$PW" | curl -s -K - http://192.168.1.222:3030/api/dashboards/uid/farfartaxi | head -c 300
printf 'user = "admin:%s"\n' "$PW" | curl -s -K - -X POST -H 'Content-Type: application/json' \
  http://192.168.1.222:3030/api/ds/query -d '{"from":"now-1h","to":"now","queries":[{"refId":"A","datasource":{"type":"prometheus","uid":"prometheus"},"expr":"up{job=\"farfartaxi\"}"}]}'
curl -s http://127.0.0.1:9090/api/v1/targets | grep -o '"health":"[a-z]*"'
```
Panels marked `[optional]` in their description (zero-event counters such as 5xx, NO_DRIVER) only warn when empty.

## Update / backup / rollback
- Update dashboards/provisioning: copy the repo files over the deploy dir; Grafana reloads dashboards within 60 s
  (`docker compose restart grafana` for datasource changes).
- Backup: `docker run --rm -v farfartaxi-observability_grafana-data:/d -v $PWD:/b alpine tar czf /b/grafana-data.tgz -C /d .`
  and `tar czf prometheus-data.tgz prometheus-data` (stop Prometheus first for a consistent copy). Keep `.env` and `secrets/`.
- Rollback: `docker compose down` (data kept in the volume/dir); to pin back, edit the image tags in `docker-compose.yml`
  and `docker compose up -d`. Full removal: `docker compose down -v && rm -rf prometheus-data`.
