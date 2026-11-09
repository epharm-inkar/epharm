# Local recommendation-sale rewards acceptance — 2026-11-09

Scope: local branch `codex/recommendation-sale-auto-credit-20261109`, based on
`4a6ebaf843f3acda1e47414dd0fd1c584c959f07`. No production connections, migrations,
release publication, GitHub write, main merge or real employee mutation was made.
No Flutter/Android/iOS tests or Windows VM were started. The domain and `/learn`
remain unchanged; both backend monetary switches and the POSM evidence switch
default to false.

## Accepted behavior

- First actual popup acknowledgement + complete immutable cart baseline.
- A new recommended full package must be added in the same checkout, physical
  document and line within 0–120 seconds inclusive. Preexisting items are excluded.
- Quantity decreases consume eligible units; deletion/re-add has a new generation.
  Only remaining qualifying sold packages count. Repeated windows do not reset time.
- Credit follows positive paid-document evidence, even if payment is later than
  two minutes. No disappearance, click, marketing attribution or RPS token is payment.
- Explicit same-pharmacy HQ Standard-N USER mapping and stable source identity at
  show/add/payment are required. Uncertain evidence/units/identity require HQ.
- Physical/fiscal canonical fences, per-product cases, finite campaign funding and
  the existing `receipt_bonus` wallet source prevent duplicate money. `/learn`
  reads that same wallet. Returns preserve original credit and go to HQ, including
  offline refund-first delivery. No automatic debit or card transfer is performed.
- HQ view/decision/Excel permissions and pharmacy scopes are enforced on the server.
  Explicit manual approval requires reason, recipient, package quantity and payment
  confirmation, and cannot bypass missing canonical keys or conflicting evidence.

## Verification

`checks.json` retains the final backend suite inventory: 145/145 passed, zero failures
or skips, including 18 real HTTP/PG flow tests, 72 pure evidence scenarios, 27 legacy
wire checks, 20 credit-policy/unit/PG checks, four budget races and four expiry checks.
The HTTP fixtures use actual device keys, the real HQ mapping endpoint and application
endpoints; they do not seed events, sales, pending bonuses, cases, receipts or money.
The existing learner APIs confirm the resulting balance/earned amount. These are
shared `/learn` server-route assertions, not mobile application tests.

Portable POSM: 598/598 passed. WPF/client/UI-test projects cross-built with zero errors
and warnings; interactive Windows execution was not performed. Frontend checks and
measured load results are recorded below after their completed runs.

## Failures retained and corrected

1. The first eight-actor smoke used an earlier candidate and failed with 409 responses.
   HTTP integration exposed JPA-to-JDBC flush ordering: the mapping audit referenced
   an unflushed mapping, and reward cases referenced an unflushed pending bonus.
   Both are fixed and covered by actual endpoint tests.
2. The corrected eight-actor HTTP run passed. Its initial SQL oracle failed because
   Hibernate JSONB timestamps were epoch seconds while the oracle assumed ISO text.
   A read-only corrected oracle then passed over the same untouched data: four
   credits ×400 KZT, no negative-control money. Both oracle attempts are retained.
3. One 1000-actor startup did not reach HTTP: Docker had exhausted its preexisting
   network address pools. Only this task's stopped networks were removed; foreign
   networks/data were not pruned. This is not a capacity result.
