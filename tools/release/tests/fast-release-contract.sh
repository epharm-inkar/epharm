#!/usr/bin/env bash
# Exercises tools/release/fast-release.sh and fast-release-remote.sh end to end against a fake host: ssh, scp and sudo
# run the server half here, Docker is a fake daemon, curl answers for the site and for GitHub's CI gate, and the
# transaction and the rollback package are stand-ins that check what the real ones check about the staged files.
# No network, no Docker. The release runs the way the release session runs it: main's copy on standard input.
set -euo pipefail
umask 022

source_root="$(cd "$(dirname "$0")/../../.." && pwd)"
t="$(mktemp -d "${TMPDIR:-/tmp}/epharm-fast-release.XXXXXXXX")"
trap 'rm -rf -- "$t"' EXIT
fail() { echo "FAIL: $*" >&2; exit 1; }
mkdir -p "$t/bin" "$t/docker" "$t/logs" "$t/host/epharm/releases" "$t/host/epharm-backend-only-backups" \
  "$t/host/fast" "$t/host/incoming" "$t/host/builds"
: > "$t/calls"

export TEST_ROOT="$t" EPHARM_APP_DIR="$t/host/epharm" EPHARM_FAST_DIR="$t/host/fast" \
  EPHARM_BACKUP_ROOT="$t/host/epharm-backend-only-backups" EPHARM_BUILD_ROOT="$t/host/builds" \
  EPHARM_INCOMING="$t/host/incoming" EPHARM_SITE="https://epharm.test" \
  EPHARM_GITHUB_API="https://api.github.test/repos/epharm-inkar/epharm" RELEASE_LOGS="$t/logs" \
  RELEASE_RETRY_PAUSE=0 RELEASE_NO_GITHUB_TOKEN=1 RELEASE_GATE_POLL_SECONDS=0 RELEASE_BUILD_POLL_SECONDS=1 \
  RELEASE_BUILD_WAIT_SECONDS=120 RELEASE_GATE_WAIT_SECONDS=60 GIT_TERMINAL_PROMPT=0
# Linux (CI) checks the owners the release sets; elsewhere (Git Bash) owners are not real, so they are kept.
if [ "$(uname -s)" = Linux ]; then
  EPHARM_STAGE_OWNER="$(id -un):$(id -gn)"
else
  EPHARM_STAGE_OWNER=""
fi
export EPHARM_STAGE_OWNER
export PATH="$t/bin:$PATH"

cat > "$t/bin/docker" <<'STUB'
#!/usr/bin/env bash
# A fake Docker daemon: an image is a file under $TEST_ROOT/docker, a container is healthy unless TEST_HEALTH says.
set -euo pipefail
db="$TEST_ROOT/docker"
image_file() { local name="${1%%:*}"; printf '%s/%s_%s' "$db" "${name//\//_}" "${1##*:}"; }
case "${1:-}" in
  image)
    [ "${2:-}" = inspect ] && [ "${3:-}" = --format ] || exit 1
    file="$(image_file "$5")"
    [ -f "$file" ] || exit 1
    case "$4" in
      '{{.Id}}') sed -n 's/^id=//p' "$file" ;;
      *image.revision*) sed -n 's/^revision=//p' "$file" ;;
      *image.version*) sed -n 's/^version=//p' "$file" ;;
      *) exit 1 ;;
    esac
    ;;
  inspect) echo "${TEST_HEALTH:-healthy}" ;;
  compose)
    service="${*: -1}"
    case "$service" in backend | frontend) ;; *) exit 1 ;; esac
    [ "${TEST_BUILD_FAIL:-}" != "$service" ] || { echo "fake build of $service failed"; exit 1; }
    upper="$(tr '[:lower:]' '[:upper:]' <<<"$service")"
    tag_name="${upper}_RELEASE_ID" commit_name="${upper}_RELEASE_COMMIT"
    tag="${!tag_name}" commit="${!commit_name}"
    id="sha256:$(printf '%s %s %s' "$service" "$tag" "$commit" | sha256sum | cut -d' ' -f1)"
    printf 'id=%s\nrevision=%s\nversion=%s\n' "$id" "$commit" "$tag" > "$(image_file "epharm/$service:$tag")"
    echo "build $service $tag $commit" >> "$TEST_ROOT/calls"
    ;;
  *) exit 1 ;;
