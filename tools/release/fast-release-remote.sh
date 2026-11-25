#!/usr/bin/env bash
# The server half of tools/release/fast-release.sh: the ePharm server and admin release in about a minute (INK-199,
# 25.11.2026; the owner: «выкладка… за одну минуту должна происходить… это должно касаться только того, что не идёт в
# сами эти кассы по ePharm»). It runs as root on the archive host (sudo -n), sent there from the exact main commit.
#
#   state <commit>                     read only: the live pins and health, the deploy lock, the running release and the
#                                      prebuild of the commit, as KEY=VALUE lines
#   plan <tag> <commit> [--fresh-package]
#                                      read only: whether the release of that prebuild would reuse its rollback package
#   start-prebuild <tag> <commit> <archive> <archive-sha256>
#                                      starts the prebuild detached from the connection (a VPN drop does not stop it)
#   start-live <tag> <commit> [--fresh-package]
#                                      starts the release detached from the connection: LIVE_STARTED <pid> <log>
#   follow <log> <pid> <from-line>     the log of a job from that line until the job ends; its last line is FAST_EXIT=<n>
#
# The prebuild (right after a merge) unpacks the commit into a new directory, builds both images at the same time and,
# when the candidate keeps the live Compose file and nothing holds the deploy lock, makes the rollback package of the
# running release. The release stages the candidate's Compose file and tools as root (the nightly backup refuses rollback
# packages with other owners: 25.11.2026 03:38), reuses that package while it is fresh and the configuration has not
# changed since, and runs deploy-archive-two-image.sh once. The transaction makes all its checks before it switches, so
# the separate dry run that repeated them (25-28 s a release) is gone. A build waits for the build before it; a release
# waits for a running build and never runs next to another release.
set -euo pipefail
umask 077

APP="${EPHARM_APP_DIR:-/home/adm-quasar/epharm}"
OPS="${EPHARM_FAST_DIR:-/home/adm-quasar/epharm-ops/fast}"
BACKUPS="${EPHARM_BACKUP_ROOT:-/home/adm-quasar/epharm-backend-only-backups}"
BUILDS="${EPHARM_BUILD_ROOT:-/home/adm-quasar}"
# Owner of the staged Compose file and tools; tests set the caller (or nothing: keep the owner).
OWNER="${EPHARM_STAGE_OWNER-root:root}"
PACKAGE_MAX_AGE_MINUTES="${FAST_PACKAGE_MAX_AGE_MINUTES:-60}"
LOCK_WAIT_SECONDS="${FAST_LOCK_WAIT_SECONDS:-1800}"
self="$(readlink -f "$0" 2>/dev/null || printf '%s' "$0")"
saved_compose=""

