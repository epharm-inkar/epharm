# System audit — 2026-11-16

## Scope and release boundary

Base: main `c3937f5` after PR #173/#174. Production was observed at v0.2.20,
healthy with zero container restarts; this task did not deploy it. The protected
`codex/recommendation-sale-auto-credit-20261109` branch is untouched.
Mobile builds remain paused. No production load test, password retry automation,
direct SQL mutation, monetary flag change or unrelated service restart is performed.

## Completed production data operation

Using the normal authenticated HQ API, published 15 campaigns with 981 active
cross-sell rules from the agreed matrix. Only unambiguous catalogue matches with
positive confirmed bonuses were included. Ambiguous/clinical-review positions
were excluded. Dates: 2026-11-16 through 2026-11-30; budget: 0. Both sale-bonus flags
remain false. Source points were explicitly confirmed as 1 KZT per point.

Durable private journal recorded each prepared/applying/verified stage; readback
checked exact rule IDs, active status, bonuses, barcodes, scripts, dates and budget.
Independent read-only SQL confirmed 15/981 active. All 20 pre-existing campaigns
and rule configurations were unchanged by this publication. This does not prove
on-screen delivery on every offline terminal, stock availability or clinical
interchangeability. No auto-credit was enabled and no actual payout was made.

## Inventory input

The new workbook contains 546 records and 618 unique normalized AnyDesk IDs.
123 records have multiple PCs; 50 have no valid ID and therefore unknown count.
32 IDs repeat across records. Strict unique name or city/address matching found
380 records in the live registry; only 320 are conflict-free import candidates.
The remaining 226 records require review. Original file is unchanged, and the
private output workbook preserves source values alongside separate inventory,
registered POSM and online counts. Remote-access identifiers are not committed.

Inventory has **not** been imported into production: V104 and its API are a code
candidate. After reviewed release, revalidate target identities and current inventory,
save a before snapshot, apply only approved matches through the normal API and verify
each result. Never set unknown PCs to zero or create pharmacies from ambiguous rows.

## Confirmed defects and changes

- Late learner authentication responses could replace a changed IIN/phone form and
  save stale tokens. Generation/abort guards cover all authentication steps and unmount.
- Even-sized sale-duration median selected the upper middle value. Timing statistics
  now ignore unknown/negative durations and average the middle pair safely.
- Analytics initial failures could appear as zero/empty success. Error/loading/stale
  states are distinct; monetary and reporting-window labels describe their actual data.
- Cancelling an Excel export by changing filters/permission could leave a stuck button
  when returning to that context. Export state now belongs to its request context.
- Pharmacy inventory was absent. New additive fields and scoped aggregate counters
  preserve unknown values and distinguish inventory from registered/online identities.
- The pharmacy metrics scheduler could merge a stale entity over newer inventory or
  passport edits. It now updates only calculated columns; unrestricted administration
  also locks the row before edits. A PostgreSQL regression models the stale-context
  interleaving and checks preserved inventory, name and active status.

## Verification status

The first complete frontend run found 1191 passes and three failures while files
were still being edited. Fresh focused pharmacy and dashboard/export runs passed.
These intermediate results are not a clean final full-suite claim.

The local Docker daemon is unavailable. Full source/test compilation and pure backend
unit tests can run with JDK 22; PostgreSQL 16 integration tests, V104 constraints and
native projection SQL must pass the unchanged CI gate. No skipped integration run is
reported as a pass. Final verification is recorded below when completed.

Local JDK 22 `compileKotlin compileTestKotlin` passed for the full source tree.
All 23 focused unit tests passed: analytics (3), pharmacy counts (6), metrics
scheduler (3), administrative roster scope (11), with no failures/errors/skips.
Frontend lint and TypeScript/production build passed. The prepared output workbook
was re-opened independently: 546 data rows, 15 columns, no spreadsheet error cells.
Final full frontend, browser artifact and PostgreSQL CI results remain release gates.

## Remaining boundaries

- Time-series charts are not implemented; the UI honestly marks them unavailable.
- Active recommendations with budget 0 do not enable automatic monetary rewards.
- Unmatched inventory requires an operator's source-data resolution.
- Counter identities are not proof of a physical PC count; reinstalls/offline PCs need
  reconciliation rather than automatic deletion.
- Existing Sentry configuration remains outside this patch; no checks are disabled.

## Independent release review

The exact original PR head `fe42905eb229d3f28872eed46c7ac87b792603f8` passed CI
`43841182297`: 1784 backend, 1197 frontend, 121 backend browser and 73 image browser
checks, 976 Windows core tests and the native WPF suite. Independent backend and
frontend source reviews found no blocking defect. A focused regression for an active
learner with no phone/pharmacy passes with all 10 authentication-lifecycle tests.
The final head must pass CI again. Mobile application builds remain paused.

Production was independently rechecked at `v0.2.20` / `c3937f5`; preserve PR #174
training enrollment, the already-published 35 total campaigns, 981 active rules and
the current signed POSM release. The 320-row inventory approval payload is not
available in this checkout. Older extraction/reconciliation files have different
row partitions and are not a substitute. This release deploys the API and nullable
schema; inventory import remains separate. V104 compatibility must be exercised
with the actual preceding backend before production migration.

Release readiness exposed a pre-existing v0.2.20 hot-path scaling defect: every scan
loaded each active rule's product separately before matching. With 981 active rules,
this produces hundreds of product queries per scan. Product-ID and legacy-INN reads
are now batched separately, reconstructing the previous candidate ordering without
cross-request caching or changing global barcode ownership checks. A 981-rule test
first failed on old query counts, then passed with the patch. All 21 focused engine
tests pass; independent review found no change to ranking, four advertised trigger
types, product-any, ACC AND, missing-ID or ambiguity behavior. Full CI must run again
on this additional change. Live disconnect/pool-timeout observations establish an
operational risk; they are not a controlled capacity benchmark or proof that this
is the only production bottleneck.
