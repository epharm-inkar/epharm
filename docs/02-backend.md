# Backend

## Sales premium from Standard-N (26.11.2026)

The monthly premium for selling the products of the month's matrix (bonus per pack times packs, receipts minus
returns, per seller IIN) is computed from the central Standard-N database, by the server's daily collector (see below)
or outside ePharm, and arrives as cumulative month-to-date amounts, again every day. ePharm only credits it to the
pharmacist wallet as earned money; payouts are separate work. Package `finance.premium` (pure rules in
`SalesPremiumRules`, tested without a database), V124 and V125.

- `POST /api/admin/finance/sales-premium/import` `{period: "2026-11", through: "2026-11-25", dryRun, rows: [{iin,
  amountKzt, packs?}]}`. HQ_HEAD and SYSTEM_ADMIN only (`@PreAuthorize`; a finance reviewer approves payouts but does
  not load premiums). In the unified route catalog it is `NOT_READY` like the payouts: a legacy HQ account works, a
  managed account gets 403 until the route is migrated. `dryRun` must be in the file: Jackson reads a missing primitive
  Boolean as false, so the field is nullable and a file without it is 400. `dryRun: true` runs in a read-only
  transaction and writes nothing; `false` credits and writes the audit row.
- Input checks (400 naming the rows): period `YYYY-MM`; `through` a day of that month, not after today in
  Asia/Almaty; 1 to 5000 rows; IIN exactly 12 digits (no checksum, the cards come from the same directory); `amountKzt`
  a whole number of tenge from 0 to 20 000 000 (a guard against tiyn instead of tenge); `packs` optional, 0 or more;
  an IIN twice in one file is refused.
- Delta: the active card is found by IIN (`pharmacists.iin` is unique; if several active cards ever existed the IIN is
  reported as `ambiguous` and nobody is guessed). Delta = cumulative figure minus the net premium already in the
  ledger for that month (`sales_premium` minus `sales_premium_correction`, context `premium:<period>`), read under the
  pharmacist row lock, so a repeated or a concurrent file writes nothing the second time. A rise is one
  `sales_premium` entry (earned and balance up, `sourceId = premium:<period>:<pharmacistId>:<through>`; another run of
  the same cut-off with other figures adds `#2`, `#3`). A fall is one `sales_premium_correction`: earned and balance go
  down by the same amount, never below a zero balance; the rest is returned as `uncoveredKzt` and is asked again by
  every following file while the source still says the lower figure.
- A seller without an active card (no card, or a pending or blocked one) is kept in `sales_premium_pending(iin,
  period, amount_kzt, through)` with the latest figure (zero is never stored) and credited when a card with that IIN
  appears or becomes active: `PharmacistCardReady` is published by `PharmacistService.create/activate/unblock` and
  `StandardNAccountService.ensure`, handled after the commit by one background thread, and `SalesPremiumSweep`
  checks every 10 minutes (`app.finance.sales-premium.sweep-delay-ms`) for anything a hook missed.
- Safety: a file whose `through` is older than the latest loaded one of the month is 409 (its lower figures would take
  valid credits back). Imports of one month are serialised by an advisory lock; pharmacist rows are locked in id
  order, like the payout generation, so the two never wait for each other. Every real import writes one append-only row
  to `sales_premium_imports` (admin, role, month, cut-off, SHA-256 of the figures, counts and sums, no IIN, name or
  phone). V124 widens the ledger constraints: `sales_premium` needs earned = amount, `sales_premium_correction`
  needs a negative amount equal to earned, every other source keeps its rule.
- Response: `rows`, `matched`, `credited`, `corrections {count, appliedKzt, uncoveredKzt}`, `unchanged`, `pending
  {count, sumKzt, notActiveCount}`, `ambiguous {count, sumKzt, iins}`, `packsTotal`, `auditId`. `matched + pending +
  ambiguous` always add up to `rows`.
- Reading it back: the cabinet balance (`GET /api/mobile/me/balance`) is `pharmacists.balance`; the till's "earned in
  the month" (`PosmSelfService`) sums `earned_delta_kzt` of the Almaty month and is clamped at zero; its last
  entries name the source "Премия за продажи, ноябрь 2026". The admin roster's 30-day earned figure is clamped at
  zero too. The HQ ledger report knows both sources. A payout batch reserves the whole balance, premium included.

### Daily collector from Standard-N (26.11.2026, V125)