esac
STUB

cat > "$t/bin/ssh" <<'STUB'
#!/usr/bin/env bash
# A fake ssh: the command runs here, as if on the host.
no_input=0
while [ $# -gt 0 ]; do
  case "$1" in -n) no_input=1; shift ;; -i | -o) shift 2 ;; -*) shift ;; *) break ;; esac
done
shift
[ "${TEST_SSH_DOWN:-0}" != 1 ] || { echo 'ssh: connect to host port 22: Connection timed out' >&2; exit 255; }
if [ "$no_input" = 1 ]; then exec bash -c "$*" < /dev/null; fi
exec bash -c "$*"
STUB

cat > "$t/bin/scp" <<'STUB'
#!/usr/bin/env bash
# A fake scp to the fake host: the last argument is host:destination.
files=()
while [ $# -gt 0 ]; do
  case "$1" in -q) shift ;; -i | -o) shift 2 ;; *) files+=("$1"); shift ;; esac
done
last=$((${#files[@]} - 1))
destination="${files[$last]#*:}"
unset "files[$last]"
cp "${files[@]}" "$destination"
STUB

cat > "$t/bin/sudo" <<'STUB'
#!/usr/bin/env bash
[ "${1:-}" != -n ] || shift
exec "$@"
STUB

cat > "$t/bin/curl" <<'STUB'
#!/usr/bin/env bash
# A fake curl: the site answers from the host's pins, GitHub with the CI gate of TEST_GATE after TEST_GATE_PENDING tries.
set -uo pipefail
out="" write="" url="" fail_http=0
while [ $# -gt 0 ]; do
  case "$1" in
    -o) out="$2"; shift 2 ;;
    -w) write="$2"; shift 2 ;;
    -H) [ "$2" != @- ] || cat > /dev/null; shift 2 ;;
    --max-time | --max-redirs | --connect-timeout) shift 2 ;;
    http://* | https://*) url="$1"; shift ;;
    -*) if [[ "$1" =~ ^-[A-Za-z]*f ]]; then fail_http=1; fi; shift ;;
    *) shift ;;
  esac
done
pin() { sed -n "s/^$1=//p" "$TEST_ROOT/host/epharm/.release.env" | tail -n 1; }
code=200
case "$url" in
  */api/health) body="{\"status\":\"ok\",\"releaseId\":\"$(pin BACKEND_RELEASE_ID)\",\"commit\":\"$(pin BACKEND_RELEASE_COMMIT)\"}" ;;
  */release.json) body="{\"releaseId\":\"$(pin FRONTEND_RELEASE_ID)\",\"commit\":\"$(pin FRONTEND_RELEASE_COMMIT)\"}" ;;
  */learn) body='<html><body>Курсы для фармацевтов</body></html>' ;;
  *check-runs*)
    count=$(($(cat "$TEST_ROOT/gate-count" 2>/dev/null || echo 0) + 1))
    echo "$count" > "$TEST_ROOT/gate-count"
    if [ "$count" -le "${TEST_GATE_PENDING:-0}" ]; then status=in_progress conclusion=null
    else status=completed conclusion="\"${TEST_GATE:-success}\""; fi
    body="{\"total_count\":1,\"check_runs\":[{\"id\":7,\"name\":\"P0 / merge gate\",\"status\":\"$status\",\"conclusion\":$conclusion,\"app\":{\"slug\":\"github-actions\"}}]}"
    ;;
  *) code=404 body='not found' ;;
esac
if [ -n "$out" ]; then printf '%s' "$body" > "$out"; else printf '%s' "$body"; fi
[ -z "$write" ] || printf '%s' "$code"
[ "$fail_http" = 0 ] || [ "$code" -lt 400 ] || exit 22
exit 0
STUB

