#!/usr/bin/env bash
# The ePharm server and admin release in about a minute (INK-199, 25.11.2026). The owner: «выкладка… за одну минуту
# должна происходить… это должно касаться только того, что не идёт в сами эти кассы по ePharm». The heavy runs (tests,
# lint, browser e2e) stay in the pull request's CI, main's CI reuses that run of the same tree (#221), and on production
# only the health and the changed pages are checked. The tills keep their own pilot (docs/05-posm-client.md): nothing
# here publishes a till package.
#
# Run main's own copy from a clone of epharm-inkar/epharm, on a computer with the VPN to 10.10.1.76:
#   git fetch -q origin && git show origin/main:tools/release/fast-release.sh | bash -s -- [options]
#
#   (no option)        release origin/main as the next patch version (v0.2.30 -> v0.2.31): the build the server made right
#                      after the merge (made now when missing), the CI gate of that exact commit, the switch
#                      (tools/release/deploy-archive-two-image.sh with its checks and smoke), the health and the --check pages
#   --check "<path>[|<text>]"  after the switch https://epharm.inkar.kz<path> must answer 200 and, with |<text>, contain
#                      the text; a whole https address works too. One for each change. Pages the admin draws in the
#                      browser have no text in their HTML: look at those by hand, a minute at most
#   --prebuild         right after a merge into main: the server builds main and makes the rollback package, detached from
#                      this connection; returns at once
#   --dry-run          everything before the switch (precheck, build, gate, the choice of the rollback package), no switch
#   --checks-only      only the health and the --check pages of what is live now
#
# The output ends with RELEASE_LIVE (with the seconds of each step) or RELEASE_STOP and its reason; the log is in
# <folder of the clone>/../night-env/release-logs/epharm-release-*.log, the server's in ~adm-quasar/epharm-ops/fast/.
#
# Before (v0.2.26-v0.2.30, 24-25.11.2026): 37-39 minutes from the merge to production. CI on main repeated the pull
# request's run (26-35 min), the server built both images for each release (290-360 s), made a fresh rollback package
# (80-95 s) and a dry run that repeated the switch's own checks (25-28 s), then switched (59-70 s).
set -uo pipefail

host="${EPHARM_HOST:-adm-quasar@10.10.1.76}"
key="${RELEASE_SSH_KEY:-$HOME/.ssh/inkar_ed25519}"
# ConnectTimeout 20 s: on 25.11.2026 the VPN took 10 s to connect and a release stopped at its first question.
ssh_options=(-i "$key" -o IdentitiesOnly=yes -o BatchMode=yes -o ConnectTimeout=20 -o ServerAliveInterval=15 -o ServerAliveCountMax=4)
incoming="${EPHARM_INCOMING:-/home/adm-quasar/epharm-ops/incoming}"
site="${EPHARM_SITE:-https://epharm.inkar.kz}"
github_api="${EPHARM_GITHUB_API:-https://api.github.com/repos/epharm-inkar/epharm}"
retry_pause="${RELEASE_RETRY_PAUSE:-5}"  # seconds between tries; 0 in tests
gate_wait="${RELEASE_GATE_WAIT_SECONDS:-1500}"
build_wait="${RELEASE_BUILD_WAIT_SECONDS:-1800}"
gate_poll="${RELEASE_GATE_POLL_SECONDS:-5}"
build_poll="${RELEASE_BUILD_POLL_SECONDS:-10}"
# What the two images and the staged release hold. A change elsewhere (the tills, the mobile app, docs) needs no server
# release; the till package goes its own way.
server_paths=(admin-panel/backend admin-panel/frontend docker-compose.prod.yml tools)
migrations_path=admin-panel/backend/src/main/resources/db/migration
declare -A took=()
mode=release checks_given=() fresh="" tag="" token="" state="" main="" remote_file="" python=""