The server can compute the figures itself and give them to the same import (`SalesPremiumService`), so nothing here
credits money by another route. Files: `SalesPremiumCollector` (one calculation), `SalesPremiumAggregator`
(`PremiumAccumulator`, pure), `StandardNPremiumSource` (`FirebirdPremiumSource` behind the interface
`StandardNPremiumSource`, mocked in the tests), `SalesPremiumJob` (the daily schedule), `SalesPremiumMatrix` (the
month's products), `SalesPremiumStatement`, `SalesPremiumOperationsController`.

- Source: the central Standard-N database through the same connection factory and `STANDARDN_*` settings as the
  pharmacist directory (`app.standardn-directory.*`; it needs `STANDARDN_DIRECTORY_ENABLED=true` and the read-only user).
  The connection is read only (`isReadOnly`), WIN1251, one connection per calculation, one prepared query per matrix
  product (`PARTS` joined to `DOC_DETAIL`, `DOCS` of type 3 and 9, `SESSIONS` and `USERS` by outer join, group by
  profile, user, IIN, name, document type) with `DOC_COMMITDATE >= first day` and `< day after the cut-off`.
  Receipts (type 3) carry negative quantities, returns (type 9) positive ones: packs = minus the sum of both.
- Window: the month of yesterday, through yesterday, in Asia/Almaty. On the 1st that is the month that has just ended
  with its last day as the final cut-off. The figures are cumulative from the 1st, so a missed day is made up by the next.
- Premium of a seller = sum over the products of packs x `bonus_per_pack`, rounded to whole tenge once, at the end, per
  IIN (the same person under several Standard-N users or pharmacies is one seller). A seller whose month is below zero
  (returns of earlier sales) is sent with zero, as the import accepts no negative figure. Lines without a usable IIN
  (empty or not 12 digits, or a session without a user) are summed apart per Standard-N user and reported in
  `withoutIin {sellers, packs, amountKzt}`; they are never credited. Products with a zero bonus are read too, so their
  packs count (`packs` of the answer is the net of every seller with or without IIN).
- Only a complete read is imported: one failed product query aborts the run before anything is sent (a figure that
  misses a product would be read by the import as a return and take premium back). The read is stopped after 25 minutes.
  If every product of the matrix has no sale, nothing is sent. A wrong read is also bounded by the import itself: a
  correction never takes the balance below zero, and the next good read credits the difference again.
- One calculation at a time (`AtomicBoolean` in `SalesPremiumCollector`): a second request from an administrator is
  409, the job skips. Several backend instances would not corrupt anything: the import is idempotent and serialised per
  month by its advisory lock, the Standard-N side is read only.
- Matrix: table `sales_premium_matrix(period, ware_id, name, bonus_per_pack)`; `ware_id` is the Standard-N product id
  (GUID in upper case, `WARES.ID`), an empty bonus is zero. V125 seeds `2026-11` (188 products, one with a zero bonus)
  from the owner's list. A later month is uploaded with `POST /api/admin/finance/sales-premium/matrix?period=2026-12&
  dryRun=true|false`, body = the CSV (UTF-8, header with `ware_id`, `bonus_per_pack`, optional `promo_name`; comma or
  semicolon; decimal comma accepted; a bad row refuses the whole file). It replaces the whole month; the answer compares
  with what the month has (`added`, `removed`, `changed`, `loadedThrough`). A changed matrix re-prices the whole month
  from the 1st at the next calculation, sales before the change included.
- Job: `SalesPremiumJobScheduler` ticks every minute on a thread of its own (a slow Standard-N never holds the shared
  scheduler thread of the payouts and the other jobs); `SalesPremiumJob` runs the first attempt at 06:40 Asia/Almaty and,
  while the database does not open (it is closed at night), retries every 30 minutes until 11:00. Settings
  `SALES_PREMIUM_JOB_ENABLED` (default `false`: nothing is read or written), `SALES_PREMIUM_JOB_START`,
  `SALES_PREMIUM_JOB_DEADLINE`, `SALES_PREMIUM_JOB_RETRY_MINUTES` (`app.finance.sales-premium.job.*`; a mistyped time is
  logged and replaced by its default). After a success the day is done, also after a restart: a real run leaves an
  audit row in `sales_premium_imports` with the fixed system actor (`actor_id` all zeros, role `SYSTEM_JOB`) and the job
  looks for one of today. The scheduler is not part of the `test` profile.
- Log line per calculation: period, cut-off, products, packs, amount, sellers, sellers without IIN, matched, pending,
  credited, corrections. No IIN or name is logged.
- `POST /api/admin/finance/sales-premium/recompute?period=2026-11&dryRun=true|false` runs the same calculation now
  (HQ_HEAD, SYSTEM_ADMIN). `dryRun` is required (a missing flag is 400); without `period` it is the month of yesterday.
  The answer is totals only: `products`, `productsWithSales`, `packs`, `sellers`, `sellersWithPremium`, `premiumKzt`,
  `withoutIin`, `negativeSellers` and the import's own answer under `imported` (`matched`, `pending`, `credited`,
  `corrections`, `ambiguous` without the IINs). A closed Standard-N is 503, a running calculation 409, a month without
  a matrix 409. It works with the job switched off: run the dry run first, compare with the owner's check figures,
  then switch the job on.
- `GET /api/admin/finance/sales-premium/statement.csv?period=2026-11`: the month statement for accounting, UTF-8 with
  a BOM, semicolons, CRLF: `ФИО;ИИН;аптека;город;упаковок;начислено;статус`. The credited premium comes from the wallet
  ledger (net of corrections, per pharmacist card), the people who wait for a card from `sales_premium_pending` (their
  name, pharmacy and city from the Standard-N directory snapshot; what a blocked card already has in the ledger is not
  counted twice). The packs come from `sales_premium_figures`, the latest figure per IIN and month written by every real
  import (the ledger holds tenge only). The IIN is written as `="050112345678"` so that Excel keeps a leading zero;
  packs use a decimal comma; a text that starts with `=`, `+`, `-` or `@` gets a leading apostrophe. The download is
  logged (admin id, month, rows), and the response is `no-store`.
- Tables of V125: `sales_premium_matrix`, `sales_premium_figures`. Nothing is dropped or rewritten; V124 and the wallet
  stay as they are. Rollback: an older backend ignores both tables.

## Explicit campaign funding mode — candidate 2026-11-16

`PromoDto` and create accept `budgetUnlimited=false` by default; update accepts a
nullable flag, where omission preserves the stored mode. Changes require
`MotivationPermissions.configureBonus` through the existing permission policy. Prepared
publication freezes the selected mode; old limited-draft fingerprints remain valid,
but a published mode change invalidates a stale prepared draft. The changes response
exposes `canConfigureBudget`; this UI hint does not replace server authorization.

The evidence-bound recommendation reward writer reads budget, mode and spend under
the existing campaign row lock. Only explicit true bypasses a finite cap; limited
zero still refuses payment. Every new reservation increments `spent` exactly once,
including unlimited campaigns. Overflow goes to HQ (`reward_budget_overflow`), not
an overflowing SQL update. Existing identity, payment, return and retry guards stay
in force. Atomic funding updates prevent a switch to a cap below committed spend;
fresh scalar state under the lock prevents stale ORM data from resetting the mode.
No legacy writer is silently converted, no financial flag is enabled, and no past
receipt is replayed by this feature. See database rollout notes before enabling it.

## Financial product identity — repeated audit candidate 2026-11-16

Ordinary sale items and attribution retain general catalog resolution for diagnostics.
Legacy financial reconciliation independently uses `resolveStrongProductIds`: only
unambiguous exact EAN/local SKU mappings enter the monetary log. An unknown or
conflicting supplied EAN cannot fall through to a name, and an unverified raw SKU
cannot be treated as a canonical product ID. Mixed receipts retain all raw/analytics
items but only strong lines confirm a pending bonus; a receipt without strong lines
does not invoke legacy reconciliation. Replay, seller-source whitelist, feature flags,
budgets and the separate evidence-bound reward protocol are unchanged.

## Ordinary sale seller-source candidate — 2026-11-16

`POST /api/posm/sales` accepts optional `sellerSource` with `sourceNamespace`,
`rawUserId`, `nativeSessionId` and `workstationId`. It is frozen receipt provenance,
not a substitute for an authenticated employee grant. Validate it before identity
resolution; when reward completion is also present, all four fields must agree.
Existing source-binding checks still require the matching individual device, pharmacy,
native session/workstation and proof valid at receipt time. Legacy requests may omit it.
V105 persists the source; retries cannot add/remove/change it or its device/time.
New ordinary `standardn_rps_binding` attribution bypasses legacy reconciliation,
so this repair cannot create a second money path with reward evidence disabled.

Heartbeat `staffSourceStatus` is a fixed non-personal diagnostic independent of
`staffAuthStatus`. Unknown strings are discarded; omitted diagnostics clear the
previous heartbeat value even across instances. Database-only presence does not
manufacture a login or source observation. HQ discovery includes missing USER_ID,
inactive mapping recipients and unreviewed source conflicts; last observed names
replace alphabetical maxima. Reads never create mappings or repair historical sales.

## Pharmacy inventory and analytics audit — candidate 2026-11-16

Pharmacy DTOs expose nullable `inventoryDeviceCount`, `inventorySource` and
`inventoryUpdatedAt`, plus `registeredDeviceCount`, `onlineDeviceCount`,
`deviceCountsObservedAt` and `deviceOnlineWindowSeconds`. Inventory updates require
both count and a nonblank, bounded, control-character-free source; the timestamp is
server-generated. Null/null leaves inventory unchanged; unknown is not inferred as zero.
The existing pharmacy edit permission and scope apply independently of read access.

POSM counts use one grouped query restricted to visible pharmacy IDs, not one query
per pharmacy. Registered means distinct active, non-revoked device IDs; online also
requires an active pharmacy and a heartbeat within the configured window up to the
observation time. Future heartbeat timestamps are not online. Inventory and the durable
POSM device registry are different evidence sources, not physical-machine reconciliation.

The metrics scheduler writes only calculated metric columns with a conditional SQL
update, never merges a stale pharmacy entity. Both scoped and unrestricted pharmacy
edits acquire the row lock before reading; inventory/passport updates are not lost
when metrics refresh concurrently.

Recommendation duration analytics excludes unknown/negative durations from timing
statistics without dropping converted events. The even-sized median averages the two
middle values with overflow-safe arithmetic; actual zero durations remain valid.

## Scan-time matching reads

Each request reads fresh scalar active-rule references before loading rich JSON cards.
Global barcode ownership remains authoritative, including owners outside active rules.
Only potential matches load full rules through engine-specific read-only queries;
the existing final matcher still checks all conditions, exclusions, conflicts and ranking.
An unknown barcode with a local SKU/name keeps the complete original relevant-product
pool and conservative dosage/name safeguards. Strong barcode resolution needs only
the matched offers. No cross-request cache delays publication, pause or price changes.
Generic administrative repositories remain writable. This optimization adds no schema
migration and does not change POSM request/response contracts.

## Current training auto-assignment

An opt-in reconciler assigns one explicitly selected published online training
program to registered pharmacist profiles with status `active` or `pending`.
It scans `pharmacists`, including OTP accounts without a password or pharmacy;
the separate Standard-N directory is not an enrollment audience. This covers
website, POSM and administrative registration without making registration depend
on LMS availability. The default interval is 60 seconds; bounded per-person
transactions let later cycles recover transient failures.

Enable only with all three settings: `TRAINING_AUTO_ASSIGNMENT_ENABLED=true`,
`TRAINING_AUTO_ASSIGNMENT_PROGRAM_ID`, and `TRAINING_AUTO_ASSIGNMENT_ACTOR_ID`.
The selected accountable administrator must remain active and retain the role
and explicit all-pharmacy permission to assign training. An absent, invalid,
unpublished, paused, archived or expired target does not select another course.
Changing the current program is an explicit configuration decision, never a
side effect of publishing unrelated training.

Any existing assignment for that program, across its versions and statuses,
prevents automatic re-enrollment. Completed, cancelled and overdue history,
progress and deadlines stay intact. New assignments use normal stages,
notifications, counters and audit; assigning alone does not credit a wallet.
The manual assignment endpoint keeps its existing active-only eligibility.

## Delayed reward authority candidate — 2026-11-15

A stored `standardn_explicit_mapping` identity is historical attribution, not permission
to credit later. Before financial processing (including a late shown acknowledgement),
the reward service rechecks the current active HQ mapping and source-binding conflict
under the same recipient/account locks used by identity writers. Revoked or reassigned
recipients go to HQ review without spending budget or creating a wallet credit. A
multi-sale replay locks its full sorted recipient/account set before receipt and funding
locks. Existing valid payouts are not reversed, and real auto-credit gates remain off
until a funded campaign and scoped pilot are approved.

Legacy receipt reconciliation accepts financial confirmation only from the existing
`posm_internal` and `standardn_explicit_mapping` sale identities. Name-only matches
remain stored analytics, and ordinary RPS attribution still does not enter legacy
payments. This does not change full reward-evidence authorization or enable money flags.
Log/Excel amounts must be positive and agree within inclusive 2% of the log amount,
regardless of arrival order; comparison is overflow-safe. Missing, invalid or conflicting
amounts retain an exact matched receipt for HQ with `amount_missing`, `amount_invalid`
or `amount_mismatch`, without granting financial confirmation to invalid evidence.
Legacy log confirmation also requires finite positive quantity and positive unit price;
invalid lines remain in the raw sale journal and the matched receipt stays for HQ review
with `quantity_invalid` or `price_invalid`. Positive fractional quantities remain valid:
this check does not infer full-package semantics or multiply a legacy reward by quantity.
No nonblank existing review reason is cleared or bypassed by automatic retries;
explicit HQ review retains its existing permissions and decision path.
The common new-credit gate requires an `awaiting_receipt` reservation even without
a v2 reward case: manual review cannot revive an expired or already matched legacy
reservation. Retrying an already approved receipt remains idempotent and does not
request a second credit.
An Excel-first row lacking a positive amount
cannot select a pending by amount and remains unmatched until sufficient evidence exists.
No historical repair, reassignment or automatic backfill is performed.

Legacy log matching also requires the pending reservation's exact pharmacy, seller and
canonical SKU, rechecked under its row lock. Excel fiscal matching requires an exact
canonical SKU and consistent receipt/pending pharmacy; a fiscal number is never proof
of a different product or pharmacy. Conflicting known fiscal IDs from log and Excel cannot confirm
each other in either arrival order and do not flag the unrelated receipt.
Nonblank Excel pharmacy codes resolve only through
the explicit Spring configuration map `epharm.receipts.excel.pharmacy-codes`, whose
default is empty. Example operator-supplied configuration (synthetic identifiers):
`epharm.receipts.excel.pharmacy-codes[pilot-export-code]=canonical-pharmacy-id`.
Use bracketed map keys for opaque codes with punctuation/case; values are canonical
pharmacy IDs. The parser trims Excel cell boundaries, but the crosswalk never applies
fuzzy matching, case folding or implicit ID/name/CRM/storefront aliases. Every mapping
must be verified against the actual export and the canonical pharmacy before rollout.
Unknown or mismatching supplied codes remain persisted unmatched Excel rows (import
continues); they neither confirm nor flag an unrelated receipt. Missing codes also
remain unmatched: a currently unique fiscal number cannot prove the export's pharmacy.
Both fiscal matching and Excel-first SKU/amount selection require an explicit resolved
pharmacy mapping. **Rollout gate for legacy Excel confirmations:** validate and
configure the export crosswalk before enabling or preserving automatic confirmations.
An installation with no active legacy Excel imports may be deployed with the empty
map: import still preserves rows for HQ review, and automatic confirmation stays
paused until verified mappings are supplied. Do not replace an existing verified
crosswalk with the empty default. Missing/unknown codes never auto-confirm or credit;
the raw rows remain available for HQ review. Existing rows are not deleted or replayed.

## Local candidate: recommendation sale rewards

Optional evidence protocol 1 extends the existing POSM recommend/shown/sales routes;
older clients keep their existing journal and attribution behavior. Both
`POSM_SALE_BONUS_ENABLED` and `POSM_SALE_BONUS_AUTO_CREDIT_ENABLED` default to false.
Auto credit additionally requires an explicit `POSM_SALE_BONUS_ELIGIBLE_AFTER` cutoff;
there is no historical backfill. `POSM_SALE_BONUS_DEVICE_ALLOWLIST` is closed when empty;
exact individual device IDs select a pilot, standalone `*` explicitly selects the fleet.
The authenticated heartbeat advertises protocol 1 only inside this scope. Shown
admission and the final money decision independently enforce the same scope.

The first actual display snapshot is immutable. Only newly added, remaining full
packages in the same checkout and physical document/line, added within 0–120 seconds
inclusive, qualify after positively confirmed payment (payment can be later).
The beneficiary requires either an explicit active same-pharmacy HQ Standard-N USER
mapping or source-bound proof from a successful password-authenticated RPS login.
Proof uses exact IIN first; only missing/unavailable source IIN permits a unique
normalized full-name match. Available mismatched/invalid/ambiguous IIN never falls
back to a name. V102 scopes this proof to pharmacy, individual device, database
namespace and native USER; the live grant must also match native SESSION/workstation
at payment. Later login cannot authorize an earlier sale. A conflicting/revoked HQ
mapping blocks automatic rebinding; an explicit later HQ review resolves exceptions.
Supplied internal IDs and fuzzy name matching are not financial proof.
Unknown units, incomplete evidence and ambiguous employees go to HQ. The current native
adapter requires an explicit positive integer HUMAN_QUANT agreeing with absolute
QUANT to prove a full package; fractional/missing/conflicting units never earn
automatically. Raw QUANT alone is insufficient. See [POSM contract](05-posm-client.md).

Physical and fiscal receipt fences, per-product cases, ordered transaction locks,
finite campaign budgets and the existing append-only wallet prevent replay and
concurrent double credit. Bonuses use `receipt_bonus` and the same balance read by
the separate `/learn`. A refund retains the original credit and opens HQ review,
including when the refund arrives before the original sale. Native refund capture uses the verified posted-return type, original receipt and
signed native lines; ambiguous or unlinked evidence is retained for review.

HQ routes: `/api/admin/reconcile/recommendation-rewards`, `/{id}`, `/export.xlsx`
and `/{id}/decision`. They require explicit scoped `motivation.sales_review`
view/export/approve grants; the financial approve grant is not inferred from a page
view permission. Review requires a reason, explicit payment/package confirmation,
same-pharmacy active beneficiary, verified source-user mapping and canonical receipt
keys. Conflicting source evidence cannot be approved through a checkbox. XLSX respects
the same scopes/filters and rejects more than 10,000 rows instead of truncating.
Before an HQ approval reads seller authorization, it locks the recipient row and exact
native account, then pending/receipt/reward rows. The existing administrative authority
gate already serializes HQ mapping changes; the account lock additionally prevents a
concurrent password-login observation from creating a source conflict between that
authorization check and the wallet credit. No login permission or mapping is created.

Recovery after V100 requires a reviewed reward-capable forward image; the release
transition guard rejects an older generic wallet binary even on empty V100 tables.
To stop automatic money while retaining refund observation, disable auto-credit
only. Keep the schema, ledger, evidence and cutoff; disabling flags does not make
older manual/Excel writers safe. Rehearse the exact recovery artifact before release.
The isolated HTTP/SQL workload and acceptance criteria are in
[`tools/loadtests/recommendation-rewards`](../tools/loadtests/recommendation-rewards/README.md).

## Identity claims: IIN and full name typed by a pharmacist (26.11.2026)

The owner's request of 26.11.2026. ePharm names a pharmacist by the Standard-N user and that user's IIN in the central
directory. 41 people who sell every day have no IIN in Standard-N (for example Standard-N user 18 at Buzurbaeva 23: 87
receipts a day, never a pharmacist), and some directory names are cut short (no patronymic, a nickname). Their receipts
reach ePharm as `standardn_unmapped`: no card, no cabinet, no bonus. Now the pharmacist types the IIN and the full name,
HQ confirms, and the directory wins as soon as it has the person. Package `posm.identity`, table
`pharmacist_identity_claims` and the card columns `iin_source`, `name_source` (V126).

Two kinds of claim, one flow. `person`: a Standard-N user without a card types IIN and full name at the till; the key
is pharmacy + USER_ID (the text that `pos_sales.reported_pharmacist_id` and `posm_pharmacist_mappings.external_user_id`
hold: a positive whole number, no leading zeros). `name`: an active card whose name has fewer than two words proposes
a full name in the cabinet; the key is the card. Statuses: `pending`, `confirmed`, `rejected`, `superseded`. Partial
unique indexes allow one open claim per key: sending again rewrites the open claim (a typo corrected before HQ looked),
and HQ confirms only what the reviewer saw (the confirm body repeats `iin` and `fullName`; a changed claim gets 409).

| Route | Who | Answer |
| --- | --- | --- |
| `POST /api/posm/self/identity` | till, device key | `{state, iinMasked?, fullName?, reason?}`; `state` is `known`, `unknown`, `pending` or `rejected` |
| `POST /api/posm/self/identity/claim` | till, individual device key | the same shape, `pending` |
| `GET /api/admin/posm/identity-claims?status=` | `SYSTEM_ADMIN`, `HQ_HEAD` | up to 500 claims, open ones first; `status` one of the four or all |
| `POST /api/admin/posm/identity-claims/{id}/confirm` | same | the claim, `confirmed`; body `{iin, fullName}` as the reviewer saw them |
| `POST /api/admin/posm/identity-claims/{id}/reject` | same | the claim, `rejected`; body `{reason}`, 3 to 500 characters |
| `GET /api/mobile/me/identity` | pharmacist JWT | `{name, nameSource, iinSource, nameIncomplete, canPropose, claim?}` |
| `POST /api/mobile/me/identity/name` | pharmacist JWT | the same shape; body `{fullName}` |

**A pending claim pays nothing.** Receipts reach a card only through an active HQ mapping or the directory; a pending
claim makes neither, so the user's receipts stay `standardn_unmapped` and no bonus is reserved. Confirming a `person`
claim creates the card (`account_source`, `iin_source` and `name_source` are `self`; active, no phone, no password, the
pharmacy of the till) or, when an active card of the same pharmacy already holds the IIN, attaches to that card without
rewriting it. Either way it writes the active mapping (pharmacy + USER_ID -> card) through
`PosmPharmacistMappingService.upsert`: the same locks and an audit row `created` with the HQ user as actor, which is the
record `docs/22-unified-pharmacist-wallet.md` asks for before money flows. Lock order: `UnifiedAuthorityLock`, the claim
row, the account lock, the card. Refused with 409, the claim stays open: a card with that IIN in another pharmacy,
blocked or pending; an active mapping or a directory row for the user (the claim is out of date); an inactive
pharmacy. Confirming a `name` claim writes the name on the card (`name_source` `self`) and creates no mapping.

Till rules (`IdentityClaimRules`, repeated by the till in `IdentityClaimRules.cs` and by the cabinet in
`identityName.ts`): the IIN is twelve ASCII digits with the Kazakhstan control digit (`IinUtil`); the full name is two
to six words of letters of any script, with a hyphen or an apostrophe inside, each word at least two letters, at most
255 characters; the USER_ID is a positive whole number. 400 for any of them, 403 for a fleet-key till or another
pharmacy's till, 409 when the person is already known (directory, confirmed claim in force, active HQ mapping), 429
`OTP_TOO_MANY_ATTEMPTS` with `Retry-After` after 5 attempts per hour for one Standard-N user and 20 for one till
(`IdentityClaimLimiter` on `posm_staff_rate_limits`, own transaction: refused attempts count too). The answer to the
till never says whether the IIN belongs to someone else's card; HQ sees it. The cabinet allows 5 proposals per hour per
card.

`POST /api/posm/self` and the feeds behind it (`/cabinet-link`, `/contact`, `/tasks`, `/messages`) answer a user nobody
knows with 404, the old message and the new code `STANDARDN_USER_UNKNOWN` (`PosmSelfPersonResolver`, one place). A user
whose confirmed claim is in force (the mapping made at confirmation is active, points at the claim's card, and the card
sits in this pharmacy) is a known person and gets the window; HQ revoking that mapping ends it, as for any mapping.

Flags HQ sees in `conflicts` (computed when the list opens, so they are current): `iin_other_card` (a card holds this
IIN: name, pharmacy, status), `iin_other_claim`, `iin_in_directory` (the directory has this IIN for another Standard-N
user), `mapping_exists`; stored on the claim: `iin_other_card` at sending and `directory_iin_differs`.

**The directory wins** (`IdentityClaimDirectoryReconciler`, after every directory sync, the manual one too; one
transaction under `UnifiedAuthorityLock`; idempotent; a failure never fails the sync). A claim whose Standard-N user
the directory now has, through the pharmacy's profile link, yields:

- same IIN: `superseded` (`directory_same_iin`), the card's IIN source becomes `standardn`, its name becomes the
  directory's when that is a full name;
- another IIN: `superseded` with `directory_iin_differs` and `directory_iin`; the mapping the claim made is switched
  off (audit `revoked`, no actor) so that receipts follow the directory person. The self-made card stays as it is:
  cards are never merged, what to do with it is HQ's decision. The flag stays in «Расхождения» for 30 days;
- a `name` claim whose IIN the directory knows with a full name: the card takes the directory name (`name_source`
  `standardn`), the claim is `superseded` (`directory_name`).

A name HQ typed by hand (`name_source` `hq`) is never rewritten. A superseded claim is decided by the system:
`decided_by` is empty, and who confirmed it earlier stays in the mapping and its audit. The cabinet proposal checks the
directory when it is sent: if the directory already holds a full name for the IIN, the card takes it and no claim is
made. Not covered: cards created before V126 keep the name the directory had at creation; only cards a claim touched
follow the directory afterwards.

## Cabinet sign-in by phone, password change from the till (25.11.2026)

Pharmacists no longer register: the card comes from the Standard-N directory and the till's personal window
(`/api/posm/self`) sets the phone and the password. `POST /api/mobile/auth/login` takes `{iin, password}`
(unchanged) or `{phone, password}`; exactly one identifier, otherwise 400. The phone is normalized with
`PhoneUtil.canonicalOrNull` (`+7` and ten digits). `PharmacistPhoneOwners` finds the cards whose stored phone
means that number in any format (current writers store `+7XXXXXXXXXX`; an HQ edit keeps what was typed). Sign-in
needs exactly one owner, locks the card like the IIN path and checks the number again under the lock. Unknown,
shared or passwordless numbers and wrong passwords get the same 401 `INVALID_CREDENTIALS`; a blocked card gets 403
as by IIN. `PharmacistPasswordLoginLimiter.checkPhone` spends the budget of the owner's IIN, so IIN, phone, POSM and
`/learn` attempts share one durable row per account; a number of no single card has its own budget. Malformed
requests are refused before the limiter.

`POST /api/posm/self/contact` fills an empty phone (another number, or a number another card holds in any format,
is 409) and sets or replaces the password (POSM rules: 4 to 128 characters, at most 72 UTF-8 bytes). A replaced
password revokes every mobile refresh token of the person (cabinet sessions, till one-time-link sessions included);
issued access JWTs keep their 15-minute lifetime. POSM staff sessions end through the V061/V097 `password_hash`
triggers. The vault captures the new password after the flush, as on first set. The current password sent again
keeps the hash and the sessions. The cabinet no longer offers SMS activation, registration or reset; those
endpoints stay for older clients.

## Pharmacist access recovery candidate — 2026-11-10

All pharmacist password writers share the BCrypt limit of 72 UTF-8 bytes without
trimming or truncation. Existing route minimums remain: POSM 4, HQ 6, learner/reset 8.
Initial learner activation validates before phone locks/proof consumption, so an
invalid new password can be corrected without spending the verified activation.
Existing learner password verification is unchanged; older incompatible passwords
can be replaced only through the established verified recovery flow.

The account login limiter hashes the same trimmed IIN used by validation/lookup.
Whitespace variants share the original attempt budget/window; no counter reset,
new quota or weaker password proof is introduced. Unknown accounts, wrong passwords
and blocked POSM accounts retain the generic invalid-credentials response.
Password reset request, verification and completion use the same trimmed IIN;
surrounding whitespace neither changes account lookup nor creates a new quota key.

Only after a correct POSM password, an active unassigned account receives 403
`STAFF_PHARMACY_ASSIGNMENT_REQUIRED`; a different assigned pharmacy receives 403
`STAFF_PHARMACY_MISMATCH`. Pending accounts retain 200/pending without a grant.
These results explain an HQ action, never activate or transfer a person, reveal
the other pharmacy, or grant wallet access. Heartbeat `session_revalidating` and
`pending_approval` are bounded diagnostics, not authority.

Scheduled Standard-N directory replacement uses an explicit transaction for
delete+insert+flush, including the scheduled self-call. External Firebird reads
occur before that transaction. A failed replacement rolls back; independent
readers keep the previous committed snapshot until the new snapshot commits.
Repeated staff logout does not overwrite the first recorded revocation time.
No migration or production data mutation is part of this candidate.

## OTP admission and retry contract (local post-157 candidate)

OTP/phone-lock entry points share a fair admission advisor **before** the
transaction advisor. `SMS_MAX_CONCURRENT_TRANSACTIONS` defaults to 4, capped at
`floor((Hikari maximum-pool-size - 2) / 2)`: mobile wrappers may retain an outer
transaction while running a `REQUIRES_NEW` OTP transaction. The pool must contain
at least four connections. A caller may wait at most two seconds for a permit before
opening a transaction, so a short healthy burst does not immediately fail. An
interrupted wait preserves the caller's interrupt flag. Nested annotated calls reuse admission; rejection is
503 `OTP_PROVIDER_UNAVAILABLE`, without borrowing a connection or inventing a retry
deadline. The separate shared provider-call budget `SMS_MAX_CONCURRENT` defaults
to 2. Phone locks, durable attempt counts, invalidation and one-use proofs remain
inside their original transactions. Cancellation/error releases in-memory permits;
it never grants authentication or automatically retries a mutation.

The released SMS-free initial registration remains SMS-free and permanently
advertises `registrationSmsRequired=false`. Its phone-lock/BCrypt transaction also
uses admission; overload can return 503, but cannot restore SMS registration or
label a self-declared phone as verified. Reset/activation, genuine previously
issued proofs and other OTP channels retain their security contracts.

This bounds slow-SMS/lock-storm damage; it does not increase BCrypt CPU capacity
or replace a production-sized load test. Admission is not a separate connection
pool: unrelated authentication requests can still wait for a shared account-row
lock. Account locks are intentionally preserved; cross-path contention needs an
integration stress regression before claiming all database contention is gone.

Known account/device/SMS 429 windows return matching positive integer `Retry-After`
and optional JSON `retryAfterSeconds`, rounded up from their actual remaining
deadline. Unknown provider availability has no invented cooldown. An exhausted
POSM OTP proof returns `retryAction=REQUEST_NEW_OTP` with the same 429/code and no
countdown: its attempt budget cannot recover by waiting. Clients may preserve
entered form data but must never replay passwords/OTP, send SMS, or retry account
mutations automatically.

Release publication takes a PostgreSQL transaction advisory lock per normalized
platform and flushes retired current rows before insertion. New V099 enforces one
current row per platform with a partial unique index. Historical duplicates fail
migration rather than selecting a signed release silently; resolve that condition
explicitly before deployment. No previously applied migration is edited.
An application rollback to the previous binary may read schema99, but publication
of another POSM release must remain frozen until the new binary is restored: the
previous publisher does not flush retired rows before inserting under the new index.

The pool8/pool10 regression uses real Spring transactions/advisors and Hikari with
fake physical JDBC connections, not a PostgreSQL/SMS transport. It holds the budget,
checks bounded rejection without borrowing connections, and verifies that unrelated
work can still use the two remaining slots. Separate regressions cover a healthy
burst longer than one second, caller interruption, nested calls and permit cleanup.
The fake JDBC configuration is explicitly `TestConfiguration`; Boot's real component-scan
exclusion filter must keep it out of application and integration-test datasources.

Release acceptance additionally requires full Spring/PostgreSQL16 CI on the exact
revision and the unchanged isolated500-desk registration, login and session workloads.
These use synthetic identities, positive recommendations/playlists and SQL financial
checks; they never call real SMS or production. Current execution evidence is recorded
in the release section of the root `PLAN.md` and the PR. A local CPU-limited load run
does not certify production capacity. Production deployment and real SMS acceptance
remain separate explicit gates.

## RPS registration availability

The authenticated POSM identity is `(pharmacyId, deviceId)`, not the Windows/device
name alone. Staff registration, login and SMS device budgets and staff advisory locks
use that complete identity. Independently authenticated pharmacies named `KASSA` or
`MANAGER` must not consume one another's quota or serialize one another's employee
requests. Multiple processes in the same pharmacy/device still share a budget.
The global IIN/phone limits, attempt durability, individual key checks and original
maximum/window values remain enforced. Existing hashed rate counters are not deleted;
the corrected device scope starts its own budget and ordinary retention removes old
counters. This change needs no migration and does not move existing pharmacists.

HTTP 429 means an attempt budget was exhausted, not that the service crashed. The
POSM form keeps the entered data and offers continued cash-desk work; it must not
automatically retry an account mutation. Transport failures and 5xx are separate
diagnostics. The isolated RPS workload in `load-tests/` checks the HTTP result,
process-bound grant, duplicate rejection and actual pharmacy assignment in SQL.

## SMS-free initial RPS registration (v0.2.13)

Initial RPS registration always advertises `registrationSmsRequired=false` in the
existing individual-device capability response. The historical
`POSM_STAFF_REGISTRATION_SMS_REQUIRED` setting cannot re-enable SMS in this backend;
its configuration mapping remains only for older rollback binaries. POSM sends IIN, full name, phone, password and
`deviceContext` to `POST /api/posm/staff/device/register-without-sms`. It sends no
code/challenge and makes no SMS-provider request. The active profile belongs to the
authenticated device's pharmacy and receives the usual process-bound employee grant.

V098 adds only nullable `pharmacists.phone_verification_status`: old rows stay
unclassified (`NULL`); this route records `self_declared`, not verified ownership.
Successful normal RPS SMS registration records `sms_verified`. Existing accounts,
IIN/phone uniqueness, password/vault checks, durable registration limits and individual
device authentication remain enforced. Declaring a number grants no password-reset,
mobile-login, merchandising-payment or Standard-N identity proof.

New RPS register-OTP issuance always returns 503 before taking phone locks or
contacting the provider, without sending SMS; already
issued genuine registration codes may still complete. Login, activation, reset,
`/learn`, mobile and producer OTP contracts remain unchanged. POSM 1.0.81 never
restores the registration SMS form for an unavailable, missing or old capability:
unsupported registration keeps the entered form, explains the problem and permits
cash work. No request is automatically replayed. Older installed binaries need the
signed client update to remove their historical SMS controls.

## POSM employee login independent of Standard-N (v0.2.9 candidate)

`GET /api/posm/staff/capabilities` retains protocol 2 and advertises
`deviceEmployeeAuthVersion: 1`. POSM 1.0.77 uses `/device/login`,
`/device/register`, `/device/session/check` and `/device/logout`: the device
context contains the actual Standard-N process instance and an optional independently
observed Standard-N employee context. Missing or ambiguous database evidence does
not prevent ePharm password login. It does not prove a Standard-N identity match.

Both registration routes assign the active pharmacy authenticated by the individual
POSM device key, including legacy `/register` without a database session. This is
the same authoritative pharmacy boundary used for POSM sales. A contextless legacy
registration returns `registered` without inventing a strict session; device
registration returns its own authenticated grant. Existing pending/blocked or
already assigned accounts are not automatically activated or transferred. Historical
unassigned records require a verified, individually audited HQ activation.

V097 adds `posm_device_staff_sessions` separately from strict legacy sessions, plus
expiry/revocation indexes and pharmacist/device change revocation triggers. Password,
phone proof, active pharmacy/device, cross-pharmacy access and shared rate limits
remain enforced. A pharmacy package can be installed on multiple terminals: login
replaces strict/device grants only for the same device/pharmacy/process. Password,
account and device-key revocation still cover all corresponding processes. The
process identifier is a client observation, not hardware or financial proof. Late first reliable Standard-N evidence binds the existing grant;
a different known cashier/session/workstation or process revokes it. Unknown evidence
does not erase known evidence. Without readable source data, a user switch inside
the same process cannot be reliably detected. No recommendation-sale attribution or
bonus crediting is enabled by this login.

When the server/SMS is unavailable, the client releases mandatory presentation and
permits work. POSM receipt, recommendation, display and `/learn` contracts remain
independent. Roll back the application without deleting the additive session table;
1.0.77 disables its new flow when an older server lacks the capability.

## Local candidate: internal self-password change

`POST /api/admin/auth/password` accepts only `currentPassword` and `newPassword`
for the authenticated internal account. It is a self-service route for all admin
roles, not an account-management grant and not a pharmacist/producer/device route.
Success is 204; wrong proof or invalid new password is 400, not an authentication
refresh challenge. New passwords require 12–72 characters and at most 72 UTF-8
bytes. No supplied password/hash/token is written to the change audit.

The local V093 migration adds admin `session_version` and password-change metadata.
Admin access JWTs carry `sv`; every authentication compares it to the current
account. A legacy token without `sv` is version zero and works only while the
account remains at zero. Password update locks and reloads the account, verifies
the principal version/current password, increments the version and revokes refresh
tokens transactionally. Login and refresh use account-before-token lock ordering.
Refresh validates/rotates the credential under that lock before inspecting account
status: revoked/expired credentials return 401 even for a blocked account; an
otherwise valid credential for an inactive account remains 403. A rejected refresh
does not issue new credentials, and its transactional rotation rolls back.
Pharmacist token handling is unchanged. Ordinary logout retains its existing
refresh-only revocation semantics; it is not upgraded to global JWT revocation.

The new password endpoint has a persistent per-account attempt budget (five
attempts per 15 minutes). Its separate transaction commits before password proof
checking, so failed proof cannot roll back the budget. Exhaustion is 429; there
is no permanent account lock. This is not a general limiter for existing login,
HQ reauthentication, SMS or other proof endpoints.

Release prerequisite: all serving backend nodes must enforce session versions
before immediate old-token revocation is claimed. An old binary during a rolling
rollout or rollback ignores this fence. V093 is only a local unapplied candidate;
DB transactions, concurrency and the real security pipeline require PostgreSQL
integration verification before release.

Path: `admin-panel/backend/`.

Stack:

- Kotlin 2.0.21;
- Spring Boot 3.3.5;
- JVM toolchain 22;
- Gradle 8.10.2 wrapper;
- PostgreSQL 16 + Flyway;
- Redis;
- MinIO/S3;
- Spring Security + JWT;
- Testcontainers + JUnit 5 + MockK.

The backend is a modular monolith under package `kz.epharm`.

## Domains

| Package                               | Responsibility                                                                                      |
| ------------------------------------- | --------------------------------------------------------------------------------------------------- |
| `auth`                                | Admin auth, JWT, refresh tokens, roles.                                                             |
| `mobile.auth`                         | Pharmacist OTP auth, registration, refresh/logout.                                                  |
| `mobile.profile`                      | `/api/mobile/me` profile and authenticated self balance/recommendation APIs.                        |
| `mobile.catalog`                      | Public mobile catalog with a configured website source and local snapshot fallback.                 |
| `mobile.promotions`                   | Public active promo campaign feed.                                                                  |
| `mobile.receipts`                     | Authenticated receipt upload/history.                                                               |
| `mobile.pharmacies`                   | Public active pharmacy list.                                                                        |
| `catalog`                             | Internal/admin product master data used by rules.                                                   |
| `promo`                               | Campaigns, Medusa product snapshots, tiers, campaign-generated rules.                               |
| `rules`                               | Rules Engine admin CRUD and POSM matching logic.                                                    |
| `receipts`                            | Receipt storage, moderation, POS/Excel reconciliation, bonus crediting.                             |
| `pharmacies`                          | Chains and pharmacies.                                                                              |
| `pharmacists`                         | Pharmacist registry, status, balance.                                                               |
| `finance`                             | Payout batches, items, approval, scheduler.                                                         |
| `finance.merch`                       | Disabled-by-default verified CRM executor grants, signed approvals and idempotent monetary rewards. |
| `finance.premium`                     | Sales premium from Standard-N: daily collector (job, matrix, statement), cumulative import by IIN, wallet credit and correction, pending sellers. |
| `screens`                             | Broadcast/screen media and playlists.                                                               |
| `banners`                             | Admin-managed mobile banners.                                                                       |
| `medusa`                              | Historical Medusa client and durable read-only product snapshot.                                    |
| `storefront`                          | Read-only client and DTO mapping for the current Inkar website catalog.                             |
| `posm`                                | POSM recommendations, outcomes, sales, playlists, heartbeat, CDP.                                   |
| `fulfillment`                         | Signed storefront order ingest, pharmacist cash-pickup queue and actions.                           |
| `appupdate`                           | POSM app release metadata and auto-update endpoint.                                                 |
| `merchtasks`                          | Optional authenticated CRM task bridge, exact staff URL validation and bounded concurrency.         |
| `training`                            | Programs, routes, assignments, events, attendance, results and awards.                              |
| `lms`, `ai_exam`, `lift`, `dashboard` | Admin sections and reporting.                                                                       |
| `reporting`                           | HQ-only campaign JSON/PDF, confirmed displays, attributed/0–120-second sales.                       |
| `posm.staff`                          | Optional device-scoped employee authentication and purpose-bound SMS recovery.                      |
| `shared`                              | Security, errors, media proxy, storage, validation, dev reset.                                      |

## API Map

Shared IIN validation checks twelve ASCII digits and the two-pass checksum, and
preserves rejection of the empty `000000000000` identifier. It does not decode a
birth date, century or sex: see the [official explanation](https://www.gov.kz/org/migration/press/news/details/132237?lang=ru).
This applies to staff registration/login, pharmacist administration and the learner
web authentication API. Format validation does not prove government issuance or
ownership; authentication, uniqueness and identity observations remain separate.

POSM staff identity is an independent optional API under `/api/posm/staff`: capabilities,
login, OTP request, registration, password reset/activation, session validation and logout.
Every route requires a non-legacy individual `X-Posm-Key`; opaque staff grants also bind
the pharmacy/device and observed process/cashier context. Pending registration does not
assign or activate a pharmacist. `POSM_STAFF_AUTH_ENABLED=false` is the safe default.
Neither this flag nor a failed login gates Standard-N, recommendation delivery or sales.
See [manual staff acceptance](rollout/rps-auth-test.md).

Protocol 2 additionally advertises `nameFallbackSupported=true`; the new client requires this
marker and disables its whole staff flow on an older server. `StaffContext` appends optional
`standardNFullName` and `standardNFullNameStatus`, persisted by V096 and bound to POST session
validation. Legacy GET projects both IIN and name observations away for old four-field clients.
An available IIN always takes priority. Only missing/unavailable IIN permits exact full-name
comparison against the already authenticated profile: case/whitespace and word order are
normalized, with all words retained. Initials, generic role labels and fuzzy/partial matches
do not identify a person. There is no account lookup or financial seller binding by name.
IIN-only diagnostics remain `UNKNOWN` for this path; separate permission-filtered identity
method/status/check time explain name matches and mismatches without exposing source names.

OTP issuance/verification serializes Daribar's phone-wide codes across mobile and POSM.
New staff challenges are purpose/account/device-bound and one-use; independent transactions
preserve invalidation and attempt budgets on request errors. Changing staff status/pharmacy/
password or device credentials revokes grants. Password reset also revokes mobile refresh
tokens; previously issued mobile access JWTs retain their existing short lifetime until expiry.
Transient expired staff challenges/sessions/rate counters are purged after 24 hours.

RPS passwords accept 4–128 characters and at most 72 UTF-8 bytes, excluding all-whitespace
values. Existing-password mobile/`/learn` login accepts the same minimum; unrelated password
creation policies are unchanged. Both HTTP password-login controllers use one durable hashed-IIN
budget (10 attempts / 5 minutes) before the authentication transaction. Failed logins do not
roll back the budget, and switching routes or devices cannot bypass it. Device/SMS limits remain.

The existing POSM heartbeat accepts optional `staffAuthStatus` from a strict fixed allowlist.
Unknown/free-text values are discarded without rejecting the heartbeat; an omitted value clears
obsolete diagnostics from an older client. The connected-screen API exposes the bounded current
status for operations only. It is never consulted for employee authorization, account activation,
sale attribution or wallet eligibility. Redis/local presence expiry applies normally; no new PII is stored.

### Admin

- `POST /api/admin/auth/login|refresh|logout`, `GET /api/admin/auth/me`
- `/api/admin/dashboard/summary`
- `/api/admin/dashboard/recommendations` — конверсия показ→продажа + время до продажи (V032)
- `/api/admin/reports/campaigns/{promoId}` and `/{promoId}/pdf` — HQ/SYSTEM_ADMIN-only campaign reporting;
  inclusive `from`/`to` dates in Asia/Almaty, at most 366 days, `no-store`. Confirmed display ACKs,
  accepted actions, verified attributed sales and the separate 0–120-second subset remain distinct.
  See [report methodology and rollout](21-rps-rollout-and-manufacturer-reports.md).
- `/api/admin/catalog/products`, `/brands`, `/mnn-groups`, `/trigger-options`
- `/api/admin/rules/**`
- `/api/admin/promo/**`, including `/refresh-prices` and `/{id}/rules`
- `/api/admin/banners/**`
- `/api/admin/storefront/products`, `/products/{id}`
- `/api/admin/pharmacies/**`
- `/api/admin/pharmacists/**`
- `/api/admin/reconcile/**`, including `/submit` and `/import-excel`
- `/api/admin/payouts/**`
- `/api/admin/finance/sales-premium/import` (POST), `/recompute` (POST), `/statement.csv` (GET), `/matrix` (POST): sales
  premium load, calculation from Standard-N now, month statement and matrix of the month, SYSTEM_ADMIN/HQ_HEAD only, see
  [Sales premium](#sales-premium-from-standard-n-26112026)
- `/api/admin/screens/**`, including `/connected`, `/broadcast`, and per-slot broadcast replacement
  at `/broadcast/slots/{1..12}`
- `/api/admin/app-releases/**`
- `/api/admin/tills/commands` (POST, GET) and `/{id}/artifact`: diagnostics and restart of tills without AnyDesk,
  SYSTEM_ADMIN/HQ_HEAD only, see [Remote till commands](#remote-till-commands)
- `/api/admin/lms/courses/**`
- `/api/admin/training/**`
- `/api/admin/ai-exam/questions/**`
- `/api/admin/lift`

Live lessons extend the existing course API rather than replacing older lesson kinds. The
administrator's live-room endpoints manage the broadcast state and interactions; the authenticated
learner endpoints expose only the assigned lesson's room. A learner cannot complete a live lesson
until the room has ended. Stream URLs are accepted only over HTTPS with provider-aware host checks;
the new `V059` migration expands the lesson-kind constraint after the already deployed `V058`
course-live schema migration without editing either previously applied migration.

`GET /api/admin/catalog/trigger-options?kind=acc_group|acc_subgroup|acc_mnn&q=...`
returns selectable scopes and metadata for the active ACC classification snapshot. Campaign rule
pairs keep the historical product trigger by default; optional `triggerKind`, `triggerValue` and
`triggerLabel` represent a broad ACC scope. The backend validates the scope against the active
snapshot when saving or activating it. POSM matching uses only an unambiguous exact cart barcode
for broad scopes, never a fuzzy product name; the response still names the actual scanned item.
The recommendation event and sale attribution paths deduplicate a sold offer within one receipt,
even if multiple grouped items triggered separate displays.

Trigger refs also accept optional `triggerConditions: [{kind, value, label}]`: up to three
distinct ACC group/subgroup/INN factors, excluding a repeat of the primary factor. All factors
must match the **same** unambiguous scanned barcode; separate basket lines cannot satisfy parts
of the conjunction. Active snapshot keys are validated on save/publication. Factor order and
labels do not change rule identity or bypass publication/financial permissions. POSM wire DTOs
remain unchanged. Duplicate conjunctions and simultaneous active replacement/cross-sell for the
same conjunction are rejected rather than silently losing a pair or suppressing both at runtime.
Publication also checks known ACC intersections, including confirmed aliases for exact products;
empty conjunctions remain editable drafts and known replacement/cross-sell overlaps cannot launch.
Cached pre-conditions admin editors must refresh before changing such a campaign. The current
editor sends `X-Epharm-Trigger-Conditions: 1` on rules/save/discard/apply; without it a compound
published/prepared/incoming set returns 409 before mutation. Simple legacy campaigns and POSM
requests are unaffected. The header is a compatibility signal, never an authorization grant.

`POST /api/admin/promo` still accepts the legacy campaign payload. For trigger-first creation it
also accepts optional `initialRecommendation: {type: "substitution"|"crosssell", trigger}`,
where `trigger` has the same shape as one entry in `PUT /api/admin/promo/{id}/rules`.
`medusaProductId` remains the product **offered** by the campaign; the initial trigger is the
scanned product or ACC group/subgroup/MNN that starts the recommendation. This path creates the
campaign and its first rule in one transaction and only as drafts. It rejects a missing offered
product, an immediately active campaign, an exact-product self-trigger, or a scope absent from
the active ACC snapshot. A failed rule cannot leave an empty campaign behind; existing clients
that omit `initialRecommendation` retain their previous behavior.

The impressions-and-sales journal uses its configured rolling analytics window, independently of
the header's selected month. PostgreSQL first selects the receipt candidates needed for the page,
including every receipt sharing the boundary timestamp, then expands their items. The final order
remains event time descending and expanded event ID descending. Complete counts, empty receipts,
per-item quantities/amounts and fastest sale attribution are unchanged; deep offsets still cost
more than initial pages. No events or accepted outcomes are synthesized by the dashboard.

### Mobile

Public:

- `POST /api/mobile/auth/sms/request`
- `POST /api/mobile/auth/sms/verify`
- `POST /api/mobile/auth/register`
- `POST /api/mobile/auth/refresh`
- `GET /api/mobile/catalog/products`
- `GET /api/mobile/catalog/products/{id}`
- `GET /api/mobile/catalog/products/{id}/recommendations`
- `GET /api/mobile/catalog/recommendation-pools`
- `GET /api/mobile/catalog/categories`
- `GET /api/mobile/promotions`
- `GET /api/mobile/pharmacies`
- `GET /api/mobile/banners`

Authenticated pharmacist:

- `GET /api/mobile/auth/me`
- `POST /api/mobile/auth/logout`
- `GET /api/mobile/me`
- `GET /api/mobile/me/balance` — own available wallet balance, integer KZT.
- `GET /api/mobile/me/recommendations?page=0&size=20` — active authored recommendation groups.
- `GET /api/mobile/receipts`
- `POST /api/mobile/receipts` multipart.
- `/api/mobile/training/**` for the authenticated pharmacist's assignments, events and notifications.

#### Personal balance and recommendation API for the learning service

Both new GET routes require `Authorization: Bearer <pharmacist-access-token>` issued by
ePharm. This is the same principal used by `/api/mobile/training/**`, not the training
manager's admin account. An admin token (including `TRAINING_MANAGER`) cannot read these
routes. POSM device keys and JWTs issued by another service are not pharmacist credentials.
Never share `app.jwt.secret` with a consuming service.

The current account is reloaded for every call. Only `status=active` may use these routes:
a pending or blocked account returns `403 USER_NOT_ACTIVE`; a deleted account returns
`401 USER_NOT_FOUND`. This check applies even to access tokens issued before a status change.
An active account may read its own balance without a pharmacy assignment. The owner is
always the signed token subject, never a client-supplied phone, `pharmacistId` or header.
These identity selectors are not supported and cannot switch the current user.
Both responses set `Cache-Control: no-store` and contain no IIN, phone or other users' balances.

`GET /api/mobile/me/balance` returns exactly the existing common wallet projection
`pharmacists.balance`, without creating ledger entries or reserving funds:

```json
{ "balanceKzt": 42000, "currency": "KZT" }
```

`GET /api/mobile/me/recommendations` returns:

```json
{
  "items": [
    {
      "campaign": { "id": "promo_example", "title": "Example", "dateStart": null, "dateEnd": null },
      "type": "substitution",
      "trigger": { "kind": "acc_group", "value": "stable-acc-key", "label": "Group label" },
      "triggerProducts": [],
      "offers": [
        {
          "ruleId": "rule_example",
          "product": {
            "id": "product_example",
            "name": "Example product",
            "brand": "Example",
            "mnn": "Example INN",
            "volume": "30 tablets",
            "barcodes": [],
            "ipartId": null,
            "catalogPriceKzt": 2500,
            "catalogUpdatedAt": "2026-01-01T00:00:00Z"
          },
          "bonusKzt": 0,
          "script": "Configured text",
          "advantages": []
        }
      ]
    }
  ],
  "page": 0,
  "size": 20,
  "total": 1,
  "currency": "KZT"
}
```

`type` is `substitution` or `crosssell`. Every rule must be active; a linked campaign must
also be active and within inclusive dates in `Asia/Almaty`. Missing, paused, draft, archived,
expired and future campaigns are excluded. Legacy manual rules without a campaign remain
supported with `campaign=null`, matching the POSM activity policy. A missing offered product
is omitted; an empty group is not returned. Reading does not record impressions, sales,
acceptances, progress or bonuses, and does not activate rules.

Groups preserve separate trigger and offered products, all four authoring modes
(`product`, `acc_group`, `acc_subgroup`, `acc_mnn`), plus legacy `product_any`/`mnn`.
`trigger.value` is a product ID, a list of IDs for `product_any`, a stable scope key for
`acc_*`, or a literal INN string for legacy `mnn`;
`exclude` and the presentation-only `label` are preserved where present. `triggerProducts`
contains known exact-product snapshots only; ACC groups are not expanded into an entire catalog.
Each campaign/type/trigger group contains up to five distinct offers in admin `offerRank`
order, with legacy bonus-descending and rule-ID tie-breakers. Bonus zero means no reward.

Pagination is by whole groups, never by individual offers: zero-based `page`, `size` 1–100
(default 20), `total` number of groups. Invalid bounds/types return `400 VALIDATION_FAILED`;
pages past the end return `items=[]`. Groups sort by most recently updated rule, then rule ID.
This is live offset pagination, not a frozen export: edits between calls can move groups.

This endpoint is a network-wide **directory of configured active recommendations**, not
POSM cart matching or a promise of display/medical equivalence. It does not check a current
receipt, local pharmacy stock, ACC membership of a scanned item, conflicts in that cart,
A/B eligibility, dismissals or personal goals. `catalogPriceKzt` is the last stored catalog
reference price with its update timestamp, not a guaranteed pharmacy price or stock statement.
All data is read locally; neither a retired Medusa origin nor the website is called per GET.

For a separate learning application with its own login, its unrelated user token or an
arbitrary ID is **not** accepted. Reuse the learner's existing ePharm pharmacist login/token
or implement an explicitly verified account-linking/federation flow with the owning team.
No external service, new issuer trust, CORS origin or account-linking flow is configured
by these endpoints. A training administrator's shared token must never stand in for a learner.
Existing mobile logout revokes refresh tokens; an already issued access token retains its
short expiry. These endpoints additionally reject accounts made inactive in the database.

The built-in learning portal at `/learn` already authenticates through `/api/mobile/auth/login`
and uses a pharmacist access token for `/api/mobile/training/**`. It can reuse that same token
for these routes on the same origin. The balance payload uses `balanceKzt`, not the `balance`
field in `/api/mobile/auth/me`; the recommendation directory is under `items`, not a bare array.
A pending account may sign in to the existing learner portal but still receives
`403 USER_NOT_ACTIVE` from the self-service APIs. Diagnose that account state explicitly;
do not replace the pharmacist token with an administrator token or silently activate users.

Structured online courses use per-lesson progress. A completed stage created before lesson tracking
projects its lessons as completed without rewriting history. Legacy URL-only online stages still
accept stage-level progress, while linked courses require the lesson endpoint. Lessons with recorded
progress cannot be deleted (HTTP 409); archiving the course preserves referential integrity.

Learner quiz submissions return aggregate score/pass/attempt information only: no per-question
correctness, answer key or explanations. The deprecated `questions` array is always empty so
already open older `/learn` clients can still consume the response without exposing answer analysis.
Required previous lessons gate both passing and failed
submissions; the passing threshold is checked before display rounding. In the current legacy
lesson schema, required `quiz`/`test` lessons contribute their best successful scores to the course
stage and individually to the assignment average alongside required scored stages; optional tests
do not contribute. A failed retry preserves a previously passed grade. If the passing threshold changes before the
first success, that first success replaces any higher failed score. Missing grades remain absent,
and certificate PDFs show `Без оценки` instead of inventing 100%. Existing issued scores are unchanged.
This compatibility fix does not implement the planned explicit final/practice/intermediate roles,
immutable course/test versions, exact-ratio score storage or an immutable quiz attempt history.

Quiz submissions, administrative assessment attempts, assignment start and lesson/stage progress
take the same PostgreSQL transaction advisory lock per assignment before their first ORM read.
Concurrent accepted requests therefore see committed attempt counts and best grades, including
when creating the first progress row. A request queued behind assignment completion receives 409
without another attempt, certificate or reward. Different assignment keys do not block each other;
rollback releases the lock. Event selection/attendance and inter-assignment counters remain outside
this guard; the existing event/stage row-lock order is unchanged. All serving backend nodes must
run this guard before the concurrency guarantee applies.

Managed administrative training requests additionally use
`training/access/TrainingAdministrativeAccess`. Assignment, participant, result,
preference and certificate visibility follows the pharmacist's **current** pharmacy,
intersected with the existing regional-manager/trainer restrictions. SQL selects
the permitted IDs before loading child records, computing totals or paging. Shared
program/course authoring and event creation/editing require explicit network-wide
access; a participant's pharmacy grant cannot authorize editing a shared event.
Administrative writes recheck the current authority before domain writes. Publishing
programs/courses and configuring their bonuses require separate exact permissions.
Historical person-wide rewards require the existing global financial permission;
without it, reward payloads are omitted and monetary totals are `null`, not zero.
The managed assignment report is the separate XLSX endpoint; the legacy CSV endpoint
explicitly rejects managed callers and remains compatible for LEGACY accounts.
The separate assignment/result XLSX files retain saved employee names within their
learning scope. Assignment reward amount/status stay blank without global financial
authority and a global report scope; they use saved training rewards, not a wallet
balance or a newly calculated payment. Phones, IINs, passwords, answer keys and private
feedback/competency data remain excluded. The frontend reads exact action flags from
a fresh `/api/admin/access/me.trainingActions` response; an unknown or stale managed
permission never falls back to a role title or the old coarse management flags.
These checks do not change learner authentication, quiz scoring, certificate rules,
mobile endpoints or the legacy HQ read-only ceiling.

Public training verification:

- `GET /api/public/training/certificates/{qrToken}`
- `GET /api/public/training/certificates/{qrToken}/pdf`

### POSM

All POSM endpoints require `X-Posm-Key`.

- `POST /api/posm/recommend`
- `POST /api/posm/recommendations/{eventId}/outcome`
- `POST /api/posm/recommendations/{eventId}/shown` — факт+время показа попапа (V032, атрибуция)
- `POST /api/posm/sales`
- `GET /api/posm/playlists/active?pharmacyId=...`
- `GET /api/posm/app/version`
- `POST /api/posm/heartbeat`
- `GET /api/posm/tasks?pharmacyId=...` — scoped CRM assignment with individual device identity.
- `POST /api/posm/tasks/shown` — accepted/rejected confirmation of an actual displayed QR.
- `POST /api/posm/cdp/lookup`
- `POST /api/posm/cdp/register`
- `POST /api/posm/commands/next`, `POST /api/posm/commands/{id}/result`: remote till commands
  (per-device key only, see [Remote till commands](#remote-till-commands))
- `POST /api/posm/self/messages`, `POST /api/posm/self/messages/{id}/shown|closed`: notices from HQ for the person at
  the till (see [Notices to pharmacists at the till](#notices-to-pharmacists-at-the-till-posm-1099))

### Shared Public

- `GET /api/health`
- `GET /api/media/img?u=<configured-catalog-image-url>`

The media proxy allows only the exact configured Medusa or enabled website origin, never follows
redirects, and avoids browser mixed-content blocking when a source image uses HTTP.

## Security Model

- Admin auth uses bcrypt passwords, access JWT, hashed refresh tokens, and admin roles.
- Mobile auth uses Daribar OTP and separate ePharm mobile refresh tokens.
- POSM uses a device key; comparison is constant-time.
- `GlobalExceptionHandler` returns JSON `{code,message,fields?}` instead of stack traces.
- Business errors should be `AppException(ErrorCode, message, HttpStatus)`.
- Receipt approve/reject/import and explicit balance changes require HQ, system-admin or finance
  roles. Administrative pharmacist phone/password resets require HQ or system-admin only; the
  service checks these sensitive fields before applying any profile mutations. A password login
  alone is not a monetary executor proof; the separate fresh OTP and HQ affiliation still apply.
- Receipt/photo bucket access is still a hardening topic: current MinIO bucket is public-readable.

## Mobile OTP

The opt-in monetary merchandising flow uses a separate purpose-bound fresh OTP, never a
generic login code or password JWT as proof of phone ownership. Its signed challenge,
opaque grant and immutable wallet contract are described in
[unified pharmacist wallet](22-unified-pharmacist-wallet.md).

`RecommendationSaleBonusService` is a separate opt-in, post-activation bridge from actual
display ACK and exact printed sale to a source-bound pending bonus. It requires a trusted
internal seller, pharmacy/session/product match and bounded timestamps. It never fabricates
an accepted outcome or bypasses HQ receipt moderation; name-only and database-close evidence
cannot trigger it. Both monetary opt-ins default to false in application and production Compose.

The mobile application calls only ePharm endpoints. `OtpProvider` selects the implementation:

- `OTP_DEV_MODE=true`: local fixed code for development/tests; no external request;
- `OTP_DEV_MODE=false`, `OTP_PROVIDER=daribar`: Daribar generates, sends and verifies the code;
- `OTP_PROVIDER=p1sms`: legacy fallback where ePharm generates the code and p1sms only delivers it.

Daribar requests are backend-only: `POST /api/v2/sms` with `sms_type=auth`, followed by
`POST /api/v2/auth` with `validation_code`. Daribar access/refresh tokens are deliberately discarded;
after phone verification ePharm issues its own JWT pair. The database stores the provider id and an
opaque nonce, never the external OTP. Per-phone resend cooldown, TTL and attempt limits remain local.
See `docs/15-daribar-otp.md` for the extracted contract and operations.

## Catalog source and historical snapshots

The current authoritative external catalogue is `https://www.aptekasosklada.kz`
(`STOREFRONT_CATALOG_ENABLED=true`). It supplies product lists, detail, category handles and
campaign price refreshes server-to-server. The remote Medusa origin is retired:
production uses `MEDUSA_ENABLED=false`, and the client also disables its remote requests and crawler
whenever the website source is enabled, even if an older environment still contains a true legacy flag.
This does not rename historical DTOs/packages or delete stored products and campaign references.
A complete local historical snapshot
remains the outage path and preserves historical product IDs referenced by campaigns. Website
product IDs can differ from historical IDs. Viewed website products retain their original handles
and last-known-good payload in `storefront_product_references`, scoped by configured origin, so
campaign price refresh continues after a cold restart. Campaign/rule pricing uses this same source
priority and refreshes the website detail instead of treating its in-memory copy as a fresh price.
If the website fails, campaign refresh uses only the stored product from that same website;
historical data is not written over a website price or barcode. The complete historical snapshot
remains available for read-only catalogue browsing. Missing data preserves existing campaign values. Unknown website-only
IDs fail promptly rather than waiting on the retired origin. No barcode is inferred from a name.
The feature flag and website origin are supplied through production environment variables. If neither
the website nor suitable saved data can satisfy a request, it fails promptly; the retired server is
not a fallback. Historical snapshot availability does not assert current pharmacy stock or freshness.

For the historical source, backend is the only consumer of Medusa from this repo. It fetched:

- product list/detail/category data;
- images, barcodes, MNN/ATC/rx metadata;
- product recommendation data;
- pharmacy stock locations exported into `seed/pharmacies.json`. This one-time export is the only source
  of the pharmacy register (`RealPharmacySeeder`, empty table only); nothing refreshes names, cities or
  networks later, the 06:30 job recalculates sales metrics only. Pharmacies under the umbrella chain
  `inkar` get their network from the start of the name in one spelling (`ChainNames`), also when created
  in the admin panel. Closed pharmacies are `"active": false` in the file.

Mobile/admin receive normalized DTOs from this backend, not raw Medusa responses.

Important behavior:

- The retained PostgreSQL read model contains the last complete, versioned historical snapshot.
  Its legacy crawler is inactive with the website source enabled. Website outages may use this
  local read model; no request is sent to the retired remote `q` endpoint.
- Search covers product name, brand/manufacturer, MNN, category, SKU, barcode and alternative names.
  A trigram index keeps partial Cyrillic/Latin searches fast. Failed or partial refreshes retain the
  last complete generation and retry automatically.
- `metadata` placeholders such as `-`, `_`, `none`, `n/a`, `н/д` are treated as empty.
- Prices may be missing in Medusa; UI must degrade to "Цена в аптеке".
- Product images may be HTTP; UI should display them through `/api/media/img`.

## POSM Matching

Barcode is the primary key for cash-desk recommendation matching.

`CartItemDto` can include:

- `barcode`/EAN - primary matching key, resolving any confirmed `products.barcodes[]` alias;
- `name` - fallback matching key after normalization;
- `sku` - Standard-N internal `iPartID`, exact fallback when barcode ownership permits it;
- `qty`.

Ambiguous barcode or normalized-name matches are logged and skipped. The system prefers not showing a
recommendation over showing the wrong one.

Duplicate records of one product (26.11.2026, analytics only). The products table can hold one medicine twice:
a website catalogue record (`prod_01K...`, often with the local Standard-N code `8811000...`) and a Daribar
record (`prod_Daribar...`, with the EAN). Two records are duplicates when they carry the same normalized name,
or share a barcode and agree on every number, dose, form and qualifier. Duplicates are one product wherever
sales are analysed: `resolveToProductIds` does not call a name ambiguous when only duplicates of one product
carry it (the line resolves to one of them; it stays unresolved when another line of the same receipt already
stands for that product by an exact key, so payout checks see the same lines as before), and
`RecommendationAttributionService` counts the sale of a tip ("Принято") when the sold line is the recommended
record or its duplicate (`RulesEngineService.canonicalProductIds`, a primary key read of the ids in the receipt
and the tips; one sold product closes one tip). A name that merely begins like another, a different pack or dose
and a barcode shared by differently written doses stay different products. Showing tips and the money paths
(`resolveStrongProductIds`, reward cases, reconciliation) are unchanged and never match by name. The name index
logs one summary line per call instead of one warning per catalogue name.

Campaign/product authoring and catalogue DTOs expose additive `barcodes[]` while retaining the
legacy primary `barcode`. The website adapter joins explicit UUID `WARE_ID` values to the active
ACC snapshot in a batch, with case-insensitive WARE matching and global source-code ambiguity
rejection. It does not infer identity from names or marketplace SKUs. Complete source identities
replace revoked aliases, whereas incomplete/unavailable sources preserve saved keys. Distinct
variant doses/forms/packs are not flattened. A supplied ambiguous barcode blocks name/iPart
fallback, including when its other owner has no active rule. Money attribution additionally
rejects unknown barcodes and conflicting supplied exact identifiers. POSM keeps sending one actual
scan code; `triggerBarcode` in the response stays that actual code, not the first catalogue alias.

Both normalized and fuzzy name fallback retain numeric multiplicity, dose units (including
concentration denominators), and release modifiers such as DSR. Explicit recognized dosage forms
must agree; known capsule/tablet/plural abbreviations are normalized without lowering the fuzzy
threshold. No new acceptance path for omitted forms is added: existing similarity and ambiguity
checks still apply. Name fallback remains secondary to exact identifiers and does not establish
clinical interchangeability.

## Dashboard Journal

Each recommended product in one receipt retains its own nonnegative attribution
interval. Multiple matching events cannot duplicate receipt rows or amounts;
conflicting known sellers and foreign pharmacies cannot supply that interval.
An explicit captured refund returns additive nullable `receiptStatus=refund` and
no sale-attribution timing. Neither this projection nor the UI rewrites refunds,
sale evidence or wallet entries. Unresolved sellers are not filtered from the journal.

Administrative pharmacist `receipts30d` is now a read-only aggregate of `pos_sales`
over the last 30 days through the request time, once per visible list (or detail).
It matches the saved internal seller and current pharmacy exactly, excludes refunds,
and applies existing financial field restrictions after projection. The stale
`pharmacists.receipts_30d` column is not rewritten; legacy mobile
consumers of that column retain their existing contract. The current registry XLSX
and newly generated payout-item receipt snapshots use the same read-only aggregate
as the registry. Previously generated payout items keep their captured values;
wallet amounts, eligibility and reservations are unchanged. No counter backfill or
migration rewrites the legacy profile column.

`GET /api/admin/dashboard/recommendations?page=0&size=50` returns KPI values for the configured
analytics window and a server-paginated combined journal of recommendation displays and POS sale
items. Sale JSON arrays are expanded in PostgreSQL before `LIMIT/OFFSET`, so every receipt item is a
separate row and all pages can be traversed without loading the complete sales window into the JVM.
The response includes `page`, `pageSize`, `totalElements`, and `totalPages`. Page size is capped at
100; the legacy `limit` query parameter remains an alias for the first-page size.

## Commands

```bash
cd admin-panel/backend
export JAVA_HOME=/Users/amir/Library/Java/JavaVirtualMachines/temurin-22.0.2/Contents/Home

./gradlew bootRun
./gradlew test
./gradlew build
```

Local dev needs the root `docker compose up -d` infrastructure.

## Opt-in unified administrative access

`auth/access` connects `auth/policy` to persisted grants and request-bound SQL
scopes. V066 defaults existing accounts to LEGACY; new accounts are MANAGED and
empty-deny. Current admin identity/status/role are reloaded from the database,
while mobile, POSM and HMAC authentication remain separate. Exact handler patterns
are catalogued; unknown/NOT_READY routes deny managed accounts. Existing role
checks remain an additional ceiling. See [the U1/U2 contract](23-unified-admin-data-and-access.md)
for supported surfaces, field projections, management locking and remaining gates.

The local unification candidate adds `GET/PUT /api/admin/promo/{id}/changes` and
`POST /api/admin/promo/{id}/changes/apply`. PUT accepts `expectedRevision` and one
complete `bundle: {campaign, rules}` or explicit `discard: true`; Apply accepts the
saved `expectedRevision`. Author identity comes from the authenticated principal.
Both campaign and recommendation capabilities are required, globally scoped;
financial changes require separate configure-bonus permission. V067 retains pending
JSON separately from published tables and keeps an immutable publication history.
Conflicting revisions/source conditions return409 without partial publication.
These endpoints do not change POSM authentication, paths or response shapes.

The disposable dev reset explicitly truncates pending/publication history together
with its existing fixture-only FK set before reseeding campaigns. It uses no CASCADE
and does not weaken append-only triggers. Both destructive utilities are excluded
when `prod` is active, including a combined `prod+dev` profile. It must not be run
against restored business data; migration rehearsals use plain Flyway without it.

## Native unification candidate (opt-in)

`merch/inventory`, `merch/placements`, `merch/producer/auth`, `merch/tasks` and
`merch/delivery` own the new database-backed workflows. They do not proxy the CRM
administration UI. Before cutover, `merchtasks/MerchTaskClient` serves the legacy
integration. With `MERCH_LEGACY_PORTAL_CUTOVER_ENABLED=true`, its recommendation context
comes only from the owned `merch_recommendation_signals` and `merch_product_balances`
tables; missing native data does not fall back to CRM HTTP. Existing global disable,
response validation and concurrency limits still apply. The optional SELECT uses the
caller's connection, a savepoint and a one-second JDBC timeout so SQL errors or cancellation
do not abort later local recommendation-event writes. Exact active verified pharmacy/branch
scope, barcode/ipart triggers, branch precedence and stock staleness are preserved.
Task stock observations are not authoritative product balances.

`tools/unification/owned_context.py project` preserves source IDs and raw provenance from
the pinned typed CRM snapshot. Its manual `sync` mode retains the reviewed-file workflow:
private input and source/target hashes, dry-run by default, explicit pharmacy count and
context hash for apply, a lock acquired before the snapshot, and immutable input audit.
Stale/conflicting observations and generated-ID collisions across product roles are refused.
It does not schedule refreshes, create identities, issue tasks or replay financial rewards.
`MerchTaskDelivery` chooses one writer for
polling and routes acknowledged native-owned tokens independently of issuance flags.

Native delivery, producer authentication and placement expiry default OFF.
`MERCH_TASK_EVIDENCE_ROOT` and `MERCH_TASK_LEGACY_FILES_ROOT` are private owner-only
absolute directories; missing storage returns503 rather than publishing evidence
in the screens bucket. `/api/public/merch/tasks/**` requires the task capability
header per object. A capability never grants an administrator or pharmacist identity.
Paid recipient confirmation remains a separate purpose-bound proof.

`GET /api/admin/merch/tariffs` and `PUT /api/admin/merch/tariffs/{category}` expose
the supplier tariff matrix to HQ accounts with explicit global merchandising view
and financial access; writes additionally require global edit access. The update
uses the returned `updatedAt` as a compare-and-set token and records immutable
before/after audit entries. Amounts travel as exact integer strings in the full
nonnegative bigint range; the multiplier retains the source range 0.1–10 with
three decimal places. Editing a tariff never rewrites its source snapshot or
reprices saved placement items, submitted requests, rewards or wallet entries.
The UI entry uses fresh `canViewMerchTariffs`; stale edits require a fresh fetch.

`reporting/exports`, `reporting/producers` and `reporting/xlsx` create separate XLSX
workbooks with explicit view/export permissions. Producer associations are explicit
versioned facts, not brand/name heuristics. Wallet reports read postings once and
retain unknown/conflicting historical pharmacy attribution as unknown.

## Learner credential export (D9)

`learnercredentials` owns POST `/api/admin/learner-credentials/exports`, POST
`/{id}/download` and DELETE `/{id}`. Route preflight requires scoped registry view;
the transactional service separately requires explicit HQ2 credential authority, current
password reauthentication and current access/credential versions. The preparatory API
returns metadata only; download returns a no-store one-use XLSX. Ordinary roster exports
never contain credential values. `app.learner-credentials.enabled` defaults false.
Key file, bounded TTL/batch/attempt settings and capture behavior are documented in
[the data/access contract](23-unified-admin-data-and-access.md#d9-current-learner-credential-export).

### Scoped shared staff access (unification candidate)

`/api/admin/merch/staff-access` and `/options` require explicit fresh
`merchandising/staff_access` view/create/revoke grants over every covered branch.
`/api/staff/auth/code|qr` exchanges a bounded monthly code or independent QR for an
opaque12-hour session; only its SHA is stored. No admin JWT, pharmacist identity or
reward is inferred. Manual revoke terminates sessions, including already-consumed
codes, and repeated revoke preserves the first actor/audit. Source code hashes,
branch links and imported auth attempts remain immutable history.

Public `/api/staff/visits` operations validate that session, active organization,
branch/month scope and deadline on each request; mutation checks join the write
transaction before domain locks. Shared attempts carry staff_visit_id plus an
unverified display name and cannot carry PERSON/account/pharmacist/capability
identity. They reuse evidence/review validation, with no bonus path. A returned
subset cannot discard submitted siblings or use rejected photos as new evidence.
Staff sessions and native ephemeral login attempts must never be promoted from a
rehearsal database. `MERCH_STAFF_ACCESS_ENABLED` defaults to false.

## POSM write throttling (INK-150)

- `fulfillment_devices.last_seen_at` is written at most once a minute per device
  (`DeviceLastSeen.STALE_CONDITION` in the heartbeat and fulfillment authentication `UPDATE`);
  presence counts a device online for `app.posm.heartbeat-ttl-seconds` (`POSM_HEARTBEAT_TTL`, default 150 s).
- `pharmacists.standard_n_*` IIN and name observation is rewritten only when the result changes,
  otherwise its check time is refreshed at most once an hour.

## Notices to pharmacists at the till (POSM 1.0.99)

HQ writes a notice to pharmacists (25.11.2026, package `posm.messages`, tables `posm_messages`,
`posm_message_targets`, `posm_message_receipts`, V122). Rules: `PosmMessageRules` (pure, `PosmMessageRulesTest`).

- `POST /api/admin/posm-messages` `{title, text, audience: all|pharmacies|pharmacists, pharmacyIds?, pharmacistIds?,
  days?}` -> 201. SYSTEM_ADMIN and HQ_HEAD only (`@PreAuthorize`). Title up to 120 characters (one line), text up to
  1000 (paragraphs kept, control characters dropped), days 1-30 (7 by default). Only the list of the chosen audience
  is used; unknown pharmacy or pharmacist ids get 400 naming them. `recipientsTotal` = active cards in scope at
  sending (all active; active cards of the chosen pharmacies; chosen cards not blocked).
- `GET /api/admin/posm-messages?limit=` (default 50, at most 200), newest first, `no-store`: each notice with
  `targetCount`, the first five `targetNames`, `shownCount` (people who saw it on a till), `closedCount` (closed it
  with ×), `expiresAt`, `withdrawnAt/By` and `active`.
- `POST /api/admin/posm-messages/{id}/withdraw`: tills stop showing it; repeated calls keep the first time; 404 for
  an unknown id.
- `POST /api/posm/self/messages` (device key, body as `/api/posm/self`): the person signed in to Standard-N at the
  till (same resolution and errors as the tasks feed, `PosmSelfPersonResolver`) gets every notice of the last 30
  days for them, newest first, at most 20 but every open one included: `{messages: [{id, title, text, sentAt,
  closedAt, pending}], pendingCount}`. Withdrawn notices are left out; an unclosed one past its last day stays in the
  history with `pending=false`.
- `POST /api/posm/self/messages/{id}/shown` and `.../closed` -> `{id, shownAt, closedAt}`: first report wins, repeats
  change nothing; a close also counts as shown. A notice not for this person gets 404; a withdrawn one gets 404 for
  `shown` but a close is still recorded.
- Who gets a notice: `all`, everyone; `pharmacies`, whoever signs in at a till of a chosen pharmacy and the staff
  of a chosen pharmacy (card's pharmacy) at any till; `pharmacists`, the chosen cards at any till.
- `GET /api/posm/notice` carries `messagesAt` (when a notice was last sent or withdrawn; cached 30 seconds, reset
  after the commit of a change). Tills from 1.0.99 fetch their person's notices at once when it changes; older tills
  ignore it.

`POST /api/posm/self/tasks` gains `requiredOpenCount`: required courses not finished (new, in progress or overdue;
paused ones not counted). The till keeps its red dot while it is above zero.

## Remote till commands

HQ manages tills without AnyDesk (25.11.2026, package `posm.commands`, table `till_commands`, V117). A command is
one action from a fixed list compiled into the till, `diagnostics` or `restart`, with no parameters. The till is
the authenticated device (per-device `X-Posm-Key`; the retired fleet key gets 401), never an id from the request.

- `POST /api/admin/tills/commands` `{pharmacyId, deviceId, action}` -> 201 command. The till must have an active key
  in an active pharmacy (404 otherwise); one command in work (`pending`/`delivered`) per till (409 otherwise).
  `requestedBy` is the admin e-mail.
- `GET /api/admin/tills/commands?pharmacyId=&deviceId=&limit=` (default 50, at most 500): newest first. A command
  past its deadline is shown `expired` at once, the row is marked by the next write or the hourly cleanup.
- `GET /api/admin/tills/commands/{id}/artifact`: the diagnostics zip, `Content-Disposition: attachment`, `no-store`.
- `POST /api/posm/commands/next` -> 200 `{id, action, expiresAt}` for the oldest waiting command of this till, now
  `delivered`; 204 when there is none. A waiting command lives 30 minutes; once taken, `expiresAt` becomes the
  answer deadline, 10 minutes later.
- `POST /api/posm/commands/{id}/result`, multipart: `status` `done|failed`, `message` (cut to 2000 characters),
  optional `file` (ZIP signature checked, up to 5 MB inclusive; larger gets 413, not a zip or an archive for
  `restart` gets 400). Another till's command gets 404; a command not taken, already answered or past its deadline
  gets 409. A rejected answer leaves the command `delivered`, so the till may retry before the deadline.
- Archives live in a private bucket of the same MinIO (`app.posm.commands.bucket`, `POSM_COMMANDS_BUCKET`, default
  `epharm-private`, created on the first upload), key `till-diagnostics/<commandId>/<random>.zip`. The media bucket
  `epharm-receipts` is anonymous-readable through `/s3` and is refused for this. `TillCommandCleanup`
  (`POSM_COMMANDS_CLEANUP_CRON`, hourly) expires elapsed commands and removes archives older than 14 days;
  `artifact_bytes` stays for history.