# Git Bash has neither flock nor setsid, and its python3 may be the store's placeholder.
if ! command -v flock > /dev/null 2>&1; then
  printf '#!/usr/bin/env bash\nwhile [ $# -gt 0 ]; do case "$1" in -n) shift ;; -w) shift 2 ;; *) break ;; esac; done\n[ $# -le 1 ] && exit 0\nshift\nexec "$@"\n' > "$t/bin/flock"
fi
if ! command -v setsid > /dev/null 2>&1; then printf '#!/usr/bin/env bash\nexec "$@"\n' > "$t/bin/setsid"; fi
if ! python3 -c 'import json' > /dev/null 2>&1 && python -c 'import json' > /dev/null 2>&1; then
  printf '#!/usr/bin/env bash\nexec python "$@"\n' > "$t/bin/python3"
fi
chmod +x "$t/bin/"*

# The repository: main's tools are the real fast-release scripts and stand-ins for the transaction and the package.
git init -q --bare "$t/origin/epharm-inkar/epharm.git"
git init -q "$t/clone"
clone="$t/clone"
git -C "$clone" config user.email test@example.invalid
git -C "$clone" config user.name test
git -C "$clone" config core.autocrlf false
git -C "$clone" remote add origin "$t/origin/epharm-inkar/epharm.git"
mkdir -p "$clone/tools/release" "$clone/admin-panel/backend/src/main/resources/db/migration" "$clone/admin-panel/frontend" \
  "$clone/docs" "$clone/App"
cp "$source_root/tools/release/fast-release.sh" "$source_root/tools/release/fast-release-remote.sh" "$clone/tools/release/"
cat > "$clone/tools/release/prepare-backend-only-backup.sh" <<'STUB'
#!/usr/bin/env bash
# Stand-in for the rollback package of the running release: copies the live configuration like the real one.
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
[ -z "${TEST_PACKAGE_BUSY:-}" ] || { echo 'ERROR: another backend-only backup/deploy is active' >&2; exit 1; }
dir="$3/pre-backend-only-$(date -u +%Y%m%dT%H%M%SZ)-$RANDOM$RANDOM"
mkdir -p "$dir"
cp "$root/.env.prod" "$dir/env.prod"
cp "$root/.release.env" "$dir/release.env"
cp "$root/docker-compose.prod.yml" "$dir/docker-compose.prod.yml"
cp "$root/Caddyfile" "$dir/Caddyfile"
printf 'dump\n' > "$dir/epharm.dump"
printf 'b\n' > "$dir/backend-$1.tar.gz"
printf 'f\n' > "$dir/frontend-$2.tar.gz"
(cd "$dir" && sha256sum epharm.dump env.prod > SHA256SUMS)
echo "package $1 $2" >> "$TEST_ROOT/calls"
echo "Verified backend-only backup: $dir"
STUB
cat > "$clone/tools/release/deploy-archive-two-image.sh" <<'STUB'
#!/usr/bin/env bash
# Stand-in for the two-image transaction: what the real one refuses about the package, the Compose file and the images.
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
echo "transaction $*" >> "$TEST_ROOT/calls"
[ "$#" = 9 ] || { echo "ERROR: 9 arguments expected, got $#" >&2; exit 2; }
for pair in env.prod:.env.prod release.env:.release.env docker-compose.prod.yml:docker-compose.prod.yml Caddyfile:Caddyfile; do
  cmp -s "$8/${pair%%:*}" "$root/${pair#*:}" || { echo "ERROR: ${pair#*:} changed after the verified backup" >&2; exit 1; }
done
[ "$(sha256sum "$root/docker-compose.prod.yml" | cut -d' ' -f1)" = "$9" ] || { echo 'ERROR: live Compose differs from the reviewed source' >&2; exit 1; }
[ "$(docker image inspect --format '{{.Id}}' "epharm/backend:$1")" = "$4" ] || { echo 'ERROR: candidate image IDs differ' >&2; exit 1; }
[ "$(docker image inspect --format '{{.Id}}' "epharm/frontend:$2")" = "$5" ] || { echo 'ERROR: candidate image IDs differ' >&2; exit 1; }
echo "Smoke OK: backend/frontend report $6/$7"
if [ -n "${TEST_TRANSACTION_FAIL:-}" ]; then
  echo 'ERROR: two-image release failed; checking the protected predecessor before rollback' >&2
  echo "Rollback verified: $6/$7" >&2
  exit 1