say() { printf '%s %s\n' "$(date +%H:%M:%S)" "$*"; }
stop() { say "RELEASE_STOP: $*"; exit 1; }
mark() { local now=$SECONDS; took[$1]=$((now - mark_at)); say "RELEASE_STEP $1 ${took[$1]}s${2:+ ($2)}"; mark_at=$now; }
py() { "$python" "$@"; }
pick_python() {
  local candidate
  for candidate in python3 python; do
    if "$candidate" -c 'import json' > /dev/null 2>&1; then python="$candidate"; return 0; fi
  done
  stop "Python 3 is needed (python3 or python)"
}

# Nothing here may read this script's own input: run as `bash -s`, the script itself is that input.
remote() { ssh -n "${ssh_options[@]}" "$host" "$@"; }
# main's server half on standard input, run as root: the read-only modes.
remote_script() {
  git -C "$repo" show "$main:tools/release/fast-release-remote.sh" | ssh "${ssh_options[@]}" "$host" "sudo -n bash -s -- $*"
}
# main's server half as a file on the host: the jobs it starts run their own copy of it.
send_remote_file() {
  local copy rc
  remote_file="$incoming/fast-release-remote-${main:0:12}.sh"
  copy="$(mktemp)"
  git -C "$repo" show "$main:tools/release/fast-release-remote.sh" > "$copy" \
    && remote "mkdir -p $incoming" && scp -q "${ssh_options[@]}" "$copy" "$host:$remote_file"
  rc=$?
  rm -f "$copy"
  return "$rc"
}

# What the server holds, in one connection. The state only reads: a failure is the connection, asked again three times.
server_state() {
  local try out
  for try in 1 2 3 4; do
    if out="$(remote_script state "$main")" && grep -q '^RUN_BACKEND_TAG=' <<<"$out"; then
      printf '%s\n' "$out"
      return 0
    fi
    [ "$try" = 4 ] || { say "the server did not answer: asking again in ${retry_pause} s" >&2; sleep "$retry_pause"; }
  done
  return 1
}
value() { sed -n "s/^$1=//p" <<<"$state" | head -n 1; }
read_live() {
  run_backend_tag="$(value RUN_BACKEND_TAG)" run_backend_commit="$(value RUN_BACKEND_COMMIT)"
  run_frontend_tag="$(value RUN_FRONTEND_TAG)" run_frontend_commit="$(value RUN_FRONTEND_COMMIT)"
}
known() { git -C "$repo" cat-file -e "$1^{commit}" 2>/dev/null; }
# The files of the server's release that changed from the live commits to main.
server_changes() {
  {
    git -C "$repo" diff --name-only "$run_backend_commit" "$main" -- "${server_paths[@]}"
    git -C "$repo" diff --name-only "$run_frontend_commit" "$main" -- "${server_paths[@]}"
  } | sort -u
}
other_changes() { git -C "$repo" diff --name-only "$run_backend_commit" "$main" | cut -d/ -f1 | sort -u | head -n 8 | tr '\n' ' '; }
# A live commit that main does not hold (production never runs ahead of main), or nothing.
live_outside_main() {
  local commit
  for commit in "$run_backend_commit" "$run_frontend_commit"; do
    if ! [[ $commit =~ ^[0-9a-f]{40}$ ]] || ! known "$commit" || ! git -C "$repo" merge-base --is-ancestor "$commit" "$main"; then
      printf '%s\n' "${commit:-none}"
      return
    fi
  done
}
# The rollback package comes in addition to the nightly encrypted copy and the weekly restore drill
# (docs/20-reliability-and-release.md): said when they failed or are old, never waited for.
backup_note() {  # <state key> <hours> <what>
  local result at seconds
  result="$(value "$1_RESULT")" at="$(value "$1_AT")"
  [ -n "$result" ] || return 0
  if [ "$result" != success ]; then
    say "RELEASE_NOTE: $3 last ended «$result» (${at:-never}); docs/20-reliability-and-release.md wants it next to the rollback package"
    return 0
  fi
  [ -n "$at" ] && seconds="$(date -d "$at" +%s 2>/dev/null)" || return 0
  if (($(date +%s) - seconds > $2 * 3600)); then say "RELEASE_NOTE: $3 last ran $at, more than $2 hours ago"; fi
}
# The database migrations main adds to what the live backend has, by file name.
new_migrations() {
  git -C "$repo" diff --name-only --diff-filter=A "$run_backend_commit" "$main" -- "$migrations_path" | sed 's#.*/##' | tr '\n' ' '
}
# The next patch after the higher of the two live tags.
release_tag() {
  local higher
  higher="$(printf '%s\n' "$run_backend_tag" "$run_frontend_tag" | sort -V | tail -n 1)"
  [[ "$higher" =~ ^v([0-9]+)\.([0-9]+)\.([0-9]+)$ ]] || return 1
  printf 'v%s.%s.%s\n' "${BASH_REMATCH[1]}" "${BASH_REMATCH[2]}" "$((BASH_REMATCH[3] + 1))"
}
# The server's build of main can go live: it is built and its tag is not one that runs now.
build_ready() {
  [ "$(value PREBUILD)" = ready ] && [ "$(value PREBUILD_TAG)" != "$run_backend_tag" ] \
    && [ "$(value PREBUILD_TAG)" != "$run_frontend_tag" ]
}

