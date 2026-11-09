# Isolated recommendation reward load test

Exercises the real HTTP → PostgreSQL → pending bonus → approved receipt → `receipt_bonus` wallet path. There are no seeded recommendations, paid sales, pending bonuses, reward cases or wallet entries. Real `/recommend` and visible-window ACK requests create the evidence. This is a synthetic adapter fixture with **proven full-package units for the test only**; it does not prove the real Standard-N adapter can establish those units. Unknown real units must still go to review.

No production origin, remote address, redirects, public catalog, SMS, mobile/Flutter test, existing pharmacist or production secret is used. The runner accepts only an explicit `http://127.0.0.1:PORT` or IPv6 loopback IP. There is no override. Never point the loopback port at a proxy/SSH forward. Fresh individual fixture keys cannot authorize against a real database. Seed and oracle SQL additionally refuse any database name other than the unique generated `epharm_reward_load_<run>`.

## Workload and correctness

- Default: **1500 distinct authenticated individual devices and pharmacists in 500 synthetic pharmacies**. Same raw Standard-N USER IDs can occur in different pharmacies; all mappings are explicit, active and pharmacy scoped. Mapping fixture inserts stand for an HQ-approved mapping, not automated name matching. No RPS password/SMS registration is part of this monetary test.
- Actors arrive over 60 real seconds. They stay in a checkout for at least 125 real seconds after display, so all 1500 can overlap. `peakAuthenticatedActors` is incremented only after a correctly authenticated real offer response. It must equal the offered actor count. `peakHttpInflight` is reported separately; default maximum is 50. Waiting actors use asynchronous timers, not OS threads or 1500 simultaneous requests.
- Every actor obtains exactly the seeded active rule and correct bonus/price, sends a real shown ACK, appends an immutable physical-line change journal, and sends the final paid receipt. The item is normally added near display but payment occurs **after the 120-second display window**. There is no timestamp backdating, virtual clock or shortened payment-delay option.
- Completion proves physical document and source-line IDs, same namespace/USER/native session/workstation, paid status, final details, `DOCSTATUS=1`, negative signed total/quantity/amount and positive signed price. Normalized sale items remain positive.
- All actors replay the same final outbox message. Fifteen actors per 1500 race the same sale in parallel and then replay a different sale ID for the same physical/fiscal receipt. Fifteen deliver the sale before its original shown ACK, then deliver the ACK and replay.
- Four negative controls per 100 actors: foreign device ACK, removed product, already-present product and product genuinely added after 121 real seconds. These must generate **zero wallet money**. A foreign ACK can be a deliberate 200 no-op or 403/404; the SQL oracle, not that HTTP status, proves it cannot authorize a show.
- At 1500 actors, **1440 expected credits × 200 KZT × 2 full packages = 576,000 KZT**, zero for 60 negative controls. The shared real promo has sufficient finite budget. Its atomic `spent` must equal the wallet total.
- Heartbeat and nonempty exact playlist/slide polling continue every 60 seconds while each actor is active. Playlist media is a synthetic URL; this does **not** test video playback or uploads. This load does not validate the Windows desktop/UI, real scanner or real pharmacist identity.

## Cheap self-checks (no Docker/server)

From the repository root:

```sh
node --test tools/loadtests/recommendation-rewards/contract.test.mjs
python3 -m unittest discover -s tools/loadtests/recommendation-rewards -p 'test_fixture.py'
```

These verify origin/database/scale guards, unique synthetic identities, exact v1 payloads, sign/quantity arithmetic, removal/baseline controls, bounded concurrency and monetary SQL assertions. They are not an executed load test.

## Coordinated local run

Do not start this alongside Gradle, native builds or another load test. Start with `--actors 8`; after that passes, use a **fresh DB/run ID** for 1000/1500. A real run takes roughly 3–5 minutes. Default deadline is under 7 minutes; configurable spread/payment maxima keep it below 15 minutes. All thresholds remain strict on reruns.

Laptop budget: Docker **6–8 GB total / 4 CPUs**, app container max 2.5 GB with 1.2 GB heap, PG max 1.5 GB with 256 MB shared buffers, Hikari 16, PG max 40 connections, runner typically well below 512 MB. No Grafana, MinIO or Redis container is needed for this JSON/device-auth flow. CPU/heap/DB pool are sampled from local actuator every 5 seconds. If an existing backend requires Redis for a newly added operation, stop and declare that dependency rather than silently bypassing authentication.

The following are operator instructions, not automatic startup. Build the candidate JAR through the normal backend checks first. `BACKEND_JAR` must be an absolute local JAR path. Use a private output location outside Git. Record the three operator variables below in the private compose env file (never Git) so every subsequent compose invocation uses the same candidate and cutoff.

```sh
python3 tools/loadtests/recommendation-rewards/build-fixture.py --run-id smoke01 --actors 8 --output-dir /ABS/PRIVATE/reward-smoke01
```

Set the operator environment (do not commit/generated print credentials):