say() { printf '%s %s\n' "$(date -u +%H:%M:%S)" "$*"; }
fail() { say "FAST_FAIL: $*"; exit 1; }
step_at=$SECONDS
step() { say "FAST_STEP $1 $((SECONDS - step_at))s${2:+ ($2)}"; step_at=$SECONDS; }
pin() { sed -n "s/^$1=//p" "$APP/.release.env" | tail -n 1; }
field() { sed -n "s/^$2=//p" "$1" | tail -n 1; }
image_id() { docker image inspect --format '{{.Id}}' "$1" 2>/dev/null || true; }
label() { docker image inspect --format "{{index .Config.Labels \"$2\"}}" "$1" 2>/dev/null || true; }
digest() { sha256sum "$1" | cut -d' ' -f1; }
need_tag() { [[ "$1" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail "a release tag vX.Y.Z is expected, not «$1»"; }
need_commit() { [[ "$1" =~ ^[0-9a-f]{40}$ ]] || fail "a full commit is expected, not «$1»"; }
need_file() { [[ -f "$self" ]] || fail "run this file itself, not on standard input, to start a job"; }
alive() { [[ -n "$1" ]] && kill -0 "$1" 2>/dev/null; }
# Minutes since the package's checksum manifest was written (the transaction accepts up to 24 hours).
package_age() { echo $(( ($(date +%s) - $(stat -c %Y "$1/SHA256SUMS")) / 60 )); }
container_state() {
  docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$1" 2>/dev/null \
    || echo absent
}

# Why the prebuild's rollback package cannot serve this release, or nothing when it can. The transaction checks the same
# again (age, running tags, configuration digests); this only spares it a refusal: then the release makes a new package.
package_unfit() {  # <package> <running backend tag> <running frontend tag> <candidate compose> <--fresh-package or "">
  local package="$1" pair
  if [[ -n "$5" ]]; then echo "the release brings database migrations"; return; fi
  if [[ -z "$package" ]]; then echo "the prebuild made none"; return; fi
  if [[ ! -f "$package/SHA256SUMS" ]]; then echo "the prebuild's package is gone"; return; fi
  if (( $(package_age "$package") > PACKAGE_MAX_AGE_MINUTES )); then
    echo "the prebuild's package is $(package_age "$package") min old (more than $PACKAGE_MAX_AGE_MINUTES)"; return
  fi
  if [[ ! -f "$package/backend-$2.tar.gz" || ! -f "$package/frontend-$3.tar.gz" ]]; then
    echo "the prebuild's package holds another running release"; return
  fi
  if ! cmp -s "$package/docker-compose.prod.yml" "$4"; then echo "the candidate changes the Compose file"; return; fi
  for pair in env.prod:.env.prod release.env:.release.env Caddyfile:Caddyfile; do
    if ! cmp -s "$package/${pair%%:*}" "$APP/${pair#*:}"; then echo "${pair#*:} changed since the prebuild"; return; fi
  done
}

state() {  # <commit>
  need_commit "$1"
  local twelve="${1:0:12}" env log pid job
  printf 'RUN_BACKEND_TAG=%s\nRUN_BACKEND_COMMIT=%s\n' "$(pin BACKEND_RELEASE_ID)" "$(pin BACKEND_RELEASE_COMMIT)"
  printf 'RUN_FRONTEND_TAG=%s\nRUN_FRONTEND_COMMIT=%s\n' "$(pin FRONTEND_RELEASE_ID)" "$(pin FRONTEND_RELEASE_COMMIT)"
  printf 'BACKEND_HEALTH=%s\nFRONTEND_HEALTH=%s\n' "$(container_state epharm-backend)" "$(container_state epharm-frontend)"
  if [[ -e "$APP/releases/backend-only/.deploy.lock" ]] && ! flock -n "$APP/releases/backend-only/.deploy.lock" true; then
    echo 'DEPLOY_LOCK=busy'
  else
    echo 'DEPLOY_LOCK=free'
  fi
  job="$(cat "$OPS/live.job" 2>/dev/null || true)"
  if alive "${job%% *}"; then echo "LIVE_RUNNING=$job"; fi
  # The rollback package comes in addition to the nightly encrypted copy and the weekly restore drill
  # (docs/20-reliability-and-release.md); the release says when they failed or are old, it does not run them.
  local unit name
  for unit in epharm-backup epharm-restore-test; do
    command -v systemctl > /dev/null 2>&1 || break
    name="$(tr '[:lower:]-' '[:upper:]_' <<<"$unit")"
    printf '%s_RESULT=%s\n' "$name" "$(systemctl show "$unit.service" -p Result --value 2>/dev/null || true)"
    printf '%s_AT=%s\n' "$name" "$(systemctl show "$unit.service" -p ExecMainExitTimestamp --value 2>/dev/null || true)"
  done
  env="$OPS/prebuild-$twelve.env" log="$OPS/prebuild-$twelve.log"
  pid="$(cat "$OPS/prebuild-$twelve.pid" 2>/dev/null || true)"
  if alive "$pid"; then
    echo 'PREBUILD=building'
  elif [[ -s "$env" ]]; then
    local tag package
    tag="$(field "$env" TAG)"
    if [[ "$(image_id "epharm/backend:$tag")" == "$(field "$env" BACKEND_ID)" \
       && "$(image_id "epharm/frontend:$tag")" == "$(field "$env" FRONTEND_ID)" \
       && -f "$(field "$env" BUILD)/docker-compose.prod.yml" ]]; then
      echo 'PREBUILD=ready'
    else
      # A later build took its tag, or its folder went (builds older than two days are removed).
      echo 'PREBUILD=stale'
    fi
    sed 's/^/PREBUILD_/' "$env"
    package="$(field "$env" PACKAGE)"
    if [[ -n "$package" && -f "$package/SHA256SUMS" ]]; then echo "PREBUILD_PACKAGE_AGE_MIN=$(package_age "$package")"; fi
  elif [[ -s "$log" ]]; then
    echo 'PREBUILD=failed'
  else
    echo 'PREBUILD=none'
  fi
  if [[ -s "$log" ]]; then printf 'PREBUILD_LOG=%s\nPREBUILD_LAST=%s\n' "$log" "$(tail -n 1 "$log" | tr -d '\r' | cut -c1-200)"; fi
}

plan() {  # <tag> <commit> [--fresh-package]
  need_tag "$1"
  need_commit "$2"
  local env="$OPS/prebuild-${2:0:12}.env" reason
  [[ -s "$env" && "$(field "$env" TAG)" == "$1" ]] || fail "no prebuild $1 of ${2:0:12} on this host"
  reason="$(package_unfit "$(field "$env" PACKAGE)" "$(pin BACKEND_RELEASE_ID)" "$(pin FRONTEND_RELEASE_ID)" \
    "$(field "$env" BUILD)/docker-compose.prod.yml" "${3:-}")"
  if [[ -n "$reason" ]]; then echo "PLAN_PACKAGE=new ($reason)"; else echo "PLAN_PACKAGE=reuse $(field "$env" PACKAGE)"; fi
}

# This script's own copy runs the job, so that a newer copy sent meanwhile never changes a running one.
start_job() {  # <mode> <log> <pid file> <args>...
  local mode="$1" log="$2" pidfile="$3" copy
  shift 3
  mkdir -p "$OPS/run"
  copy="$OPS/run/$mode-$(date -u +%Y%m%dT%H%M%SZ)-$$.sh"
  install -m 0700 "$self" "$copy"
  : > "$log"
  setsid nohup bash "$copy" "$mode" "$@" >> "$log" 2>&1 < /dev/null &
  echo "$!" > "$pidfile"
}

start_prebuild() {  # <tag> <commit> <archive> <archive-sha256>
  need_tag "$1"
  need_commit "$2"
  [[ -f "$3" && "${4:-}" =~ ^[0-9a-f]{64}$ ]] || fail "the source archive and its SHA-256 are expected"
  local twelve="${2:0:12}"
  if alive "$(cat "$OPS/prebuild-$twelve.pid" 2>/dev/null || true)"; then
    echo "PREBUILD_RUNNING $twelve log $OPS/prebuild-$twelve.log"
    return
  fi
  rm -f "$OPS/prebuild-$twelve.env"
  start_job prebuild "$OPS/prebuild-$twelve.log" "$OPS/prebuild-$twelve.pid" "$@"
  echo "PREBUILD_STARTED $twelve $1 log $OPS/prebuild-$twelve.log"
}

start_live() {  # <tag> <commit> [--fresh-package]
  need_tag "$1"
  need_commit "$2"
  local job log
  job="$(cat "$OPS/live.job" 2>/dev/null || true)"
  if alive "${job%% *}"; then fail "a release runs on this host already: $job"; fi
  log="$OPS/live-$1-$(date -u +%Y%m%dT%H%M%SZ).log"
  start_job live "$log" "$OPS/live.pid" "$@"
  printf '%s %s\n' "$(cat "$OPS/live.pid")" "$log" > "$OPS/live.job"
  echo "LIVE_STARTED $(cat "$OPS/live.job")"
}

follow() {  # <log> <pid> <from-line>
  local log="$1" pid="${2:-}" from="${3:-}"
  [[ "$log" == "$OPS"/*.log && -f "$log" && "$pid" =~ ^[0-9]+$ && "$from" =~ ^[0-9]+$ ]] \
    || fail "follow <log of $OPS> <pid> <from-line>"
  if alive "$pid"; then tail --pid="$pid" -n "+$from" -f "$log"; else tail -n "+$from" "$log"; fi
}

# Source folders of builds older than two days go, logs and job copies after two weeks.
tidy() {
  find "$BUILDS" -mindepth 1 -maxdepth 1 -type d -name 'epharm-fast-build-*' -mmin +2880 \
    -exec rm -rf --one-file-system {} + 2>/dev/null || true
  find "$OPS" -maxdepth 2 -type f \( -name 'prebuild-*' -o -name 'live-*.log' -o -name '*.sh' \) -mmin +20160 \
    -delete 2>/dev/null || true
}

hold_build_lock() {  # <what waits>
  exec 8>"$OPS/prebuild.lock"
  if ! flock -n 8; then
    say "waiting for $1"
    flock -w "$LOCK_WAIT_SECONDS" 8 || fail "a build held the host for $((LOCK_WAIT_SECONDS / 60)) minutes"
  fi
}

prebuild() {  # <tag> <commit> <archive> <archive-sha256>
  local tag="$1" commit="$2" archive="$3" archive_sha="$4" twelve="${2:0:12}" started=$SECONDS
  trap 'printf "FAST_EXIT=%s\n" "$?"' EXIT
  need_tag "$tag"
  need_commit "$commit"
  hold_build_lock "the build before this one"
  step lock
  tidy
  local run_backend run_frontend
  run_backend="$(pin BACKEND_RELEASE_ID)" run_frontend="$(pin FRONTEND_RELEASE_ID)"
  [[ "$tag" != "$run_backend" && "$tag" != "$run_frontend" ]] || fail "$tag runs now: the release picks the next tag"
  echo "$archive_sha  $archive" | sha256sum -c --status - || fail "the source archive does not match its SHA-256"
  local build="$BUILDS/epharm-fast-build-$twelve"
  rm -rf --one-file-system "$build"
  mkdir -p "$build"
  tar -xzf "$archive" -C "$build"
  rm -f "$archive"
  [[ -f "$build/docker-compose.prod.yml" && -d "$build/tools/release" ]] || fail "the archive holds no release source"
  step unpack
  export RELEASE_ID="$tag" RELEASE_COMMIT="$commit" BACKEND_RELEASE_ID="$tag" BACKEND_RELEASE_COMMIT="$commit" \
    FRONTEND_RELEASE_ID="$tag" FRONTEND_RELEASE_COMMIT="$commit"
  local compose=(docker compose -p epharm-build --env-file "$APP/.env.prod" -f "$build/docker-compose.prod.yml")
  local images_at=$SECONDS backend_pid frontend_pid frontend_seconds
  "${compose[@]}" build backend > "$OPS/prebuild-$twelve-backend.log" 2>&1 &
  backend_pid=$!
  "${compose[@]}" build frontend > "$OPS/prebuild-$twelve-frontend.log" 2>&1 &
  frontend_pid=$!
  if ! wait "$frontend_pid"; then
    kill "$backend_pid" 2>/dev/null || true
    tail -n 20 "$OPS/prebuild-$twelve-frontend.log"
    fail "the frontend image did not build"
  fi
  frontend_seconds=$((SECONDS - images_at))
  if ! wait "$backend_pid"; then
    tail -n 20 "$OPS/prebuild-$twelve-backend.log"
    fail "the backend image did not build"
  fi
  step images "backend $((SECONDS - images_at)) s, frontend $frontend_seconds s, at the same time"
  local component
  for component in backend frontend; do
    [[ "$(label "epharm/$component:$tag" org.opencontainers.image.revision)" == "$commit" \
       && "$(label "epharm/$component:$tag" org.opencontainers.image.version)" == "$tag" ]] \
      || fail "the $component image labels do not name $tag $commit"
  done
  local package="" out
  if ! cmp -s "$build/docker-compose.prod.yml" "$APP/docker-compose.prod.yml"; then
    say "no rollback package now: the candidate changes the Compose file, the release makes the package after staging it"
  elif ! out="$("$APP/tools/release/prepare-backend-only-backup.sh" "$run_backend" "$run_frontend" "$BACKUPS" 2>&1)"; then
    say "no rollback package now, the release makes one: $(tail -n 1 <<<"$out")"
  else
    package="$(sed -n 's/^Verified backend-only backup: //p' <<<"$out" | tail -n 1)"
    step package "$package"
  fi
  {
    printf 'TAG=%s\nCOMMIT=%s\nBUILD=%s\n' "$tag" "$commit" "$build"
    printf 'BACKEND_ID=%s\nFRONTEND_ID=%s\n' "$(image_id "epharm/backend:$tag")" "$(image_id "epharm/frontend:$tag")"
    printf 'COMPOSE_SHA=%s\n' "$(digest "$build/docker-compose.prod.yml")"
    printf 'PACKAGE=%s\nPACKAGE_FOR=%s/%s\n' "$package" "$run_backend" "$run_frontend"
    printf 'BUILD_SECONDS=%s\nBUILT_AT=%s\n' "$((SECONDS - started))" "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  } > "$OPS/prebuild-$twelve.env.tmp"
  mv -f "$OPS/prebuild-$twelve.env.tmp" "$OPS/prebuild-$twelve.env"
  say "PREBUILD_READY $twelve $tag in $((SECONDS - started)) s"
}

live() {  # <tag> <commit> [--fresh-package]
  local tag="$1" commit="$2" fresh="${3:-}" twelve="${2:0:12}"
  trap 'rc=$?; if [[ $rc -ne 0 && -n "$saved_compose" ]] && cp -p "$saved_compose" "$APP/docker-compose.prod.yml"; then
    say "the previous Compose file is back"; fi; printf "FAST_EXIT=%s\n" "$rc"' EXIT
  need_tag "$tag"
  need_commit "$commit"
  [[ -z "$fresh" || "$fresh" == --fresh-package ]] || fail "unknown option $fresh"
  exec 7>"$OPS/live.lock"
  flock -n 7 || fail "another release runs on this host"
  hold_build_lock "a build to finish (a release never runs next to a build)"
  step lock
  local env="$OPS/prebuild-$twelve.env" build backend_id frontend_id
  [[ -s "$env" ]] || fail "no prebuild of $twelve on this host: fast-release.sh builds first"
  [[ "$(field "$env" TAG)" == "$tag" && "$(field "$env" COMMIT)" == "$commit" ]] \
    || fail "the prebuild of $twelve is $(field "$env" TAG), not $tag"
  build="$(field "$env" BUILD)" backend_id="$(field "$env" BACKEND_ID)" frontend_id="$(field "$env" FRONTEND_ID)"
  [[ "$(image_id "epharm/backend:$tag")" == "$backend_id" && "$(image_id "epharm/frontend:$tag")" == "$frontend_id" ]] \
    || fail "the images tagged $tag are no longer the prebuild's"
  [[ -f "$build/docker-compose.prod.yml" && "$(digest "$build/docker-compose.prod.yml")" == "$(field "$env" COMPOSE_SHA)" ]] \
    || fail "the prebuild's source folder changed or went"
  local run_backend run_frontend
  run_backend="$(pin BACKEND_RELEASE_ID)" run_frontend="$(pin FRONTEND_RELEASE_ID)"
  need_tag "$run_backend"
  need_tag "$run_frontend"
  [[ "$run_backend" != "$tag" && "$run_frontend" != "$tag" ]] || fail "$tag runs already"
  step checked

  # The transaction runs the candidate's own tools and needs its Compose file live; both stay root-owned.
  local stamp
  stamp="$(date -u +%Y%m%dT%H%M%SZ)"
  mkdir -p "$APP/releases/staged"
  tar -czf "$APP/releases/staged/pre-$tag-$stamp.tar.gz" -C "$APP" docker-compose.prod.yml tools
  saved_compose="$APP/releases/staged/docker-compose.prod.yml.before-$tag-$stamp"
  cp -p "$APP/docker-compose.prod.yml" "$saved_compose"
  if [[ -n "$OWNER" ]]; then
    install -o "${OWNER%%:*}" -g "${OWNER##*:}" -m 0644 "$build/docker-compose.prod.yml" "$APP/docker-compose.prod.yml"
  else
    install -m 0644 "$build/docker-compose.prod.yml" "$APP/docker-compose.prod.yml"
  fi
  cp -a "$build/tools/." "$APP/tools/"
  if [[ -n "$OWNER" ]]; then chown -R "$OWNER" "$APP/tools"; fi
  chmod -R go-w "$APP/tools"
  step staged "the previous Compose file and tools: releases/staged/pre-$tag-$stamp.tar.gz"

  local package reason out
  package="$(field "$env" PACKAGE)"
  reason="$(package_unfit "$package" "$run_backend" "$run_frontend" "$APP/docker-compose.prod.yml" "$fresh")"
  if [[ -n "$reason" ]]; then
    out="$("$APP/tools/release/prepare-backend-only-backup.sh" "$run_backend" "$run_frontend" "$BACKUPS" 2>&1)" \
      || { printf '%s\n' "$out" | tail -n 5; fail "the rollback package was not made"; }
    package="$(sed -n 's/^Verified backend-only backup: //p' <<<"$out" | tail -n 1)"
    [[ -n "$package" && -d "$package" ]] || fail "the rollback package names no folder"
    step package "made now: $reason"
  else
    step package "made by the prebuild $(package_age "$package") min ago: $package"
  fi

  local compose_sha rc
  compose_sha="$(digest "$APP/docker-compose.prod.yml")"
  local args=("$tag" "$tag" "$commit" "$backend_id" "$frontend_id" "$run_backend" "$run_frontend" "$package" "$compose_sha")
  say "the transaction starts: $run_backend/$run_frontend -> $tag ($twelve)"
  cd "$APP"
  set +e
  "$APP/tools/release/deploy-archive-two-image.sh" "${args[@]}" 2>&1 | while IFS= read -r line; do say "$line"; done
  rc=${PIPESTATUS[0]}
  set -e
  [[ "$rc" == 0 ]] || fail "the transaction stopped (exit $rc): when it had switched, it put the previous images back itself"
  step switch "$tag"
  say "FAST_LIVE $tag $commit"
}

main() {
  local mode="${1:-}"
  [[ $# -eq 0 ]] || shift
  case "$mode" in
    state) state "$@" ;;
    plan) plan "$@" ;;
    follow) follow "$@" ;;
    start-prebuild) need_file; mkdir -p "$OPS"; start_prebuild "$@" ;;
    start-live) need_file; mkdir -p "$OPS"; start_live "$@" ;;
    prebuild) prebuild "$@" ;;
    live) live "$@" ;;
    *) echo 'usage: fast-release-remote.sh state|plan|start-prebuild|start-live|follow … (see the top of the file)' >&2; exit 2 ;;
  esac
}

# On one line, so that bash has read the whole file before anything runs (it may come on standard input).
main "$@"; exit $?