# Another session's fetch may hold the lock of the remote refs for a moment.
fetch_main() {
  git -C "$repo" fetch -q origin || { sleep 3; git -C "$repo" fetch -q origin; } || stop "git fetch failed"
  main="$(git -C "$repo" rev-parse origin/main)" || stop "no origin/main in $repo"
}

# Sends main's source (LF line ends whatever this clone checks out: a CRLF archive failed the build of v0.2.25 on
# 24.11.2026) and starts the server's build, detached from this connection.
start_build() {  # <tag>
  local work archive sum out
  work="$(mktemp -d)"
  archive="$work/epharm-${main:0:12}.tar.gz"
  if ! git -C "$repo" -c core.autocrlf=false -c core.eol=lf archive --format=tar.gz -o "$archive" "$main"; then
    rm -rf "$work"
    return 1
  fi
  sum="$(sha256sum "$archive" | cut -d' ' -f1)"
  if ! send_remote_file || ! scp -q "${ssh_options[@]}" "$archive" "$host:$incoming/"; then
    rm -rf "$work"
    return 1
  fi
  rm -rf "$work"
  out="$(remote "sudo -n bash $remote_file start-prebuild $1 $main $incoming/epharm-${main:0:12}.tar.gz $sum")" \
    || { printf '%s\n' "$out"; return 1; }
  say "$out"
}

# Waits for the server's build of main, starting it when it is missing, stale or failed (once).
ensure_build() {
  local started=0 shown=-60 new deadline=$((SECONDS + build_wait))
  while ! build_ready; do
    case "$(value PREBUILD)" in
      building)
        if ((SECONDS - shown >= 60)); then say "the server builds main ${main:0:12}: $(value PREBUILD_LAST)"; shown=$SECONDS; fi
        ;;
      *)
        [ "$started" = 0 ] || { say "the server's build of main failed: $(value PREBUILD_LAST)"; return 1; }
        new="$(release_tag)" || { say "the live tag «$run_backend_tag» is not vX.Y.Z"; return 1; }
        say "the server has no build of main ${main:0:12} to release ($(value PREBUILD) $(value PREBUILD_TAG)): building $new now, 3-6 minutes (fast-release.sh --prebuild right after a merge spares this)"
        start_build "$new" || return 1
        started=1
        ;;
    esac
    ((SECONDS < deadline)) || { say "the server's build did not finish in $((build_wait / 60)) minutes"; return 1; }
    sleep "$build_poll"
    state="$(server_state)" || return 1
  done
  tag="$(value PREBUILD_TAG)"
}