```sh
export BACKEND_JAR=/ABS/CANDIDATE/app.jar
export ELIGIBLE_AFTER="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
export REWARD_FIXTURE=/ABS/PRIVATE/reward-smoke01
```

Start only the dedicated isolated stack. The network has `internal: true`; PG has no published port; the app port requests loopback only. Docker versions that suppress published ports on internal networks remain supported: the optional Node runner shares only the backend network namespace and calls its loopback, with no external egress. `SPRING_PROFILES_ACTIVE=test,isolated-reward-load` avoids dev seeding and the bundled real-pharmacy importer. The candidate must include V099 and evidence protocol 1. Feature switches are enabled only for this isolated stack, with an explicit current cutoff. Legacy device authentication is disabled.

```sh
docker compose --env-file "$REWARD_FIXTURE/compose.private.env" -f tools/loadtests/recommendation-rewards/compose.yml up -d
```

Wait for `/api/health` to become healthy (inside the runner namespace: `http://127.0.0.1:8080`). Seed only after Flyway finishes. The SQL refuses a populated business DB and never truncates/deletes anything:

```sh
docker compose --env-file "$REWARD_FIXTURE/compose.private.env" -f tools/loadtests/recommendation-rewards/compose.yml exec -T postgres sh -c 'psql -v ON_ERROR_STOP=1 -U reward_load -d "$POSTGRES_DB"' < "$REWARD_FIXTURE/seed.private.sql"
```

Run HTTP load; the result directory must not already exist:

```sh
docker compose --env-file "$REWARD_FIXTURE/compose.private.env" -f tools/loadtests/recommendation-rewards/compose.yml run --rm --no-deps -e BASE_URL=http://127.0.0.1:8080 -e ISOLATED_DATABASE=epharm_reward_load_smoke01 runner run.mjs /fixture/fixture.private.json /fixture/results
```

Only after HTTP succeeds, run the read-only oracle and retain its output next to `summary.json`:

```sh
docker compose --env-file "$REWARD_FIXTURE/compose.private.env" -f tools/loadtests/recommendation-rewards/compose.yml exec -T postgres sh -c 'psql -v ON_ERROR_STOP=1 -U reward_load -d "$POSTGRES_DB"' < "$REWARD_FIXTURE/verify.sql" > "$REWARD_FIXTURE/results/database-oracle.txt"
```

**Both commands must exit 0**. The HTTP state is intentionally `http_complete_requires_database_oracle`, not “money verified”. The oracle requires per-person balances/earned deltas, exactly one ledger credit per positive actor, exact pending/receipt/case source chain and recipient, approved receipt ×400, canonical physical/fiscal fences, negative controls, actual persisted offers/sales, genuine late payment, genuine ≤120-second positive adds /121-second negative adds and exact finite-budget spend. It does not create or repair money.

For 1500 repeat from generation with a new `--run-id capacity01 --actors 1500`, fresh isolated volume/project name and `ISOLATED_DATABASE=epharm_reward_load_capacity01`. Compose's default project is dedicated `epharm-reward-load`; pass `-p epharm-reward-load-capacity01` consistently on **every** compose command for a separate fresh volume. Do not reuse a smoke database or rerun mutations over an old fixture. Keep each dataset until results are reviewed; cleanup is an explicit operator action on that isolated project, not an automatic destructive step.

## Measurements and gates

`summary.json`: offered/completed actors, actual peak authenticated actors vs actual peak HTTP requests, per-route p50/p95/p99/max, status counts, transport/429/4xx/5xx/redirect categories, semantic failures, scheduler lag, client queue pressure, event-loop lag, runner max RSS and real time separation. `actor-evidence.private.json` contains timestamps and event IDs without device credentials; `resources.private.json` holds sampled backend CPU/JVM memory/Hikari gauges and timeouts.

Defaults (declare any alternative **before** running): p95 ≤2s and p99 ≤5s for every route, client queue p99 ≤1s, no unexpected status/transport/semantic failure, exact completion count, all authenticated actors overlapping, available resource samples, SQL oracle passing. Every 429 is distinct from 5xx and fails a nominal run. Expected foreign-control 403/404 are listed explicitly and are not availability failures. Failed runs remain failed; do not label missing actors “capacity success”.

For whole-container CPU/RSS rather than JVM-only telemetry, retain Docker stats during the same window with `docker stats` for these two dedicated containers. Retain PG pool/lock information from the dedicated PG only, for example:

```sql
SELECT state, wait_event_type, wait_event, count(*) FROM pg_stat_activity
WHERE datname=current_database() GROUP BY state,wait_event_type,wait_event;
SELECT numbackends,xact_commit,xact_rollback,deadlocks,temp_bytes,blks_read,blks_hit
FROM pg_stat_database WHERE datname=current_database();
```

No production-capacity claim follows from a local laptop run. Record candidate commit/JAR SHA, Docker allocation, measured actor count, actual concurrent requests, all failed/successful trials and test limitations in the parent acceptance report. No mobile tests are needed.
