#!/bin/sh
# Renders the Alertmanager config from environment and starts Alertmanager.
#
# Receivers (any combination; none means alerts stay visible in Alertmanager only):
#   TELEGRAM_BOT_TOKEN + TELEGRAM_ALERT_CHAT_ID  Telegram bot message (the storefront's alert bot)
#   ALERT_WEBHOOK_URL                             generic http(s) webhook
#
# The bot token never lands in the rendered config: it is written to a 0600 file and referenced
# with bot_token_file. ALERTMANAGER_VALIDATE_ONLY=true only checks the rendered config.
set -eu

workdir=${ALERTMANAGER_RENDER_DIR:-/tmp}
config="$workdir/alertmanager.yml"
template="$workdir/epharm-telegram.tmpl"
token_file="$workdir/telegram-bot-token"
webhook=${ALERT_WEBHOOK_URL:-}
telegram_token=${TELEGRAM_BOT_TOKEN:-}
telegram_chat=${TELEGRAM_ALERT_CHAT_ID:-}

receiver_body=''

case "$webhook" in
  '') ;;
  http://*|https://*)
    case "$webhook" in
      *\"*|*\'*|*' '*) echo 'ERROR: ALERT_WEBHOOK_URL must not contain quotes or spaces' >&2; exit 1 ;;
    esac
    receiver_body="$receiver_body
    webhook_configs:
      - url: \"$webhook\"
        send_resolved: true"
    ;;
  *) echo 'ERROR: ALERT_WEBHOOK_URL must be an http(s) URL' >&2; exit 1 ;;
esac

if [ -n "$telegram_token" ] || [ -n "$telegram_chat" ]; then
  if [ -z "$telegram_token" ] || [ -z "$telegram_chat" ]; then
    echo 'ERROR: set both TELEGRAM_BOT_TOKEN and TELEGRAM_ALERT_CHAT_ID, or neither' >&2
    exit 1
  fi
  if ! printf '%s' "$telegram_token" | grep -Eq '^[0-9]+:[A-Za-z0-9_-]+$'; then
    echo 'ERROR: TELEGRAM_BOT_TOKEN does not look like a bot token (digits:letters)' >&2
    exit 1
  fi
  if ! printf '%s' "$telegram_chat" | grep -Eq '^-?[0-9]+$'; then
    echo 'ERROR: TELEGRAM_ALERT_CHAT_ID must be a numeric chat id' >&2
    exit 1
  fi
  umask 077
  printf '%s' "$telegram_token" > "$token_file"
  umask 022
  receiver_body="$receiver_body
    telegram_configs:
      - bot_token_file: '$token_file'
        chat_id: $telegram_chat
        parse_mode: HTML
        message: '{{ template \"epharm.telegram.message\" . }}'
        send_resolved: true"
fi

if [ -z "$receiver_body" ]; then
  echo 'WARNING: no TELEGRAM_BOT_TOKEN/TELEGRAM_ALERT_CHAT_ID or ALERT_WEBHOOK_URL; alerts remain visible in Alertmanager but no external notification is sent.' >&2
fi

# Text/template: `html` escapes annotation text for Telegram's HTML mode.
cat > "$template" <<'EOF'
{{ define "epharm.telegram.message" -}}
{{ if eq .Status "firing" }}🔴 <b>ePharm: тревога</b>{{ else }}✅ <b>ePharm: снято</b>{{ end }}
{{ range .Alerts -}}
• <b>{{ .Annotations.summary | html }}</b>{{ if eq .Labels.severity "critical" }} (срочно){{ end }}
{{ .Annotations.description | html }}
{{ end -}}
{{- end }}
EOF

cat > "$config" <<EOF
global:
  resolve_timeout: 5m

templates:
  - '$template'

route:
  receiver: ops
  group_by: [alertname, severity]
  group_wait: 30s
  group_interval: 5m
  repeat_interval: 4h
  routes:
    - matchers: [severity="critical"]
      repeat_interval: 30m

inhibit_rules:
  - source_matchers: [severity="critical"]
    target_matchers: [severity="warning"]
    equal: [alertname, instance]
  # A dead backend or database also empties the sales and device metrics: one message, not three.
  - source_matchers: [alertname=~"EpharmBackendDown|EpharmPostgresDown|EpharmPublicEndpointDown"]
    target_matchers: [team="business"]

receivers:
  - name: ops$receiver_body
EOF

if [ "${ALERTMANAGER_VALIDATE_ONLY:-false}" = true ]; then
  exec /bin/amtool check-config "$config"
fi

exec /bin/alertmanager --config.file="$config" --storage.path=/alertmanager "$@"
