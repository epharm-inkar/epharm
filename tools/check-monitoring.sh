#!/usr/bin/env bash
# Checks the monitoring stack with the same images production runs (needs Docker):
#   1. Prometheus config and alert rules load; rule unit tests pass.
#   2. Alertmanager config renders and validates with and without Telegram; bad input is refused;
#      the bot token stays out of the rendered config.
#   3. postgres-exporter loads ops/monitoring/postgres-exporter/queries.yml and exports the
#      business metrics with the right values against a throwaway PostgreSQL.
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
mon="$root/ops/monitoring"
prom_image=prom/prometheus:v3.5.0
am_image=prom/alertmanager:v0.28.1
pg_image=postgres:16-alpine
exporter_image=prometheuscommunity/postgres-exporter:v0.17.1
net=epharm-monitoring-check-$$
work=$(mktemp -d)
chmod 777 "$work"

cleanup() {
  docker rm -f "$net-pg" "$net-exporter" >/dev/null 2>&1 || true
  docker network rm "$net" >/dev/null 2>&1 || true
  rm -rf "$work"
}
trap cleanup EXIT

fail() { echo "FAIL: $*" >&2; exit 1; }

echo '== Prometheus config, rules and rule tests'
docker run --rm --entrypoint promtool -v "$mon/prometheus:/etc/prometheus:ro" "$prom_image" \
  check config /etc/prometheus/prometheus.yml
docker run --rm --entrypoint promtool -v "$mon/prometheus:/etc/prometheus:ro" -w /etc/prometheus/tests "$prom_image" \
  test rules epharm-business-alerts.test.yml

render() {
  # render <name> [env...]: renders into $work/<name>, prints amtool output
  local name=$1; shift
  mkdir -p "$work/$name"; chmod 777 "$work/$name"
  docker run --rm --entrypoint /bin/sh \
    -e ALERTMANAGER_VALIDATE_ONLY=true -e ALERTMANAGER_RENDER_DIR=/render "$@" \
    -v "$mon/alertmanager/render-and-run.sh:/etc/alertmanager/render-and-run.sh:ro" \
    -v "$work/$name:/render" \
    "$am_image" /etc/alertmanager/render-and-run.sh
}

echo '== Alertmanager: no receiver configured'
render none

echo '== Alertmanager: Telegram'
fake_token='123456789:AAFakeTokenForCiOnly_-xyz'
render telegram -e TELEGRAM_BOT_TOKEN="$fake_token" -e TELEGRAM_ALERT_CHAT_ID=-1001234567890
grep -q 'telegram_configs' "$work/telegram/alertmanager.yml" || fail 'telegram receiver missing'
grep -q 'chat_id: -1001234567890' "$work/telegram/alertmanager.yml" || fail 'chat id missing'
if grep -q "$fake_token" "$work/telegram/alertmanager.yml"; then fail 'bot token leaked into config'; fi
stored=$(docker run --rm --entrypoint cat -v "$work/telegram:/render:ro" "$am_image" /render/telegram-bot-token)
[ "$stored" = "$fake_token" ] || fail 'token file wrong'

echo '== Alertmanager: Telegram and webhook together'
render both -e TELEGRAM_BOT_TOKEN="$fake_token" -e TELEGRAM_ALERT_CHAT_ID=42 -e ALERT_WEBHOOK_URL=https://example.invalid/hook
grep -q 'webhook_configs' "$work/both/alertmanager.yml" || fail 'webhook receiver missing'

echo '== Alertmanager: refuses bad input'
refuse() {
  if render bad "$@" >/dev/null 2>&1; then fail "accepted bad input: $*"; fi
  echo "ok refused: $*"
}
refuse -e TELEGRAM_BOT_TOKEN="$fake_token"
refuse -e TELEGRAM_ALERT_CHAT_ID=42
refuse -e TELEGRAM_BOT_TOKEN="$fake_token" -e TELEGRAM_ALERT_CHAT_ID=@channel
refuse -e 'TELEGRAM_BOT_TOKEN=123:abc def' -e TELEGRAM_ALERT_CHAT_ID=42
refuse -e ALERT_WEBHOOK_URL=ftp://example.invalid