fi
printf 'RELEASE_ID=%s\nRELEASE_COMMIT=%s\nBACKEND_RELEASE_ID=%s\nBACKEND_RELEASE_COMMIT=%s\nFRONTEND_RELEASE_ID=%s\nFRONTEND_RELEASE_COMMIT=%s\n' \
  "$1" "$3" "$1" "$3" "$2" "$3" > "$root/.release.env"
echo "Archive two-image release verified: $1/$2"
STUB
chmod +x "$clone/tools/release/"*.sh
printf 'services:\n  backend: {image: one}\n' > "$clone/docker-compose.prod.yml"
printf 'epharm.test\n' > "$clone/Caddyfile"
printf 'fun one() = 1\n' > "$clone/admin-panel/backend/Main.kt"
printf 'create table one();\n' > "$clone/admin-panel/backend/src/main/resources/db/migration/V001__one.sql"
printf 'export const one = 1\n' > "$clone/admin-panel/frontend/main.ts"
printf 'docs\n' > "$clone/docs/release.md"
commit() {
  git -C "$clone" add -A
  git -C "$clone" commit -qm "$1"
  git -C "$clone" push -q origin HEAD:main
  git -C "$clone" rev-parse HEAD
}
c0="$(commit "v0.2.30 is live")"

# The host runs c0 as v0.2.30.
cp "$clone/docker-compose.prod.yml" "$clone/Caddyfile" "$t/host/epharm/"
cp -R "$clone/tools" "$t/host/epharm/tools"
printf 'JWT_SECRET=fixture-never-log\n' > "$t/host/epharm/.env.prod"
printf 'RELEASE_ID=v0.2.30\nRELEASE_COMMIT=%s\nBACKEND_RELEASE_ID=v0.2.30\nBACKEND_RELEASE_COMMIT=%s\nFRONTEND_RELEASE_ID=v0.2.30\nFRONTEND_RELEASE_COMMIT=%s\n' \
  "$c0" "$c0" "$c0" > "$t/host/epharm/.release.env"

out="" rc=0
run() {  # the release as the release session runs it: main's copy on standard input, from the clone
  rm -f "$t/gate-count"
  if out="$(cd "$clone" && git fetch -q origin && git show origin/main:tools/release/fast-release.sh | bash -s -- "$@" 2>&1)"; then rc=0; else rc=$?; fi
}
expect() { grep -qF -- "$1" <<<"$out" || { printf '%s\n' "$out" >&2; fail "expected «$1»"; }; }
refuse() { if grep -qF -- "$1" <<<"$out"; then printf '%s\n' "$out" >&2; fail "unexpected «$1»"; fi; }
calls() { grep -c -- "^$1" "$t/calls" || true; }
live_tag() { sed -n 's/^BACKEND_RELEASE_ID=//p' "$t/host/epharm/.release.env"; }
wait_prebuild() {  # <commit>
  local i
  for i in $(seq 1 150); do
    if grep -q '^FAST_EXIT=' "$t/host/fast/prebuild-${1:0:12}.log" 2> /dev/null; then return 0; fi
    sleep 0.2
  done
  fail "the prebuild of ${1:0:12} did not end (try $i)"
}

# 1. Only docs changed: nothing to build and nothing to switch.
printf 'docs, more\n' > "$clone/docs/release.md"
c1="$(commit "docs only")"
run --prebuild
[ "$rc" = 0 ] || fail "prebuild of docs only: exit $rc"
expect "PREBUILD_SKIP: main ${c1:0:12} changed nothing the server runs"
run
[ "$rc" = 0 ] || fail "release of docs only: exit $rc"
expect "RELEASE_LIVE v0.2.30 (main's server and admin code is live)"
[ "$(calls transaction)" = 0 ] && [ "$(calls build)" = 0 ] || fail "docs only built or switched"