4. The first real 1000-actor run failed and is not counted as success: campaign FK
   key-share references conflicted with subsequent `FOR UPDATE` upgrades. Funding
   now uses `FOR NO KEY UPDATE` for non-key budget fields, and a normal ACK with no
   sale to replay takes no funding lock. A 16-concurrent-ACK regression passes,
   followed by 16 real credits totaling 6400 KZT. The strict load oracle now also
   requires zero PostgreSQL deadlocks. This locking distinction is specified in
   [PostgreSQL 16 documentation](https://www.postgresql.org/docs/16/explicit-locking.html#LOCKING-ROWS).

Private fixtures, generated device keys and detailed diagnostics remain outside
Git under `/Users/amir/.codex/private/reward-acceptance20261109`; the early failed
smoke is under `/Users/amir/.codex/private/reward-smoke20261109a`. Public artifacts
contain only synthetic aggregate results. Failed trials have not been relabeled.

## Limits before any production release

The real Standard-N adapter currently reports raw `QUANT` unit semantics as unknown.
It deliberately marks that evidence incomplete and does not automatically pay for
unproven packages. The HTTP load fixture explicitly proves `full_package` for its
synthetic adapter only. The real package/unit conversion needs vendor or observed
schema verification and a controlled Windows pilot. The native adapter does not yet
capture refund documents automatically; refund-first/HQ logic is tested with explicit
validated synthetic refund evidence.

Authenticated active actors and simultaneous HTTP requests are different metrics.
This is a laptop/backend/PostgreSQL test, not a measurement of production hardware,
1500 simultaneous screen renders, live scanners, uploads, SMS, registration throughput
or external fiscal attestation. Financial evidence uses the existing individually
authenticated workstation trust boundary. No production-capacity guarantee follows.

Before an eventual release, confirm the real adapter, explicit HQ mappings, finite
campaign budgets, permission grants, schema/storage capacity and evidence retention;
then perform a controlled pilot. There is no automatic enablement or backfill.
For rollback to an older binary, disable both sale-bonus flags first and prevent
older manual/Excel reconciliation writers from deciding v2 reservations. Retain
V100, financial ledger and source evidence; an older writer lacks the new guards.

## Completed load acceptance

Both fresh runs used the same candidate JAR SHA recorded in `checks.json`, source
commit `127a47e6`, isolated PostgreSQL16, a Node22 runner sharing only the backend
loopback namespace and an internal Docker network with no outbound access. Backend
limit:2.5CPU/2.5GiB, JVM heap1200MiB, Hikari16; PostgreSQL:1.5CPU/1.5GiB,
shared buffers256MiB/max40 connections; Node runner:1CPU/512MiB. Docker allocation
was8CPUs/4,109,803,520bytes. No real account or remote service was used.

| Measured result                                 |   1000 actors |     1500 actors |
| ----------------------------------------------- | ------------: | --------------: |
| Authenticated actors simultaneously active      |          1000 |            1500 |
| Completed scenarios                             |          1000 |            1500 |
| Peak concurrent HTTP requests                   |            18 |              50 |
| Actual elapsed seconds                          |        185.01 |          185.02 |
| First sale p95 / p99, milliseconds              | 33.84 /200.65 |  159.30 /651.85 |
| Recommendation p95 / p99, milliseconds          |  11.93 /79.83 | 205.80 /1040.57 |
| Positive credited employees                     |           960 |            1440 |
| Wallet/earned/budget, KZT                       |        384000 |          576000 |
| Negative-control employees; credited money      |          40;0 |            60;0 |
| Unexpected HTTP/transport/429/semantic failures |             0 |               0 |
| PostgreSQL deadlocks                            |             0 |               0 |
| All strict HTTP + read-only SQL oracle gates    |          PASS |            PASS |

Arrivals were spread over60 real seconds. Payment followed the first actual test
show after125 real seconds; late121-second addition, preexisting/removed product,
foreign ACK, offline sale-before-ACK, concurrent duplicate and physical alias
controls were exercised. This is a high-overlap workload, not1500 simultaneous
HTTP sockets or1500 operating-system threads. The plan's preliminary10/100/500
ladder was replaced by the eight-actor smoke,16-concurrent-ACK regression, and the
requested1000/1500 targets; no intermediate scale was claimed as measured.

Thresholds stayed unchanged: every route p95≤2000ms/p99≤5000ms, client queue p99≤1000ms,
all actors overlapping/completed, zero unexpected errors and exact monetary SQL
invariants. The1500 run's client queue p99 was77.79ms. CPU/RAM samples (`resources-*`)
showed backend maxima856.7/895.8MiB and PostgreSQL149.4/157.2MiB for1000/1500; these
are sampled container values, not continuous peak or production forecasts.

Artifacts: `load-1000.json`, `load-1500.json`, `oracle-1000.txt`, `oracle-1500.txt`,
`resources-1000.json`, `resources-1500.json`. SQL verifies each beneficiary/pharmacy,
wallet delta, earned delta, receipt→pending→case→source chain, physical/fiscal fences,
quantity/bonus, real timing, campaign spent and absence of negative-control credits.

Frontend: 65/65 targeted tests passed after formatting, lint and production build
passed. Results are retained in `checks.json`. Containers, networks and five synthetic
database volumes created for these local runs were removed after acceptance. Docker
Desktop, started for this task, was stopped again. Private fixtures and diagnostics
were retained; foreign projects and user/production data were not pruned.

## Integration with main updated during the task

After the preceding acceptance, the local `origin/main` reference advanced to
`fbb4545e` (PR #158). Its release-publication migration V099 is preserved unchanged;
our local unapplied reward migration was renamed V100. The updated main was merged
only into the feature branch, in `01022001`; no main promotion or production action
was performed. The original load artifacts above remain historical measurements of
`127a47e6` and are not silently presented as measurements of the newer binary.

Both native popup paths were retained: main's actual-visibility checks, 60-second
expiration/reopen and background shown-ACK buffer, plus our first immutable display
snapshot. `PendingShownPings` now forwards an optional frozen reward baseline through
that same durable path. A 256 KiB per-record /8 MiB serialized buffer bound admits a
512-line baseline without restoring unbounded memory or synchronous SQLite writes.
Legacy calls remain valid; root reward flags remain off by default.

`checks-after-main-merge.json` records **172/172 backend checks, zero failures/skips**,
including the previously tested monetary flow, nine new identity-conflict checks,
five source-bound, four receipt-race and nine shared-wallet concurrency checks.
The merged V099+V100 schema booted successfully. Native checks: **664 PASS, 14 explicit
Windows-only SKIP, zero FAIL** with canonical physical TMPDIR. The default macOS
`/var` temp alias caused 40 initial test failures under main's new symlink guards;
using `/private/var/...` retained those guards and passed the portable suite.
The 11 shown-buffer tests passed, including frozen first baseline/restart and bounds.
WPF cross-build had zero errors/warnings; Windows runtime was not claimed. Frontend
code was unchanged by this main update; its 65 checks, lint and build remain valid.

The merged candidate JAR SHA is
`75d4eec7767060afe7c5578be9ce5f9362121e08150e8d9ddeef3938c6c48d3e`.
A fresh 1500-actor measurement on this binary is retained separately below.

Merged-candidate result: **1500/1500 actors, peak authenticated actors1500,
peak concurrent HTTP requests50, elapsed185.02s**.
All HTTP gates and the read-only SQL oracle passed;1440 recipients ×400KZT
=**576000KZT**, no money for60 negative controls, no duplicate sources, no
HTTP/transport/429/semantic failures and zero PostgreSQL deadlocks. First-sale
p95/p99=47.11/601.46ms; recommendation
p95/p99=23.18/749.57ms. Same thresholds, real125s payment
delay and60s arrival spread; no shortened clock or weakened assertions.
Artifacts: `load-1500-after-main-merge.json`, `oracle-1500-after-main-merge.txt`,
`resources-1500-after-main-merge.json`. Test containers/network and this additional
synthetic DB volume were removed after recording the results.