echo '== postgres-exporter business queries'
docker network create "$net" >/dev/null
docker run -d --name "$net-pg" --network "$net" -e POSTGRES_PASSWORD=check -e POSTGRES_DB=epharm "$pg_image" >/dev/null
for _ in $(seq 1 60); do
  docker exec "$net-pg" pg_isready -U postgres -d epharm >/dev/null 2>&1 && break
  sleep 1
done
# Only the columns the queries read; names and types as in db/migration (V014, V043).
docker exec -i "$net-pg" psql -v ON_ERROR_STOP=1 -U postgres -d epharm >/dev/null <<'SQL'
CREATE TABLE pos_sales (id VARCHAR(64) PRIMARY KEY, pharmacy_id VARCHAR(64) NOT NULL, printed_at TIMESTAMPTZ NOT NULL);
CREATE INDEX ON pos_sales (printed_at DESC, id DESC);
CREATE TABLE fulfillment_devices (id UUID PRIMARY KEY, pharmacy_id VARCHAR(64) NOT NULL, active BOOLEAN NOT NULL,
  last_seen_at TIMESTAMPTZ NOT NULL);
-- 5 sales in the last hour, 1 in the future (clock skew, not counted), 2 at 2h ago (not counted);
-- the same hour yesterday 14 sales and 3 days ago 7: average over 7 days = 3.
INSERT INTO pos_sales SELECT 'now-' || g, 'p1', now() - interval '10 minutes' FROM generate_series(1, 5) g;
INSERT INTO pos_sales VALUES ('future', 'p1', now() + interval '1 day');
INSERT INTO pos_sales SELECT 'old-' || g, 'p1', now() - interval '2 hours' FROM generate_series(1, 2) g;
INSERT INTO pos_sales SELECT 'd1-' || g, 'p1', now() - interval '1 day 20 minutes' FROM generate_series(1, 14) g;
INSERT INTO pos_sales SELECT 'd3-' || g, 'p2', now() - interval '3 days 30 minutes' FROM generate_series(1, 7) g;
-- 3 devices online in 2 pharmacies, 1 stale, 1 revoked but recent.
INSERT INTO fulfillment_devices VALUES
  (gen_random_uuid(), 'p1', true, now() - interval '1 minute'),
  (gen_random_uuid(), 'p1', true, now() - interval '2 minutes'),
  (gen_random_uuid(), 'p2', true, now()),
  (gen_random_uuid(), 'p3', true, now() - interval '1 hour'),
  (gen_random_uuid(), 'p4', false, now());
SQL
docker run -d --name "$net-exporter" --network "$net" \
  -e DATA_SOURCE_URI="$net-pg:5432/epharm?sslmode=disable" -e DATA_SOURCE_USER=postgres -e DATA_SOURCE_PASS=check \
  -e PG_EXPORTER_EXTEND_QUERY_PATH=/etc/postgres-exporter/queries.yml \
  -v "$mon/postgres-exporter/queries.yml:/etc/postgres-exporter/queries.yml:ro" \
  "$exporter_image" >/dev/null

metrics=''
for _ in $(seq 1 30); do
  metrics=$(curl -fsS http://127.0.0.1:19187/metrics 2>/dev/null || true)
  grep -q '^epharm_posm_devices_online' <<<"$metrics" && break
  sleep 1
done

value() { awk -v m="$1" '$1 == m || index($1, m "{") == 1 { print $2; exit }' <<<"$metrics"; }
expect() {
  local got; got=$(value "$1")
  awk -v g="$got" -v w="$2" 'BEGIN { exit !(g != "" && g + 0 == w + 0) }' || {
    docker logs "$net-exporter" 2>&1 | tail -n 20 >&2
    fail "$1 = '${got}', expected $2"
  }
  echo "ok $1 = $got"
}
expect epharm_pos_sales_last_hour 5
expect epharm_pos_sales_same_hour_avg_7d 3
expect epharm_posm_devices_online 3
expect epharm_posm_devices_pharmacies_online 2
expect epharm_posm_devices_active 4

echo 'monitoring checks passed'