# 2. A backend change: the prebuild right after the merge builds both images and the package; the release reuses
#    both, runs the transaction once (no dry run) and checks the health and the page given.
printf 'fun one() = 2\n' > "$clone/admin-panel/backend/Main.kt"
c2="$(commit "backend change")"
run --prebuild
[ "$rc" = 0 ] || fail "prebuild: exit $rc"
expect "PREBUILD_STARTED ${c2:0:12} v0.2.31"
wait_prebuild "$c2"
grep -q "PREBUILD_READY ${c2:0:12} v0.2.31" "$t/host/fast/prebuild-${c2:0:12}.log" || fail "prebuild not ready: $(cat "$t/host/fast/prebuild-${c2:0:12}.log")"
[ "$(calls "build backend v0.2.31 $c2")" = 1 ] && [ "$(calls "build frontend v0.2.31 $c2")" = 1 ] || fail "both images were not built"
[ "$(calls package)" = 1 ] || fail "the prebuild made no rollback package"
run --prebuild
expect "PREBUILD_SKIP: the server's build of ${c2:0:12} is ready (v0.2.31)"
run --check '/learn|Курсы'
[ "$rc" = 0 ] || fail "release v0.2.31: exit $rc"
expect "RELEASE_LIVE v0.2.31 commit ${c2:0:12}"
expect "RELEASE_STEP switch"
expect "made by the prebuild"
expect "CHECK_OK https://epharm.test/learn shows «Курсы»"
[ "$(live_tag)" = v0.2.31 ] || fail "v0.2.31 is not live"
[ "$(calls transaction)" = 1 ] && [ "$(calls package)" = 1 ] || fail "the release ran the transaction twice or made a second package"
grep -q -- '--dry-run' "$t/calls" && fail "a dry run ran"
ls "$t/host/epharm/releases/staged/"pre-v0.2.31-*.tar.gz > /dev/null || fail "the previous Compose file and tools were not kept"
ls "$t/logs/"epharm-release-*-release.log > /dev/null || fail "no release log"
if [ -n "$EPHARM_STAGE_OWNER" ]; then
  [ "$(stat -c %U:%G "$t/host/epharm/docker-compose.prod.yml")" = "$EPHARM_STAGE_OWNER" ] || fail "the staged Compose file has another owner"
  [ -z "$(find "$t/host/epharm/tools" -perm /022 -print -quit)" ] || fail "the staged tools are writable by others"
fi

# 3. A new Compose file and a database migration: no package at the prebuild (it would hold the old Compose file);
#    the release makes it after staging and says why.
printf 'services:\n  backend: {image: two}\n' > "$clone/docker-compose.prod.yml"
printf 'create table two();\n' > "$clone/admin-panel/backend/src/main/resources/db/migration/V002__two.sql"
c3="$(commit "compose and a migration")"
run --prebuild
wait_prebuild "$c3"
grep -q "no rollback package now: the candidate changes the Compose file" "$t/host/fast/prebuild-${c3:0:12}.log" \
  || fail "the prebuild made a package for a changed Compose file"
run
[ "$rc" = 0 ] || fail "release v0.2.32: exit $rc"
expect "new database migrations: V002__two.sql"
expect "made now: the release brings database migrations"
expect "RELEASE_LIVE v0.2.32 commit ${c3:0:12}"
cmp -s "$t/host/epharm/docker-compose.prod.yml" "$clone/docker-compose.prod.yml" || fail "the new Compose file is not live"
[ "$(calls package)" = 2 ] || fail "the release did not make its own package"