# The stored git credential for github.com, when there is one (5000 questions an hour instead of 60); never printed.
github_token() {
  printf 'protocol=https\nhost=github.com\n\n' \
    | GIT_TERMINAL_PROMPT=0 GCM_INTERACTIVE=never timeout 15 git credential fill 2>/dev/null \
    | sed -n 's/^password=//p' | head -n 1
}
# success, pending, none, failure:<conclusion> or error: the P0 / merge gate of main. The transaction checks the gate
# itself before it switches; this only waits for it.
gate_state() {
  local body
  body="$({
    [ -z "$token" ] || printf 'Authorization: Bearer %s\n' "$token"
    printf 'Accept: application/vnd.github+json\nUser-Agent: epharm-fast-release\n'
  } | curl -fsS --max-time 20 -H @- "$github_api/commits/$main/check-runs?check_name=P0%20%2F%20merge%20gate&per_page=10" 2>/dev/null)" \
    || { echo error; return; }
  py -c '
import json, sys
try:
    runs = json.load(sys.stdin).get("check_runs", [])
except ValueError:
    runs = None
gates = [r for r in runs or [] if r.get("name") == "P0 / merge gate" and (r.get("app") or {}).get("slug") == "github-actions"]
if runs is None:
    print("error")
elif not gates:
    print("none")
else:
    run = max(gates, key=lambda r: r.get("id", 0))
    if run.get("status") != "completed":
        print("pending")
    else:
        print("success" if run.get("conclusion") == "success" else "failure:" + str(run.get("conclusion")))
' <<<"$body"
}
wait_gate() {
  local now said=0 deadline=$((SECONDS + gate_wait))
  while :; do
    now="$(gate_state)"
    case "$now" in
      success) say "main's CI gate is green (P0 / merge gate of ${main:0:12})"; return 0 ;;
      failure:*) say "main's CI gate is ${now#failure:} for ${main:0:12}: look at its run on GitHub"; return 1 ;;
    esac
    ((SECONDS < deadline)) || { say "main's CI gate did not finish in $((gate_wait / 60)) minutes ($now)"; return 1; }
    if [ "$said" = 0 ]; then
      say "waiting for main's CI gate ($now): under a minute when the merge reuses the pull request's run, 3 minutes when it touched the till"
      said=1
    fi
    sleep "$gate_poll"
  done
}

# The job's lines as they come. A dropped connection changes nothing on the server: following resumes after the last line.
follow_job() {  # <log> <pid>; returns the job's exit code
  local got=0 line code="" drops=0
  while :; do
    while IFS= read -r line; do
      got=$((got + 1))
      case "$line" in *FAST_EXIT=*) code="${line##*FAST_EXIT=}" ;; esac
      printf '        .76 %s\n' "$line"
    done < <(remote "sudo -n bash $remote_file follow $1 $2 $((got + 1))" 2>/dev/null)
    if [ -n "$code" ]; then
      [ "$code" = 0 ]
      return
    fi
    if state="$(server_state)" && [ -z "$(value LIVE_RUNNING)" ] && [ "$drops" -gt 0 ]; then
      say "the release on the server ended without its last line: read $1 there"
      return 1
    fi
    drops=$((drops + 1))
    [ "$drops" -le 30 ] || { say "the server stays out of reach; the release goes on there, its log is $1"; return 1; }
    say "the connection dropped; the release goes on on the server: following again in ${retry_pause} s"
    sleep "$retry_pause"
  done
}