# 4. The configuration changed after the prebuild and the gate is still running: a new package, and the release waits.
printf 'export const one = 2\n' > "$clone/admin-panel/frontend/main.ts"
c4="$(commit "frontend change")"
run --prebuild
wait_prebuild "$c4"
printf 'JWT_SECRET=fixture-never-log\nPOSM_SALE_BONUS_PER_SALE=10\n' > "$t/host/epharm/.env.prod"
TEST_GATE_PENDING=3 run
[ "$rc" = 0 ] || fail "release v0.2.33: exit $rc"
expect "waiting for main's CI gate (pending)"
expect "made now: .env.prod changed since the prebuild"
expect "RELEASE_LIVE v0.2.33 commit ${c4:0:12}"

# 5. No prebuild at all: the release builds main itself, then goes on.
printf 'export const one = 3\n' > "$clone/admin-panel/frontend/main.ts"
c5="$(commit "frontend change, merged without a prebuild")"
run
[ "$rc" = 0 ] || fail "release v0.2.34 without a prebuild: exit $rc"
expect "building v0.2.34 now"
expect "RELEASE_LIVE v0.2.34 commit ${c5:0:12}"

# 6. The transaction fails: the release stops and the previous Compose file is back.
printf 'services:\n  backend: {image: three}\n' > "$clone/docker-compose.prod.yml"
c6="$(commit "a change that fails its smoke")"
run --prebuild
wait_prebuild "$c6"
cp "$t/host/epharm/docker-compose.prod.yml" "$t/compose-before"
TEST_TRANSACTION_FAIL=1 run
[ "$rc" != 0 ] || fail "a failed transaction ended with 0"
expect "FAST_FAIL: the transaction stopped"
expect "the previous Compose file is back"
expect "RELEASE_STOP: the switch did not go"
cmp -s "$t/host/epharm/docker-compose.prod.yml" "$t/compose-before" || fail "the previous Compose file was not restored"
[ "$(live_tag)" = v0.2.34 ] || fail "a failed release changed the pins"

# 7. Production runs a commit main does not hold: no release.
cp "$t/host/epharm/.release.env" "$t/pins-before"
sed -i "s/^BACKEND_RELEASE_COMMIT=.*/BACKEND_RELEASE_COMMIT=$(printf 'f%.0s' {1..40})/" "$t/host/epharm/.release.env"
transactions="$(calls transaction)"
run
[ "$rc" != 0 ] || fail "a release went with production ahead of main"
expect "RELEASE_STOP: production runs ffffffffffff, which main does not hold"
[ "$(calls transaction)" = "$transactions" ] || fail "production ahead of main still switched"
cp "$t/pins-before" "$t/host/epharm/.release.env"

# 8. A red gate stops before the switch; a dry run plans without switching.
TEST_GATE=failure run
[ "$rc" != 0 ] || fail "a release went with a red gate"
expect "RELEASE_STOP: main's CI gate is not green"
run --dry-run
[ "$rc" = 0 ] || fail "dry run: exit $rc"
expect "RELEASE_DRY_RUN: ready to switch v0.2.34/v0.2.34 to v0.2.35 (${c6:0:12}); rollback package: new (the prebuild made none)"
[ "$(calls transaction)" = "$transactions" ] || fail "a red gate or a dry run switched"

# 9. The release of c6 goes; then main is live and a release only checks.
run
[ "$rc" = 0 ] || fail "release v0.2.35: exit $rc"
expect "RELEASE_LIVE v0.2.35 commit ${c6:0:12}"
run
[ "$rc" = 0 ] || fail "release of what is live: exit $rc"
expect "RELEASE_LIVE v0.2.35 (already live)"

# The server half refuses to build the tag that runs.
git -C "$clone" -c core.autocrlf=false archive --format=tar.gz -o "$t/again.tar.gz" "$c6"
if bash "$clone/tools/release/fast-release-remote.sh" prebuild v0.2.35 "$c6" "$t/again.tar.gz" \
  "$(sha256sum "$t/again.tar.gz" | cut -d' ' -f1)" > "$t/again.log" 2>&1; then
  fail "the server built the tag that runs"
fi
grep -q 'FAST_FAIL: v0.2.35 runs now' "$t/again.log" || fail "no reason for the refused build: $(cat "$t/again.log")"

echo "fast-release contract OK"