# The switch runs detached on the server (a VPN drop in the middle of a switch would stop it halfway).
switch() {
  local out job
  send_remote_file || { say "main's server script did not reach the server"; return 1; }
  out="$(remote "sudo -n bash $remote_file start-live $tag $main $fresh")"
  job="$(sed -n 's/^LIVE_STARTED //p' <<<"$out" | head -n 1)"
  if [ -z "$job" ]; then
    # The answer may have been lost after the start: a release that runs is followed, never started twice.
    state="$(server_state)" || return 1
    job="$(value LIVE_RUNNING)"
    [ -n "$job" ] || { printf '%s\n' "$out"; return 1; }
  fi
  say "the switch runs on the server: process ${job%% *}, log ${job#* }"
  follow_job "${job#* }" "${job%% *}"
}

# The health of what is live (with the released tag and commit when given), then each --check page.
run_checks() {  # [tag] [commit]
  local want_tag="${1:-}" want_commit="${2:-}" health admin summary="" healthy=0 try page path text url code body found
  for try in 1 2 3; do
    health="$(curl -fsS --max-time 15 -H 'Cache-Control: no-cache' "$site/api/health" 2>/dev/null)"
    admin="$(curl -fsS --max-time 15 -H 'Cache-Control: no-cache' "$site/release.json" 2>/dev/null)"
    if summary="$(py -c '
import json, sys
try:
    health, admin = json.loads(sys.argv[1]), json.loads(sys.argv[2])
except ValueError:
    print("no JSON answer")
    raise SystemExit(1)
tag, commit = sys.argv[3], sys.argv[4]
print("%s, backend %s %s, admin %s %s" % (health.get("status"), health.get("releaseId"), str(health.get("commit"))[:12],
                                          admin.get("releaseId"), str(admin.get("commit"))[:12]))
ok = (health.get("status") == "ok"
      and (not tag or (health.get("releaseId") == tag and admin.get("releaseId") == tag))
      and (not commit or (health.get("commit") == commit and admin.get("commit") == commit)))
raise SystemExit(0 if ok else 1)
' "$health" "$admin" "$want_tag" "$want_commit")"; then
      healthy=1
      break
    fi
    [ "$try" = 3 ] || sleep 2
  done
  if [ "$healthy" != 1 ]; then
    say "CHECK_FAIL $site/api/health and /release.json: ${summary:-no answer}${want_tag:+ (expected $want_tag ${want_commit:0:12})}"
    return 1
  fi
  say "CHECK_OK $site/api/health and /release.json: $summary"
  for page in "${checks_given[@]}"; do
    path="${page%%|*}" text=""
    [[ "$page" != *"|"* ]] || text="${page#*|}"
    case "$path" in
      https://*) url="$path" ;;
      /*) url="$site$path" ;;
      *) say "CHECK_FAIL «$page»: a path from / or a whole https address is expected"; return 1 ;;
    esac
    body="$(mktemp)"
    found=0
    for try in 1 2 3; do
      code="$(curl -sS -L --max-redirs 3 --max-time 20 -H 'Cache-Control: no-cache' -o "$body" -w '%{http_code}' "$url" 2>/dev/null)"
      if [ "$code" = 200 ] && { [ -z "$text" ] || grep -qF -- "$text" "$body"; }; then found=1; break; fi
      [ "$try" = 3 ] || sleep 2
    done
    rm -f "$body"
    if [ "$found" = 1 ]; then
      say "CHECK_OK $url${text:+ shows «$text»}"
    else
      say "CHECK_FAIL $url: answered ${code:-nothing}${text:+, «$text» not found}"
      return 1
    fi
  done
}

take_lock() {  # one release at a time from this clone and its worktrees; a lock older than 30 minutes is a dead release's
  local waited=0
  lock_dir="$common/epharm-fast-release.lock"
  until mkdir "$lock_dir" 2>/dev/null; do
    if [ -n "$(find "$lock_dir" -maxdepth 0 -mmin +30 2>/dev/null)" ]; then rm -rf "$lock_dir"; continue; fi
    [ "$waited" != 0 ] || say "another release from this clone runs: waiting for it"
    waited=$((waited + 5))
    [ "$waited" -le 1200 ] || stop "another release still runs after 20 minutes ($lock_dir)"
    sleep 5
  done
  trap 'rm -rf "$lock_dir"' EXIT
}

prebuild() {
  fetch_main
  state="$(server_state)" || stop "the server does not answer: is the VPN up?"
  read_live
  if [ "$run_backend_commit" = "$main" ] && [ "$run_frontend_commit" = "$main" ]; then
    say "PREBUILD_SKIP: main ${main:0:12} is live"
    return 0
  fi
  if known "$run_backend_commit" && known "$run_frontend_commit" && [ -z "$(server_changes)" ]; then
    say "PREBUILD_SKIP: main ${main:0:12} changed nothing the server runs (only $(other_changes))"
    return 0
  fi
  if build_ready || [ "$(value PREBUILD)" = building ]; then
    say "PREBUILD_SKIP: the server's build of ${main:0:12} is $(value PREBUILD) ($(value PREBUILD_TAG))"
    return 0
  fi
  local new
  new="$(release_tag)" || stop "the live tag «$run_backend_tag» is not vX.Y.Z"
  start_build "$new" || stop "the build did not start"
}

release() {
  take_lock
  started_at=$SECONDS mark_at=$SECONDS
  fetch_main
  state="$(server_state)" || stop "the server does not answer: is the VPN up?"
  read_live
  say "main ${main:0:12}; live: backend $run_backend_tag (${run_backend_commit:0:12}), admin $run_frontend_tag (${run_frontend_commit:0:12})"
  local outside
  outside="$(live_outside_main)"
  [ -z "$outside" ] || stop "production runs ${outside:0:12}, which main does not hold: do not release, tell Grigoriy"
  [ -z "$(value LIVE_RUNNING)" ] || stop "a release runs on the server now ($(value LIVE_RUNNING))"
  # The nightly backup (03:20 UTC) holds the deploy lock for minutes; the transaction would refuse at once.
  local waited=0
  while [ "$(value DEPLOY_LOCK)" = busy ]; do
    [ "$waited" -lt 600 ] || stop "the deploy lock is still held after 10 minutes (a backup or another release on the server)"
    [ "$waited" != 0 ] || say "a backup or another release holds the deploy lock on the server: waiting for it"
    sleep 15
    waited=$((waited + 15))
    state="$(server_state)" || stop "the server does not answer: is the VPN up?"
  done
  [ "$(value BACKEND_HEALTH)" = healthy ] && [ "$(value FRONTEND_HEALTH)" = healthy ] \
    || stop "the live backend is $(value BACKEND_HEALTH) and the admin $(value FRONTEND_HEALTH), not both healthy: look first"
  if [ "$run_backend_commit" = "$main" ] && [ "$run_frontend_commit" = "$main" ]; then
    mark precheck
    say "main is live already: nothing to switch, checking what is live"
    run_checks "$run_backend_tag" "$main" || stop "a check failed on what is live"
    say "RELEASE_LIVE $run_backend_tag (already live), total $((SECONDS - started_at)) s"
    return 0
  fi
  local changed migrations gate_log gate_pid plan
  changed="$(server_changes)"
  if [ -z "$changed" ]; then
    mark precheck
    say "since the live ${run_backend_commit:0:12} main changed nothing the server runs (only $(other_changes)): no switch"
    run_checks "$run_backend_tag" "" || stop "a check failed on what is live"
    say "RELEASE_LIVE $run_backend_tag (main's server and admin code is live), total $((SECONDS - started_at)) s"
    return 0
  fi
  if ! git -C "$repo" diff --quiet "$run_backend_commit" "$main" -- Caddyfile; then
    say "RELEASE_NOTE: the Caddyfile changed in main; this release does not apply it (docs/06-deployment-and-ops.md, «Caddy»)"
  fi
  migrations="$(new_migrations)"
  if [ -n "$migrations" ]; then
    fresh=--fresh-package
    say "new database migrations: ${migrations}(the rollback package and its database copy are made right before the switch)"
  fi
  backup_note EPHARM_BACKUP 30 "the nightly encrypted backup (epharm-backup)"
  backup_note EPHARM_RESTORE_TEST 192 "the weekly restore drill (epharm-restore-test)"
  mark precheck "$(grep -c . <<<"$changed") server files changed"
  gate_log="$(mktemp)"
  wait_gate > "$gate_log" 2>&1 &
  gate_pid=$!
  if ! ensure_build; then
    kill "$gate_pid" 2>/dev/null
    rm -f "$gate_log"
    stop "the server's build of main failed (its log on the server: $(value PREBUILD_LOG))"
  fi
  mark build "$tag"
  if ! wait "$gate_pid"; then
    cat "$gate_log"
    rm -f "$gate_log"
    stop "main's CI gate is not green: nothing was switched"
  fi
  cat "$gate_log"
  rm -f "$gate_log"
  mark gate
  if [ "$mode" = dry-run ]; then
    plan="$(remote_script plan "$tag" "$main" "$fresh")" || stop "the server could not plan the release: $plan"
    say "RELEASE_DRY_RUN: ready to switch $run_backend_tag/$run_frontend_tag to $tag (${main:0:12}); rollback package: ${plan#PLAN_PACKAGE=}; total $((SECONDS - started_at)) s"
    return 0
  fi
  switch || stop "the switch did not go; when it had switched, the transaction put the previous images back itself (docs/06-deployment-and-ops.md)"
  mark switch
  run_checks "$tag" "$main" || stop "the switch went and a check failed: look at the page; going back is rollback-archive-two-image.sh (docs/06-deployment-and-ops.md)"
  mark checks
  say "RELEASE_LIVE $tag commit ${main:0:12}: precheck ${took[precheck]}s, build ${took[build]}s, gate ${took[gate]}s, switch ${took[switch]}s, checks ${took[checks]}s, total $((SECONDS - started_at)) s"
}

main() {
  while [ $# -gt 0 ]; do
    case "$1" in
      --check) [ $# -ge 2 ] || stop "--check needs a page"; checks_given+=("$2"); shift 2 ;;
      --check=*) checks_given+=("${1#--check=}"); shift ;;
      --prebuild) mode=prebuild; shift ;;
      --dry-run) mode=dry-run; shift ;;
      --checks-only) mode=checks-only; shift ;;
      *) stop "unknown option «$1» (the options are at the top of tools/release/fast-release.sh)" ;;
    esac
  done
  pick_python
  if [ "$mode" = checks-only ]; then run_checks; return; fi
  repo="${EPHARM_REPO:-$(git rev-parse --show-toplevel 2>/dev/null)}"
  git -C "${repo:-.}" remote get-url origin 2>/dev/null | grep -qi 'epharm-inkar/epharm' \
    || stop "run it from a clone of epharm-inkar/epharm (here: ${repo:-no git folder})"
  common="$(git -C "$repo" rev-parse --path-format=absolute --git-common-dir)" || stop "no git folder in $repo"
  if [ "$mode" = prebuild ]; then prebuild; return; fi
  [ "${RELEASE_NO_GITHUB_TOKEN:-0}" = 1 ] || token="$(github_token)"
  local workspace logs log
  workspace="$(cd "$common/../.." && pwd)"
  logs="${RELEASE_LOGS:-$workspace/night-env/release-logs}"
  mkdir -p "$logs" || stop "cannot write the log folder $logs"
  log="$logs/epharm-release-$(date +%Y%m%d-%H%M%S)-$mode.log"
  exec > >(tee -a "$log") 2>&1
  say "epharm fast-release $mode, log $log"
  release
}

# Called last, so that bash has read the whole script before anything runs (it may come on standard input).
[ "${FAST_RELEASE_SOURCED:-0}" = 1 ] || { main "$@"; exit $?; }
