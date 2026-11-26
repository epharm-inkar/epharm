# POSM Client

Path: `App/` and `Models/`.

## IIN and full name typed at the till: 1.0.101 (26.11.2026)

The owner's request of 26.11.2026 («давать им возможность самостоятельно заполнять свой ИИН»). 41 people who sell every
day have no IIN in the Standard-N directory, so the personal window answered «Не нашли вас в справочнике Стандарт-Н:
попросите МАС заполнить ИИН…» and ePharm never made them a card. Since 1.0.101 the window asks for the IIN and the
full name instead.

- The server answers `POST /api/posm/self` for such a user with the old message and the new code
  `STANDARDN_USER_UNKNOWN` (404). `EpharmApiClient` now reads the error `code` (`PosmSelfResult.Code`,
  `IdentityUnknown`). The till then asks `POST /api/posm/self/identity` where the user stands: `unknown` shows the form,
  `pending` says «Отправлено, офис проверит» with the masked IIN and the name, `rejected` shows «Офис не подтвердил:
  причина» and the form again; `known`, or no answer (an older server), leaves the plain message the window always had.
- The form (`StaffSelfWindow.BuildIdentity`): «Укажите свой ИИН и ФИО, чтобы ePharm начислял вам бонусы», the fields
  «ИИН (12 цифр)» and «Фамилия Имя Отчество», the button «Отправить». `Services/IdentityClaimRules.cs` checks the IIN
  (twelve ASCII digits and the Kazakhstan control digit; spaces and hyphens typed between digit groups are dropped) and
  the name (two to six words, letters, a hyphen or an apostrophe inside, each word at least two letters) before anything
  is sent; the server checks again and decides (`POST /api/posm/self/identity/claim`). Typed values survive the
  8-minute refresh of the window and are dropped when another Standard-N user is at the till. A send that fails shows
  the server's text and keeps what was typed. The IIN and the name never reach the till log.
- The icon by the clock keeps its red dot («ePharm: заполните профиль») while the person is unknown or refused, and
  has none once the claim is sent.
- «Отправить» makes a pending claim only: no card, no mapping, no bonus. The office confirms in «Фармацевты» → «ИИН
  указали сами»; from then on the user's receipts belong to the card and the window shows the profile as for everybody
  (phone, password, cabinet). The server side is in `docs/02-backend.md`, «Identity claims».
- Release order: server and admin first, then the till pilot. A server without the change answers with the old text,
  and a 1.0.101 till shows it.
- Tests (App.Tests, no window): `IdentityClaimRulesTests` (the same vectors as the server's `IdentityClaimRulesTest`),
  `IdentityClaimDtosTests`. The window states are in `App.Ui.Tests/StaffSelfShowcase.cs` (`staff-self-9-iin-form`,
  `-10-iin-sent`, `-11-iin-rejected`); the Windows CI job renders them into the `staff-ui-proof` artifact. The window
  was not run on a real till: that is the pilot's check.

## Renamed cashier programs and manager-only computers: 1.0.100 (26.11.2026)

Measured on production on 26.11.2026: 68 of 506 online tills reported the staff source status `no_process`, so ePharm
could not tell who sells there and the pharmacists saw «Войдите в Стандарт-Н под своим пользователем…» while signed in.
The process lists of 13 such tills show two causes:

- **A renamed cashier program.** At Teriskey 2/21 the Standard-N cashier runs as `zkassa_BACKUP_2_6_88`; Standard-N still
  records the session program as `zkassa` (53 receipts since 25.11). `ObserveStaffProcess` asked `GetProcessesByName`
  for the exact names `zkassa` and `kassir` and never found it. Since 1.0.100 the probe reads the processes of the same
  Windows session and `StaffProcessPresence.IsCashierProcessName` accepts, case-insensitively, `kassir` and any name that
  starts with `zkassa`. The rules stay: one cashier program resolves, several are `ambiguous_process`, an unreadable one
  is `process_unavailable`. The receipt reader's «when did the cashier program start» (`CashierProcessStartedUtc`) uses
  the same probe. Comparisons of the Standard-N session `PROG` value (`zkassa`, `kassir`) are database values, not
  process names, and did not change.
- **ePharm on the manager's computer.** On 10 of the 13 computers no cashier program runs, only `ManagerXP2` (the
  Standard-N back office); all receipts since 25.11 come from the program `zkassa`, none from `ManagerXP2`. With no
  cashier program and a process whose name starts with `ManagerXP` in the same session the heartbeat reports the new
  `staffSourceStatus` `manager_only` instead of `no_process`. A cashier program always wins over the manager program.
  The server allowlist (`StaffSourceStatus.kt`) and `StaffAuthDiagnostics.NormalizeSource` accept it; «Все кассы» shows
  the chip «только «Менеджер»» (amber, like `no_process`) and the tooltip «Только «Менеджер» Стандарт-Н: ePharm стоит
  не на кассе». `staffAuthStatus` stays `local_disabled`, so its allowlists did not change.

Release order: the server and admin first, then the till pilot. A server without this change drops `manager_only` and
shows «нет диагностики» for that till. Tests: `StaffProcessPresenceTests` (names, renamed, case, manager-only,
ambiguous), `StaffProcessProbeWindowsTests` (real processes under the till's names; Windows only, skipped elsewhere),
`StaffAuthDiagnosticsTests`, backend `StaffSourceStatusTest`, admin `staffSourceStatus.test.ts` and `fleetFormat.test.ts`.

## Greeting, «Что такое ePharm», notices from HQ and the course dot: 1.0.99 (25.11.2026)

The owner's requests of 25.11.2026, built for the pilot (not released):

- **Greeting** (`MainWindow.Welcome.cs`, `EpharmGreetingWindow`, rules in `Services/PharmacistGreeting.cs`). At the
  first sign-in of a person's shift (the till sees who is signed in to Standard-N at every 15-minute check; 8 hours
  without them start a new shift, so a night shift 20:00-08:00 is greeted once and a colleague signing in between
  does not make the first person greeted again), a card in the lower right corner says «Доброе утро, Имя Отчество! Желаем хорошего дня» (утро 05-12,
  день 12-18, вечер 18-23 with «Желаем хорошего вечера», night «Здравствуйте» with «Желаем спокойной смены»), two
  lines on how ePharm helps (no numbers, no money) and the buttons «Что такое ePharm» and «Закрыть». It never takes
  the focus (`WS_EX_NOACTIVATE`), closes by itself after 45 seconds (the countdown waits under the mouse), waits while
  a recommendation popup is open and stays silent during the works. Once per person per shift, also across restarts
  (`%LOCALAPPDATA%\Epharm\greeting-state.json` keeps when each person was last seen; the 1.0.96 `welcome-state.json`
  still counts for its day). «Имя
  Отчество» is everything after the surname of the Standard-N «Фамилия Имя Отчество»; ALL-CAPS names get capitals.
- **«Что такое ePharm»** (`EpharmPageWindow`, texts in `Services/EpharmHelpContent.cs`) replaces the small «?»: a
  clear entry under the header of the personal window, an item in the icon menu and the greeting's button open a
  page with five short sections in big type: what ePharm is, how to use it at the till, «Мой кабинет» and courses,
  how a pharmacist earns (only what the product does: the amount in the tip, sales by the tip in the same receipt,
  course bonuses named in the task, monthly payout by statement) and where to ask (the pharmacy network manager).
- **Notices from HQ** (`MainWindow.Messages.cs`, `EpharmMessageWindow`, state in `Services/PharmacistMessageInbox.cs`).
  The till asks `POST /api/posm/self/messages` for the person at the till with the 15-minute status check, on a
  cashier change, when the personal window opens, and at once when the minute check of `GET /api/posm/notice` brings
  a newer `messagesAt` (HQ sent or withdrew a notice). An open notice stays in the lower right corner until the
  person closes it with × or «Понятно, закрыть»; several come one after another («1 из 3»). It never takes the
  focus, waits for the greeting, hides while a recommendation popup is open and during the works. A card whose
  notice HQ withdrew, whose last day passed or which the person closed on another till leaves the screen at the next
  check, without a report, and the next open notice takes its place. The close is kept
  on disk at once (`messages-state.json`, at most 500 entries, 45 days), so a lost report or a restart never brings a
  closed notice back; `.../{id}/shown` and `.../{id}/closed` are resent until the server answers (400, 403, 404 and
  409 end the retries). The personal window has a «Уведомления» row (new ones in red) and the icon menu an item;
  both open the history page (last 30 days, newest first, open ones marked «Новое»).
  From 26.11.2026 the server also sends such a notice to the people who have not finished a course and to the people
  who sit at a till at that moment, and HQ sees who is at a till now (`docs/02-backend.md`, «Notices to people who have
  not finished a course»). The till changes nothing: the check it already sends names the person, the server records
  who sits at it, and a course notice stops reaching a person who finishes the course (at the next check the card
  leaves the screen like a withdrawn one).
- **Red dot for training.** `POST /api/posm/self/tasks` now carries `requiredOpenCount` (required courses not
  finished; paused by HQ not counted). The icon keeps its red dot while it is above zero, as well as for an unclosed
  notice; the dot goes with the next check after the course is completed (15 minutes at most, at once when the
  personal window opens). A server without the count falls back to the list. The «МОИ ЗАДАНИЯ» heading gets the same
  dot, the tooltip says «курсов: N» and «уведомлений: N» (`Services/EpharmAttention.cs`).

Tests: `PharmacistGreetingTests`, `PharmacistMessageInboxTests`, `EpharmAttentionTests`, `EpharmHelpContentTests`
(App.Tests, no window). Offscreen renders of the new windows for the owner: `e2e-out/posm-1099` on the laptop.

## The till has no sign-in window — 1.0.97 (25.11.2026)

The owner's decision of 25.11.2026: a pharmacist never signs in at the till. POSM 1.0.97 removes the old
«Вход / регистрация сотрудника» window (`StaffAuthWindow`), its tray icon, the employee grant channel
(`StaffAuthClient`, `/api/posm/staff/*` calls from the till), the process-scoped session state and the
capability polling. What a pharmacist has instead: the Standard-N user identifies the seller on the receipt,
«Мой ePharm» in the tray sets or changes the cabinet password, and the cabinet accepts phone or IIN with that
password. What stays in `App/MainWindow.Staff.cs`: every five minutes the till reads whether the Standard-N
cashier session resolves (`staffSourceStatus` in the heartbeat, the «Стандарт-Н» column of «Все кассы»), and
the receipt reader asks when the running cashier program started. `staffAuthStatus` is always
`local_disabled`; the server still accepts the old codes from tills that have not updated. The sections below
about RPS sign-in, registration and grants describe code that no longer exists and are kept as history.

Ordinary receipt attribution candidate (2026-11-16): `sellerSource` travels with the
queued sale independently of reward enrollment. Only a mutually consistent observed
receipt group, native user/session/workstation and database namespace can produce it;
current logged-in ePharm identity is never substituted. Missing legacy provenance
leaves the field absent and the legacy receipt deliverable. Proven disagreement between
the saved group, observed snapshot and actual completion database instead stops receipt
finalization before enqueue, ACK, empty-document resolution or pending retirement.
A fresh observation from a conflicting database cannot overwrite the original group;
its unresolved observations remain available for diagnosis and an original-source retry.
The durable group-key format is unchanged: a colliding group from another database is
blocked rather than silently reassigned.
Receipt probes isolate known database domains, retaining failed groups while allowing
healthy current-source receipts to proceed. Successful partition reads still share one
print-cue ambiguity check. An unreadable candidate cannot be discarded unless its known
namespace provably differs from every successfully read completion for that cue.
Missing provenance is not separation; an already anchored cue cannot be reassigned.
The short-lived suppression of an already finalized receipt also checks its native
group/session/workstation and known database namespace, not just DOCS.ID. A proven
new source or native session may reuse a numeric document ID without disappearing
from the display; a repeated observation of the original receipt stays suppressed.
The legacy sale ID remains `pharmacyId + DOCS.ID`. If both saved and new native
seller proofs are known but differ, completion fails closed: the new group stays
visible/unresolved and the original pending payload, outbox and delivery ACK remain
unchanged. Automatic delivery of two distinct receipts with that same legacy ID
across databases or sessions is not supported by this fix; it needs a coordinated
identity/replay migration. Missing legacy provenance is not proof of a collision
or permission to invent a new identity. This guard does not migrate historical IDs.
Name lookup is source/user/session-scoped
and expires (30 seconds for missing names, five minutes for found names); action-log
lookup requires both SESSION_ID and USER_ID and never falls back to another session.
Missing exact action names may use the matching user-directory entry, not a historical
action or another session's cached name. These labels remain non-financial evidence.
Heartbeat additionally reports fixed-code `staffSourceStatus`, even when RPS login is
successful, so absent/ambiguous/stale native context is visible without exporting PII.

Current client is C#/WPF/.NET 10. Older Electron references are historical and no longer describe the
implementation.

## Cashier-driven RPS presentation — 2026-11-16

Automatic sign-in presentation is independent of the security grant lifecycle. POSM
opens one ordinary, closable form for the first reliably resolved Standard-N USER,
then for a different confirmed USER (including A → B → A). A conflicting known
source database namespace also establishes a different identity domain. Changes to
native SESSION, workstation, process PID/set, IIN/name diagnostics, grant expiry,
401/403, transport recovery or capability polling do not re-arm automatic presentation.
The last confirmed presentation identity survives temporary unknown observations.
An unknown native user never proves a switch: cashier operation continues and the
tray provides explicit sign-in/registration. Restarting POSM starts a new observation
lifetime; this is not persistent employee authentication across app restarts.

A same-user technical context change retains the open form and its in-memory fields.
Only a new explicit submission refreshes its process/source context; credentials are
never replayed. A confirmed different employee or confirmed process exit dismisses
and clears the old form. Strict authorization generations, stale-response rejection,
revocation, eight-hour server expiry and the bounded two-minute unverified recovery
remain unchanged. Suppressing a popup never authorizes an expired grant or a bonus.
New-employee registration stays SMS-free. Backend, data, `/learn` and the public
domain are unchanged. Tests/release evidence are recorded in the current PLAN entry.

## Post-release hardening candidate — 2026-11-15

The new-employee registration form contains no SMS wording, request button or code
field, including RU/KK explanatory text, unavailable-policy status and unexpected
legacy OTP errors. Login never directs the user to an invisible code field either.
Phone status remains explicitly unverified. Existing-profile activation and password
recovery retain their separate proof; this change does not remove it from `/learn`.

When Standard-N reuses a native line ID for another product, the displayed product
ID is replaced and recommendation lookup is refreshed. A missing new EAN/name cannot
inherit the previous product's identity. Reward evidence still fails closed on an
uncertain line transition. Unsupported future outbox kinds are retained on disk and
excluded before the delivery batch limit, so supported sales and shown acknowledgements
keep draining after a rollback; no unsupported event is acknowledged as delivered.

These are candidate changes, not a new signed POSM release or evidence of installation.

## Release status — 2026-11-15

Backend/frontend `v0.2.18` and signed POSM `1.0.87` contain the implementation below.
The production application switch and updater-manifest readback passed; deployment does
not prove installation on every offline cash desk. Actual recommendation-money flags remain
off pending a scoped pilot with an approved funded campaign. No historical campaign was
activated and no synthetic credit was written to production. See the
[release evidence](reports/recommendation-rewards-2026-11-15/production-release.json).
Earlier dated candidate notes record preparation history, not the current release status.

## Native reward observation corrections — 2026-11-15

Repeating an already recorded display returns its original evidence without observing
the UI cart again. An unavailable UI snapshot cannot fabricate removals, additions or
a new eligibility timer. A real native read failure, conflicting offer or changed source
identity still makes the checkout incomplete.

During posting, final `DOC_DETAIL` IDs must never replace live `DOC_DETAIL_ACTIVE`
observations. A validated posting hold preserves the last observed group while paper/PDF
printing finishes; only the real print cue plus independently checked final documents
can enqueue sales. Existing proven active-to-final aliases survive retries. A first
observation made after posting already began cannot establish a missing baseline.

These are candidate corrections discovered by the Windows acceptance test; the earlier
synthetic load result does not prove acceptance of the corrected native capture.

## Reward evidence activation candidate — 2026-11-14

A signed runtime update preserves pharmacy configuration and outbox. Missing local
`RecommendationRewardsEnabled` / `NativeRefundCaptureEnabled` fields are server-managed;
explicit `false` remains an opt-out. Explicit `true` never bypasses the server.
Only a successful authenticated heartbeat with known `rewardEvidenceProtocol=1`
admits capture. An old, disabled, malformed or unknown capability cannot enable it.
Loss of capability invalidates the current reward baseline; later new checkouts
start from fresh observations. Ordinary checkout, recommendations, RPS, QR and the
customer screen continue. Once strict native print classification was admitted,
it stays strict for ordinary queued receipts even while financial capture is off.

The server independently limits the pilot by individual device ID and checks the
same scope before money. Global auto-credit and activation cutoff remain separate.
Refund observation resumes its durable cursor, without historical import or debit.
Exact alternate EANs supplied for the same recommended product may link additions;
an unknown EAN is never rescued by guessing a product from its name or local SKU.
The first shown baseline and alias set remain immutable. Whole-package semantics
are required for the rewarded product; an unrelated exact fractional line does not
itself invalidate the cart. Uncertain rewarded units remain a case for HQ.

This is candidate behavior, not evidence of completed Windows or fleet acceptance.

## Registration recovery candidate — 2026-11-14

New-employee registration remains permanently SMS-free. Existing-profile activation
is explicitly labelled as activation, not new registration; activation and password
recovery keep their existing SMS proof. Both distinctions are shown in RU and KK.

Account creation and acceptance of its device grant are separate operations. If a
valid authenticated registration response is followed by a rejected parent handoff,
the still-open form offers manual sign-in, not another registration. It retains the
IIN and password only in memory, clears password confirmation, and preserves the
parent's bilingual process diagnostic. No login or registration is automatically
replayed. Closing the form still clears secrets. Invalid responses must not be
presented as successful account creation.

An incomplete fresh Windows process check cannot authorize a grant even when one
process identity is readable. The UI explains that local verification failed;
confirmed process changes instead instruct reopening sign-in for the current
Standard-N process. Rejected grants are revoked as before. These changes do not
relax process proof, pharmacy assignment, pending-account status or OTP recovery.
Synthetic tests do not identify the cause on an uninspected pharmacy workstation.

An empty/unknown OS-process observation also suspends an already verified grant
while the observation loop waits for stable process proof. This includes a readable
process identity with an unreadable peer: partial enumeration cannot retain verified
authentication or open a prompt; a confirmed identity change still revokes immediately. The existing two-minute
recovery deadline is not extended by repeated uncertainty. Merely seeing the old
process again does not authenticate it: a fresh server check is required. The first
uncertain observation permits that check immediately when proof returns, rather
than waiting for the normal thirty-second validation interval. A stable process
exit still invalidates as before; no popup is opened without a real process.

## Nonblocking staff login candidate — 2026-11-11

Staff sign-in is an ordinary closable/minimizable window, including when Standard-N
provides an exact identity. It is never topmost, fullscreen or automatically activated.
Continue without sign-in remains available during validation and pending requests;
closing cancels the request, never grants authentication, and clears secrets on close.
Validation failures preserve the open form for an explicit retry.

Automatic presentation has a two-minute monotonic budget independent of process/session
generations. A suppressed generation is consumed, not queued for a delayed popup. The
tray always permits explicit reopening; a genuinely new session after the budget can
offer one ordinary window. Process/cashier changes still invalidate the grant immediately;
the throttle is not a debounce of authorization or a way to transfer a grant.

Successful background session checks re-read the OS process before restoring verified
state. A changed process invalidates/revokes the old token; an uncertain or timed-out
read retains only unverified proof under the existing bounded recovery deadline.
Native WPF and synthetic orchestration tests do not replace a real Standard-N pilot.

## Local post-#158 access-recovery candidate — 2026-11-10

A temporary capability/session-check transport failure is not a logout. The
unexpired process-bound grant is retained in memory for server revalidation only,
for at most two minutes from the first failure, never beyond its original expiry.
A monotonic clock also bounds recovery if the Windows wall clock moves backwards.
While validation is pending, `IsAuthenticated` is false: retained `Grant` alone
must never authorize a financial action. Retries do not extend the recovery deadline
or replay login, registration, password reset or SMS. A successful fresh check may
restore only the same generation/process/token/pharmacist, without extending expiry.
Session-check 429 respects Retry-After inside the same recovery limit; 401/403, expiry, server disable,
confirmed cashier/process change and explicit logout still invalidate the session.
Recovery expiry allows one ordinary closable login prompt rather than trapping
the cashier in a mandatory fullscreen window. Cash work remains independent.
Known HTTP denial remains terminal even when its optional error body is truncated
or unavailable; reading that body has the same bounded request deadline.

Successful login/registration handoff awaits fresh OS process confirmation for
at most two seconds. One uncertain read may be retried; a confirmed different
process rejects immediately. Dismissal, cancellation or generation change cannot
accept a late response. Rejected returned grants are revoked; form mutations are
never automatically replayed. The form stays busy until the parent finishes.

HQ distinguishes `session_revalidating` from `pending_approval`. Pending survives
closing the optional form or a failed capability poll and does not masquerade as
unavailable Standard-N identity.
Post-password pharmacy-assignment errors explain HQ verification in RU/KK without
suggesting duplicate registration. An oversized existing password points to verified
recovery, not shortening the submitted credential.

The portable suite links the real `MainWindow.Staff.cs` orchestration with fake OS,
dispatcher, window and HTTP boundaries. Native WPF tests separately exercise the
real form and awaited callback. Neither proves a live Standard-N/cash-desk pilot.

## Local post-#157 audit candidate — 2026-11-09

This section describes **unreleased local changes** based on main `4a6ebaf`
(server v0.2.13 / POSM 1.0.81). It is not a deployment or workstation acceptance record.
PR #156's IIN checksum and PR #157's permanently SMS-free registration are retained.

- Failed registration preserves identity, password and confirmation, including timeout,
  malformed responses and HTTP failures. Unknown success statuses cannot clear the form.
  A parent-accepted valid grant closes the window and clears secrets. Explicit navigation
  and dismissal also clear secrets; preserving a failed form does not persist it to disk.
- Login accepts only `authenticated` and `pending`. An empty/unknown status (including the
  registration-only `registered`) is an invalid service response, not a pending assignment;
  the required prompt releases cash work and retains input for a manual retry.
- Loss of comparable Standard-N identity releases an already-open required prompt without
  discarding input or treating a previous identity as current. Grant binding remains separate.
- Login password length/UTF-8 constraints are checked locally. Error text and parent status
  follow RU/KK. A server-supplied retry deadline is displayed; an exhausted reset OTP asks for
  a new code instead of claiming that waiting will revive it. Reset/activation still use OTP.
- Newly raised form errors scroll into view after layout, without focusing the message or
  activating the window. Timer ticks do not repeatedly scroll the user's form.
- Recommendation refresh reconciles visible rows with the current authoritative response;
  failed/empty refreshes cannot leave stale actionable rows. Partial cart removal drops only
  matching trigger rows. All four matching triggers remain supported. A 60-second display
  lifetime is not extended by partial cart updates, and manually dismissed rows do not reopen
  on an ordinary refresh. Stock uncertainty is labelled; the original financial quote is not
  silently repriced when balance/availability changes.
- Actual newly displayed rows enter a bounded memory queue and a background journal/outbox
  writer, rather than doing SQLite I/O on the WPF dispatcher. Queue capacity is 4096 and normal
  shutdown drain is bounded to two seconds. Memory-only entries can still be lost on power loss.
- The updater keeps signature/hash validation, adds whole-download/idle deadlines and range
  validation, and prepares a hashed candidate/backup before writing live files. A durable
  transaction journal and token/PID/start-time-bound startup acknowledgement support rollback
  after failed startup. This does not prove physical power-loss recovery or old-client handover.

Native evidence comes from `App.Ui.Tests`: actual WPF windows with fake HTTP, not the full
POSM startup or live Standard-N. Updater procedures and the read-only, redacted workstation
collector are documented in `docs/rollout/posm-update-recovery.md`. The repeat-audit results
and unresolved production gates are recorded at the top of `PLAN.md`.

## Responsibilities

The POSM client runs on a Windows cash-desk machine:

1. Polls the authoritative active Standard-N Firebird receipt (`DOCS` + `DOC_DETAIL_ACTIVE`) for the
   local `zkassa` workstation session. It auto-reads server/path/login from the cashier `options.ini`.
2. Tails detailed cp1251 `zkassa.log` events as a compatibility fallback for older Standard-N builds.
3. Sends cart data to `POST /api/posm/recommend`.
4. Shows up to five replacement and five cross-sell recommendations to the pharmacist in one compact,
   mouse-scrollable popup. Prices are formatted in Kazakhstan tenge (`₸`).
5. Sends accepted/rejected outcomes.
6. Reports printed sales to `POST /api/posm/sales`.
7. Mirrors receipt and broadcast media on the customer display.
8. Polls the effective active playlist and app version. The backend resolves the default or
   pharmacy-targeted profile. Successful playlist responses remove revoked URLs from playback,
   including the last video, and persist the empty manifest so cached files cannot return after
   restart. Transport failures retain the last allowed cache. The final-empty correction requires
   a POSM binary containing it; older deployed clients keep their previous empty behavior.
9. Sends heartbeat with the current Windows monitor count so admin can count online cash desks and
   report whether a customer display is physically available.
10. Persists an atomic active-receipt draft and accepts only an exact fiscal PDF/PNG produced by an
    approved KKM/OFD adapter; POSM never reconstructs a fiscal-looking receipt.
11. Stores outgoing non-real-time events in a local SQLite outbox and retries safely.
12. Sends any cashier id/name found in Standard-N as an audit signal; the backend decides the trusted
    internal pharmacist used for bonuses.
13. POSM 1.0.77 offers employee sign-in/registration/phone-reset when a real local Standard-N
    process is detected and local/server gates allow it. Own employee login does not require a
    readable Standard-N DB; source identity is checked separately. Fail-open preserves cash work.
14. Shows and processes only non-demo pickup orders with cash payment. Missing delivery or payment
    fields fail closed. Cached orders are rechecked on startup; server-removed cards close after
    the next successful poll. Recommendations remain on a separate path.

Order fulfillment now defaults off locally and in generated pharmacy packages. Explicit local
opt-in still requires authenticated `/api/posm/fulfillment/capabilities` permission before cache or
tray access and on each poll. Disabled/missing capability closes its windows without deleting local
orders or the individual credential. A prior authorized queue can remain read-only during a network
failure; unknown startup permission never exposes it.

Production `FULFILLMENT_POSM_ENABLED=false` rejects order registration/list/card/actions even for
older clients, while preserving storefront ingest/feed and independent recommendation/QR/playlist/
heartbeat authentication. Older binaries may retain their cached window until updated; server
denial is not proof that every historical window has closed. See [the contract](19-order-fulfillment.md).

The pharmacist recommendation popup is a non-activating, right-anchored panel so the Standard-N
scanner keeps focus. It shows up to five replacements and five cross-sell offers in ordered,
mouse-wheel-scrollable sections. The reward is read first: the dark ePharm header states the
largest single-offer reward (`Ваш бонус до +200 ₸`, or `Предложите покупателю` when nothing pays),
and each paid offer carries a large green `+N ₸` badge above its price. Only an offer with a positive
individual reward is tinted green; a zero-reward offer stays white and says `без бонуса`. The
backend's personal goal (`goalText`, `goalBonus`) is shown under the script when present. The
footer states the auto-close delay. Visual changes are compared on the same realistic scenarios:
`App.Ui.Tests -- --recommendation-showcase --output=<dir>` renders the real window to PNG, and the
regular UI regression run renders them into its proof artifact. Closing the popup does not accept or reject a recommendation, and the panel does
not promise an Esc shortcut because it cannot take keyboard focus from Standard-N.

The legacy optional sale-bonus bridge does not invent an `accepted` action. With
`POSM_SALE_BONUS_ENABLED=true` and an explicit UTC activation cutoff, only a real display ACK
and a matching `standardn-print-log` sale may create one source-bound bonus reservation per
sale/product. Both must identify the same pharmacy, session and trusted pharmacist. Exact,
unambiguous barcode/iPartID proof is required; a name match, database disappearance or
unverified cashier name cannot reserve money. Evidence more than five minutes in the future
or outside the 30-minute show-to-sale window fails closed. Historical events are not backfilled.
The resulting receipt goes to HQ moderation, never automatic credit or Excel heuristic approval.
After approval the bonus uses the same ePharm wallet as training and verified merchandising.
The bridge defaults to disabled; blank cashier identity does not prevent recommendation display.

### Recommendation reward evidence v1

The new optional evidence chain is separate from the legacy 30-minute show-to-sale moderation
bridge. It records the **first actual visible show**, not the recommendation HTTP response or a
click on the read-only popup. Its baseline and timestamp are immutable for that `eventId`;
reopening the same offer cannot reset the timer or multiply rewards. Delayed delivery of the
shown outbox item does not move the display time forward.

A recommended product must be observed being added **after** that show, in the same logical
`CheckoutSession.SessionId` (since 24.11.2026 there is no 120-second cutoff: the server policy
`RecommendationRewardEvidencePolicy(additionWindow = null)` accepts the whole checkout). An ordered addition in the same
clock tick is allowed. A product already present in the shown baseline is excluded from automatic
credit. The client preserves all observed quantity transitions, including decreases and late
increases; removing/readding a native line gives it a new local generation. Eligibility is limited
to the remaining, actually sold full packages added in time. Late increases do not earn a reward,
and decreases conservatively consume eligible quantity first. Multiple shows cannot reward the
same sold units repeatedly.

Payment can occur later than the addition. It still requires the existing
anchored print cue **and** `IsPostedPhysicalSale`: a freshly verified posted physical document,
signed final details and fiscal device/number/shift. The logical checkout may contain linked
documents, but each addition must match the physical document and native line of its completed
sale. A print command, cart disappearance or UI-only line is not payment evidence; unproven
line-ID migration cannot justify an automatic reward. Source identities at show/addition/completion must agree; the beneficiary comes
from an active explicit HQ mapping or a source-bound RPS proof for that native user.
After successful password login, exact IIN establishes the proof; only missing/unavailable
source IIN permits an exact unique full-name match. The device, database namespace, USER,
native SESSION and workstation must match and the grant must have been valid at payment.
A body-supplied internal ID, fuzzy name or later login cannot authorize money. Known IIN
mismatch, ambiguous names and conflicting/revoked mappings require HQ review.

`App/Services/RecommendationRewardJournal.cs` stores a private atomic, checksummed observation
journal, bounded to 32 checkouts / 8 MiB and 2,048 changes per checkout. Optional `rewardEvidence`
travels on the existing shown and sale DTOs/outbox; there is no new addition HTTP request. Sale
evidence is frozen before queueing. The local journal retires only after the group's nonempty
sales have been durably queued and its other physical documents have been resolved; the offline
outbox retains the sale evidence.
Read gaps, corrupt/lost state, a restart interval, ambiguous log-only removal or exhausted bounds
lower completeness and never block the cash desk. Source namespaces are 64-character SHA-256
digests of the successful database source; raw paths, passwords and RPS tokens are not sent.

The backend independently verifies the evidence, canonical physical/fiscal receipt and product,
recipient, remaining quantity, immutable offer terms and budget. Canonical sale/product
deduplication and the existing wallet credit path prevent repeated delivery or different sale
aliases from creating another credit. Complete eligible evidence may credit the common ePharm
wallet automatically; uncertainty is routed to HQ. A refund is an HQ review/hold case, including
after credit, and never automatically debits the pharmacist.

The implementation is shipped, but production monetary activation remains separate. Missing local
`RecommendationRewardsEnabled` / `NativeRefundCaptureEnabled` settings are server-managed;
explicit `false` opts out, while explicit `true` cannot bypass authenticated server capability.
Server `POSM_SALE_BONUS_ENABLED` and `POSM_SALE_BONUS_AUTO_CREDIT_ENABLED` also default to `false`;
automatic credit additionally requires an explicit `POSM_SALE_BONUS_ELIGIBLE_AFTER` cutoff.
Legacy DTOs, recommendation display, receipts, customer screen, QR, RPS and separate `/learn`
continue independently.

The controlled Standard-N DEMO whole-pack sale confirms `HUMAN_QUANT="1"`, `QUANT=-1`
and the whole-pack price. Posting preserves `HUMAN_QUANT`; `KOEF` is an inventory participation
flag, not a pack coefficient. `StandardNQuantityUnits` accepts only a positive integer native
human quantity agreeing with the absolute raw quantity. Fractions, absent/malformed human
quantities and mismatches remain `unknown`; quantities/prices are never rounded or converted.
Older schemas without the optional posted human-quantity column retain ordinary sale capture.

Standard-N allocates a new posted `DOC_DETAIL.ID`. The real posted ID stays unchanged;
`observedSourceLineId` is an optional, one-to-one link to the original ACTIVE ID, permitted only
for a unique same-document signature of part, quantity, price, amount, discount and human quantity.
Ambiguity cannot create money. Durable retry preserves the original link, including after a
failed finalization and a snapshot already containing posted rows.

`NativeRefundCaptureEnabled` / `EPHARM_NATIVE_REFUND_CAPTURE_ENABLED` defaults to `false`.
Its separate read-only observer accepts posted cash return type **9**, positive native monetary
signs, final details and fiscal identity. The UI-proven original sale is `DOCS.PARENT_ID`, never
the return line's `PARENT_ID`. It emits `standardn-db-refund` / `standardn-posted-refund`, not a
fabricated sale print cue. Original fiscal identity includes the original device number.
Missing or conflicting original evidence goes to HQ without reserving an unrelated sale.
Returns stay in the raw journal but are excluded from paid-sales/GMV/marketing attribution.

The private refund cursor v2 keeps a fixed activation database time and rotating bounded scans:
generator IDs are not commit order. It retains up to 256 pending and 16,384 completed return IDs;
enqueue precedes checkpoint, replay is idempotent, and a full bound stops observation with a
diagnostic before dropping an unretained return. Cash work continues. Historical postings before
activation are not imported. An unreleased local v1 cursor is preserved/rejected, not silently reset.
Deleting the highest unpaid draft may legitimately reduce live `MAX(DOCS.ID)`. This alone does
not disable the observer or rewind its durable high watermark; cutoff and source-identity
checks still apply. A maximum-ID decrease is not treated as proof of a database restore.
The generic print guard cannot relabel an old held sale as a return or suppress a proven paid
sale merely because an unrelated return draft remains open.
A per-log bounded BeforeQueue/Destroy-ID/AfterQueue tracker permits a delayed paper dialog only
for exact IDs from that completed print batch intersected with durable observed, not-yet-queued
documents. The native classifier also reads unknown batch IDs to retain a mixed-return veto;
those IDs cannot authorize a sale. Legacy/docless classification keeps its five-second POSTDT
window, and the bounded five-second probe after the cue plus paid/source/session/fiscal checks
remain unchanged. Rotation, interruption and overflow cannot enable the exact-ID exception.

A server-observed RPS mismatch remains an HQ veto. V102 separately records source-bound
positive proof and its audit, without rewriting the HQ mapping. SourceNamespace is sent only
from a fresh successful native read and an exact current cart/source; stale, failed or log-only
observations omit it. Changing a known namespace invalidates the native grant. Old clients
and old server responses retain their existing behavior; they cannot invent the new proof.

**Acceptance limits:** core/contract tests and a Windows-targeted WPF build do not constitute a
complete real Windows/pharmacy acceptance test. The full current-candidate pilot is tracked in
the root `PLAN.md`; production switches and the main branch remain unchanged.

Merchandising tasks are accepted only while both the task deadline (when present) and QR-token
expiration remain in the future. An already-open window hides its QR at expiry rather than waiting
for a new poll. Such a task cannot reopen through snooze or produce a new display acknowledgement.

## Important Files

| Path                                            | Role                                                               |
| ----------------------------------------------- | ------------------------------------------------------------------ |
| `App/MainWindow.xaml[.cs]`                      | WPF customer display and main integration shell.                   |
| `App/MainWindow.StandardNReceipt.cs`            | Live Standard-N receipt reconciliation loop.                       |
| `App/MainWindow.Recommendations.cs`             | Recommendation popup wiring.                                       |
| `App/MainWindow.Screen.cs`                      | Customer screen/video playlist logic.                              |
| `App/MainWindow.Update.cs`                      | App auto-update logic.                                             |
| `App/MainWindow.Staff.cs`                       | Standard-N source diagnostic and cashier start time (no sign-in).  |
| `App/MainWindow.Welcome.cs`                     | Greeting at the first Standard-N sign-in of the day (1.0.99).      |
| `App/MainWindow.Messages.cs`                    | Notices from HQ, «Что такое ePharm» and history pages (1.0.99).    |
| `App/EpharmGreetingWindow.cs`                   | Greeting card; `NoActivate` helper for windows without focus.      |
| `App/EpharmMessageWindow.cs`                    | One notice from HQ, stays until closed with ×.                     |
| `App/EpharmPageWindow.cs`                       | Readable page: «Что такое ePharm», history of notices.             |
| `App/Services/PharmacistMessageInbox.cs`        | Notice state per person, owed reports, date labels.                |
| `App/Services/StaffSessionObservation.cs`       | Exact-workstation source observation; strict legacy session guard. |
| `App/Services/StaffProcessPresence.cs`          | Cashier process by name (`kassir`, `zkassa*`); manager-only.       |
| `App/RecommendationWindow.xaml[.cs]`            | Pharmacist recommendation popup.                                   |
| `App/CdpForm.xaml[.cs]`                         | POSM customer-phone/CDP form.                                      |
| `App/Config/EpharmConfig.cs`                    | Config/env parsing.                                                |
| `App/Services/EpharmApiClient.cs`               | HTTP client with `X-Posm-Key`.                                     |
| `App/Services/CheckoutSession.cs`               | Current receipt/cart lifecycle.                                    |
| `App/Services/StandardNLogLocator.cs`           | Bounded production cash-log discovery/cache.                       |
| `App/Services/StandardNDbLookup.cs`             | Workstation-bound Firebird receipt/cashier reader.                 |
| `App/Services/SaleReporter.cs`                  | Printed sale reporting.                                            |
| `App/Services/ReceiptArtifactStore.cs`          | Atomic receipt draft/pending/recovery lifecycle.                   |
| `App/Services/FiscalReceiptInboxSource.cs`      | Exact KKM/OFD artifact validation boundary.                        |
| `App/Services/ReceiptSaleId.cs`                 | Stable pharmacy/document sale id.                                  |
| `App/Services/OfflineOutbox.cs`                 | SQLite outbox.                                                     |
| `App/Services/OutboxFlusher.cs`                 | Retry loop.                                                        |
| `Models/Posm/*`                                 | DTOs shared by POSM requests/responses.                            |

## Matching Contract

The matching contract uses three ordered identities. Exact identifiers are preferred; a product name
is only a last-resort fallback.

POSM sends:

- `barcode` - authoritative exact EAN/GTIN match against the catalog/Medusa barcode;
- `sku` - Standard-N local `iPartID`, sent only when barcode is unavailable and matched against the
  campaign product's `ipartId`;
- `name` - normalized exact-name fallback;
- `qty`, price/total data for sales.

Backend resolves barcode, then `iPartID` when barcode is unavailable, then normalized name. Ambiguous catalog keys are skipped
instead of selecting an arbitrary product.

Name fallback preserves dosage, pack size, concentration and explicit form distinctions on the
backend; see [POSM Matching](02-backend.md#posm-matching). Abbreviations do not bypass these guards.

ACC group/subgroup/INN triggers are resolved on the backend against the active, immutable ACC
classification snapshot. Unlike a legacy exact-product rule, they require an exact barcode present
in that snapshot; the backend does not infer an ACC class from a local Standard-N iPartID or a
similar name. A barcode belonging to several ACC `WARE_ID`s is ambiguous and cannot activate a
broad trigger. The POSM client continues to send the same cart request and receives the same
recommendation DTO: `triggerBarcode`, `triggerIpartId` and `triggerName` identify the real scanned
cart line, never the group label. Existing clients need no binary update for this feature.

Optional campaign AND factors narrow a product/group/subgroup/INN trigger on the server. All factors
are checked against the same exact, unambiguous cart-line barcode. Missing classification, a
mismatching factor or factors split across two lines cannot trigger it. Extra factors do not alter
the installed client's recommendation request, popup or shown/sale attribution wire contract.

`ExtractBarcode` supports:

- explicit `barcode=...` or `ean=...`;
- values in `iPartID=<id>(<EAN>)` when the value is 8/12/13/14 digits and differs from the internal id.

If the log does not contain EAN, POSM performs a read-only lookup in local Standard-N. It first tries
`VW_WAREBASE_KASSA`, then `PARTS.ID/BARCODE/BARCODE1/ORIG_BCODE_IZG` with a release-stable fallback
when the optional manufacturer-barcode column is absent. A missing view or a
schema difference does not abort the fallback chain. A non-cancelled recommendation response belongs
to the current cart snapshot and is trusted; POSM does not repeat backend matching with incompatible
catalog product ids.

## Config

### Staff identity release (POSM 1.0.77 candidate)

The client detects real `zkassa`/`kassir` processes in its Windows session and opens one
employee window per PID/start-time fingerprint, independently of a readable Firebird DB or
source IIN/name. Multiple known processes use a deterministic process-set fingerprint and
an unverified source; no Standard-N user, session or workstation ID is invented.
Own authentication requires the employee's IIN/password. Registration normally requires
SMS; the explicit temporary policy below changes only new RPS registration. Recovery
continues to require its existing genuine proof.
No process means no automatic window. The candidate requires the binary release gate,
local `StaffAuthenticationEnabled=true` and an enabled server capability with protocol 2,
`nameFallbackSupported=true` and `deviceEmployeeAuthVersion=1`. An old-server rollback
missing the device marker disables the whole new flow; credentials are never replayed via
legacy routes. Server enablement defaults to false and actual rollout is a separate ops action.

New `POST /api/posm/staff/device/login` and `/device/register` send
`deviceContext={processInstanceId,standardNContext?}`. `/device/session/check` sends that
context in the body and the grant in `X-Staff-Session`; `/device/logout` revokes it.
The backend's independently scoped device grant authenticates the ePharm employee, not the
owner of a Standard-N account. The current process/generation must accept a successful
login/registration response before the window closes. Process changes and a known source
user/session/workstation change revoke the old client grant and prompt again. Unreadable,
stale or ambiguous source intervals preserve the own employee login and last reliable source
binding. The first late reliable observation binds/checks the existing grant without a second
popup; IIN/name diagnostic updates do not transfer employee identity. A delayed response from
an earlier process, source-user generation or dismissed window cannot authorize the new one.
**An account switch inside the same process cannot be reliably detected while its source DB
is unreadable**; process detection is not an account-switch event interface.

The ordinary employee handover is to finish or cancel the current receipt, fully close
Standard-N's cashier application with its close button, then launch it again and sign in
to ePharm with the next employee's own IIN/password. No tray logout is required. A confirmed
process exit clears the local grant; a new PID/start-time fingerprint prompts even after a
fast restart or PID reuse. Minimizing the window or closing only a receipt is not an exit.
The observation loop needs time to detect the change; a temporary process-read failure
does not itself sign the employee out. Existing receipt/sale attribution stays unchanged.

The source observer reads `USERS.INN` only for the exact local `SESSIONS.USER_ID` in the
same read-only Firebird snapshot. Missing/invalid/unreadable/ambiguous values remain unknown.
When IIN is missing/unavailable, that snapshot may read the textual account label
`USERS.USERNAME` by the exact user ID, only when it contains a complete full name with at
least two full name words. Opaque binary `USERNAME_N`, arbitrary logins, roles, initials,
receipt history and cached guesses are not substitutes. Names never select an ePharm account.
The strict source-bound `POST /api/posm/staff/session/check`, legacy four-field GET validation
and existing protocol-2/name-fallback clients retain their prior wire/context contracts; their
core POSM traffic is unaffected by the additive device-auth routes.

Both contextual and contextless SMS registration on an individually authenticated, active
pharmacy device now create an **active profile assigned to that device's server-recorded
active pharmacy**, independently of the Standard-N DB. The new device route returns a real
process-bound grant and the current client closes after accepting it. The legacy contextless
route returns `status=registered` without a fabricated source context or staff grant; a later
supported login is still required. Existing historical pending/unassigned, blocked or differently
assigned profiles are not blanket-activated or silently moved. Individual remediation needs
its own verified evidence; merely requesting an OTP is not completed-registration provenance.
A copied/misassigned device key cannot establish the physical location from sales/address text.

### Initial registration without SMS (POSM 1.0.81)

The registration SMS-send button and code field are always hidden and disabled,
including initial/default, cached, legacy and unavailable capability states.
Only an explicit compatible `registrationSmsRequired=false` capability permits the
direct registration request. The form keeps full name, IIN, declared phone,
password and password confirmation, then calls `/api/posm/staff/device/register-without-sms`.
No placeholder/test code is sent; the phone is labelled unconfirmed. Successful
acceptance of the normal current process grant closes the window and preserves
automatic pharmacy assignment independently of the Standard-N database.

Before a valid capability or with an incompatible server, registration explains
its unavailability, preserves the fields and permits work without requesting SMS.
Only an enabled, compatible capability can change the known registration policy;
it never restores SMS controls. A failed background refresh does not change policy.
A transient background failure releases work without cancelling an independently
pending login/registration response. Explicit close, process/source-user change,
server disable and an actual registration-policy change still reject stale replies.
Local validation and HTTP errors retain both password fields in the existing
window. Passwords are cleared on a mode change, successful accepted authentication
and close; they are never written to logs, disk, the outbox or screenshots by the app.
HTTP 429 keeps the specific attempt-limit message and the entered form; it does not
claim a service outage, create a grant or automatically replay the mutation.
A policy change
invalidates a pending registration response without logging out an unrelated existing
employee or automatically replaying an account creation. Login, reset and activation
keep their previous endpoints and proofs. Fail-open, recommendations, customer display,
receipt/outbox and task QR paths stay independent.

The pharmacist registry exposes permission-filtered `MATCH`, `MISMATCH` or `UNKNOWN` and
check time. Source IIN comparison stays separate from general `IIN`/`NAME`/`NONE` method and
status. A known IIN mismatch is never rescued by a matching name. When IIN is missing or
unavailable, all normalized full name words must match the authenticated profile; a name
mismatch is labelled separately. Source names are not exposed in administrative DTOs.
Unknown source is not a mismatch or a fake match. These are device-reported diagnostics,
**not trusted seller/bonus binding, automatic POSM sale attribution or wallet credit**.

With a fresh exact source and comparable IIN/name, the existing cashier-monitor fullscreen
presentation can be used. Otherwise the automatic window is ordinary and closable, with
“Continue work without sign-in”; closing/continuing records a per-process-generation bypass
without an auth grant and without reopening on each source-status poll. Service/SMS failure
also releases work with a fixed bypass diagnostic. Manual tray reopening remains available.
No global keyboard hook, BlockInput, Standard-N window disable or process termination is used:
this is WPF presentation, not Windows Assigned Access or an OS security boundary.
Server disable/shutdown releases the window. Customer display, receipt/sale/recommendation,
QR and updater loops remain independent; the separate `/learn` and its domain are preserved.

`App.Ui.Tests/` defines 17 native WPF regressions against the real App assembly: the previous
ten plus seven process/device login, registration, bypass, source-flap and stale-reply cases.
They use fake HTTP without real SMS, accounts or device configuration. The candidate harness
has Windows-target compilation evidence; **17-case native execution and real-SMS/live-cashier
acceptance are separate evidence, not implied by compilation**. CI records boolean proof and
synthetic renders; its explicit noninteractive opt-in does not prove a physical cashier desktop.
This document does not assert publication or completed fleet delivery of the candidate.

Historically POSM 1.0.72–1.0.75 paused staff UI through the read-only binary release gate and
production server opt-out. JSON/env/server changes alone cannot override those old binaries;
they need the signed reviewed client update/restart. The 1.0.71-era form also disabled login
without source context; the new device flow removes that dependency, retaining guided
registration/recovery and identical guidance for unknown IIN versus wrong password. Passwords,
OTP and challenges clear on navigation; no account-existence endpoint is added.
Only allowlisted `staffAuthStatus` codes enter heartbeat; no IIN/name/phone/password/OTP/token
is included. `authenticated` means accepted own ePharm login, not Standard-N ownership or
financial trust. Human attention and source matching require separate evidence.

`posm.json` keys can be overridden by environment variables:

| Key                                    | Env                                        | Meaning                                                                                  |
| -------------------------------------- | ------------------------------------------ | ---------------------------------------------------------------------------------------- |
| `Enabled`                              | `EPHARM_POSM_ENABLED`                      | Enables backend integration.                                                             |
| `BackendBaseUrl`                       | `EPHARM_BACKEND_URL`                       | Preferred backend origin, e.g. `https://epharm.inkar.kz`.                                |
| `BackendFallbackBaseUrls`              | `EPHARM_BACKEND_FALLBACK_URLS`             | Ordered backup origins; environment values use `;` or `,` separators.                    |
| `DeviceKey`                            | `EPHARM_POSM_KEY`                          | POSM device key for `X-Posm-Key`.                                                        |
| `PharmacyId`                           | `EPHARM_PHARMACY_ID`                       | Pharmacy/screen id.                                                                      |
| `PharmacistId`                         | `EPHARM_PHARMACIST_ID`                     | Diagnostic fallback only; do not use a fixed person in production.                       |
| `ScreenMode`                           | `EPHARM_SCREEN_MODE`                       | `dev` windowed or `prod` monitor behavior.                                               |
| `VideoEnabled`                         | `EPHARM_NO_VIDEO=true` disables            | Customer video playback.                                                                 |
| `PlaylistPollSec`                      | `EPHARM_PLAYLIST_POLL_SEC`                 | Playlist poll period.                                                                    |
| `AppLogPath`                           | `EPHARM_APP_LOG`                           | POSM app log path.                                                                       |
| `StandardNLogPaths`                    | `EPHARM_STANDARDN_LOG_PATHS`               | Optional explicit paths (`;`-separated in env).                                          |
| `StandardNReceiptPollMs`               | `EPHARM_STANDARDN_RECEIPT_POLL_MS`         | Active receipt poll interval; default 400ms.                                             |
| `StaffAuthenticationEnabled`           | `EPHARM_STAFF_AUTH_ENABLED`                | Opt-out of the Standard-N session diagnostic (no sign-in at the till since 1.0.97).      |
| `StaffSessionMaxAgeSec`                | `EPHARM_STAFF_SESSION_MAX_AGE_SEC`         | Staff DB-heartbeat freshness policy, default 300s; verify actual cadence in pilot.       |
| `ReceiptCaptureEnabled`                | `EPHARM_RECEIPT_CAPTURE_ENABLED`           | Enables exact-only local capture; default true.                                          |
| `ReceiptCaptureDir`                    | `EPHARM_RECEIPT_CAPTURE_DIR`               | Root for active/pending/quarantine receipt artifacts.                                    |
| `FiscalReceiptInboxDir`                | `EPHARM_FISCAL_RECEIPT_INBOX_DIR`          | Handoff directory written by an approved KKM/OFD adapter.                                |
| `FiscalReceiptTrustedSources`          | `EPHARM_FISCAL_RECEIPT_TRUSTED_SOURCES`    | Allowed adapter identities.                                                              |
| `FiscalReceiptPollSec`                 | `EPHARM_FISCAL_RECEIPT_POLL_SEC`           | Background inbox poll period; 1-60 seconds.                                              |
| `FiscalReceiptMaxClockSkewSec`         | `EPHARM_FISCAL_RECEIPT_MAX_CLOCK_SKEW_SEC` | Maximum sale/document correlation skew.                                                  |
| `FiscalReceiptMaxArtifactMb`           | `EPHARM_FISCAL_RECEIPT_MAX_ARTIFACT_MB`    | Maximum accepted PDF/PNG size; 1-50 MB.                                                  |
| `ReceiptCaptureActiveRetentionDays`    | `EPHARM_RECEIPT_ACTIVE_RETENTION_DAYS`     | Retention for abandoned active drafts; 1-30 days.                                        |
| `FiscalReceiptCompletedRetentionHours` | `EPHARM_FISCAL_RECEIPT_RETENTION_HOURS`    | Exact-copy retention after capture; 1-168 hours.                                         |

POSM v1.0.43 uses the workstation-bound active Firebird receipt as the primary live-cart source. It
still watches explicit and previously confirmed paths first, then the two legacy v1.0.23 paths:
`C:\Standart-N\Kassir\zkassa.log` and
`C:\Standart-N_DEMO\Apteka_KZ DEMO\Kassir\zkassa.log`. In parallel it performs a bounded search near
running Standard-N/cashier processes and likely top-level installation folders. It searches only for
`zkassa.log`, avoids reparse points, never blocks the UI thread, and stores a path only after a real
cash-event marker is observed. The cache is `C:\Epharm\standardn-log-paths.txt`.

`Enabled` is effective only when key identity fields are present.

When staff source comparison is enabled, the read-only observer uses one local cashier
process, the exact canonical workstation and a fresh source session. It does not borrow the
receipt reader's sole-foreign-session or substring fallbacks. The query filters exact workstation,
cashier program and valid identity rows before its 101-row sentinel; it excludes only provably
expired native timestamps. Unsupported timestamp types stay explicitly unresolved, never cast
from counters/strings. Missing/future/inconsistent timestamps remain possible candidates; two
potentially current sessions are not resolved by choosing the newest. More than 100 eligible
rows refuses source context rather than hiding another session through truncation.
Own device authentication survives unknown/stale/ambiguous source data; grants stay in memory
and are revalidated every 30 seconds. The backend remembers its first reliable user/session/
workstation through missing-source intervals; a changed known binding revokes the grant.
Receipt seller attribution remains unchanged. Use the [Windows/SMS pilot checklist](rollout/rps-auth-test.md)
for separate native, actual Standard-N and real-SMS evidence.

## Exact Fiscal Receipt Contract

Every open Standard-N receipt gets an atomically replaced JSON draft under
`C:\Epharm\receipts\active`. The 1.0.73 candidate reads the complete `LINK_ID` cart in one
bounded read-only Firebird snapshot, for one exact local workstation and cashier session.
Unknown, foreign or ambiguous membership preserves the previous screen. A missing/zero link
retains the exact single-document path. Physical document and line identities remain separate,
including equal products with different prices or discounts.

An explicit Standard-N print-log cue starts a bounded five-second correlation, rather than
declaring the current cart paid. Finalization additionally needs the previously observed physical
document's original session/link, `STATUS=1`, device/shift/fiscal counters and fresh signed
`DOC_DETAIL` rows matching its header. Posted rows are not read from `DOC_DETAIL_ACTIVE`.
A late cue can therefore confirm cart A while cart B stays on screen. A held receipt, disappearance,
commit timestamp or status alone never proves payment. A strictly proven empty posted document
resolves locally without inventing a sale or a financial acknowledgment.

Unconfirmed groups stay in an atomic, versioned, checksummed journal (32 groups, 8 MiB bound).
Reaching a bound or encountering corruption does not evict unresolved evidence. Anchored print
times, original seller/session and per-document queue state survive restart. Database probes run
off the UI thread; stale/expired first results cannot create a sale or replace a newer cart.
A deterministic id based on `pharmacyId + DOCS.ID` deduplicates confirmed print and retry signals.
The exact fiscal original is captured independently of the structured sale.

The bounded hot journal must not fill indefinitely with cancelled/deleted unpaid sources.
The background reconciler reads the exact original Firebird source, historical session, user,
workstation, document family and both active/final details twice in distinct transactions at
least one native second apart. Only proven absence of every original member can free a slot;
an inactive/suspended UI cart, timeout, partial posting, print cue or queued sale cannot.
Absence is archived as `source_disappeared_unresolved`, never as payment/cancellation proof:
the immutable, checksummed private archives retain the complete original group, reward journal
and both readings. Permanent checkout/physical-identity tombstones prevent revival after restart
or source restoration. Archives are bounded at 4096 entries/64 MiB per store and never pruned
to manufacture eligibility. Missing legacy provenance is recovered only from the original durable
reward identity; otherwise evidence remains unresolved. Idle connectivity changes do not mark
an unused future checkout incomplete; a gap in an already observed checkout remains sticky.

POSM 1.0.91 keeps old groups from stopping capture (November 2026: tills stopped confirming
sales once one old group competed with every new print or 32 groups never resolved). A doc-less
print cue is offered only to the current receipt and to groups seen as the active receipt within
the last 10 minutes (groups loaded from disk count as seen at program start, so a print right
after a restart still finds its receipt); old groups are probed in a separate read, so an
unreadable one cannot hold back the receipt being printed. A group not seen for 6 hours (by the till's clock or by its
Standard-N open/post/print times), or the oldest non-live groups while more than 28 slots are
taken, is copied verbatim to the private `stale-observed-groups` folder and leaves the hot journal.
That move creates no sale, ACK or reward and touches no draft or outbox entry. If the same
document is observed again, a fresh group starts; sales stay deduplicated by `pharmacyId + DOCS.ID`.
The stale folder is a local diagnostic copy bounded at 2048 entries/64 MiB, oldest pruned first.

The candidate also withholds the two known unsafe legacy sources (`standardn-document-switch`
and `standardn-firebird-close`) from raw-sale replay, without deleting their rows or starving other
queued events. A later real print of the same physical document preserves the old pending files
and complete SQLite queue row before promoting the canonical entry. Verified fiscal originals keep
their separate recovery path. Previously delivered server sales are not silently rewritten.
An older client does not know these replay guards, so rollback to it requires a separate review.

The legacy sales wire stores prices/totals as whole KZT. A newly confirmed amount that cannot be
represented losslessly stays unresolved with its exact source values and a diagnostic, rather than
being silently rounded and acknowledged. Fractional quantities with representable monetary values
remain supported. Supporting fractional monetary values in reporting requires a separate wire and
backend extension. Existing public DTOs and already accepted historical sales are unchanged.

POSM never renders an image from cart rows. In the background it waits for an approved read-only
KKM/OFD adapter to publish an original PDF/PNG and atomic manifest into
`C:\Epharm\fiscal-inbox`. It validates pharmacy, Standard-N document id, total, time, required fiscal
fields, container boundaries and SHA-256, then copies the bytes without transformation. Backend
records the immutable hash and provenance in a separate idempotent enrichment request. A legacy
`artifactFormat=png` without this evidence is accepted as an old structured sale but ignored as a
fiscal artifact.

After the durable local copy passes a second SHA-256 check, POSM removes the dedicated inbox
manifest first and then its source handoff file. Failed handoff cleanup is retried from stored
metadata without deleting the accepted original.

The local exact copy is deleted only after backend acknowledgment of its fiscal metadata and the
full configured retention counted from that acknowledgment. A delivered sale for which no fiscal
source ever appears expires after the bounded source-wait window. Corrupt evidence is quarantined.
POSM does not call print, cancel, drawer, shift or fiscalization commands and never changes
Standard-N/KKM state. The real adapter and locked
Windows ACL are mandatory pilot prerequisites; the repository currently provides the validated
consumer boundary, not a universal adapter for unknown cash-register drivers. See
`docs/17-posm-exact-fiscal-receipt.md`.

## Pharmacist Attribution

Pharmacist attribution comes exclusively from the active Standard-N user on the workstation:

1. POSM reads the active Standard-N id and full name and captures them once for the receipt.
2. Backend first applies an explicit HQ rule `(pharmacy, external USER_ID) -> pharmacist`. The target
   must be active and assigned to that pharmacy. Only then can it use a valid internal id or exact
   unique full-name match within the pharmacy.
3. Any other Standard-N id/name is marked `standardn_unmapped`, stored verbatim, and shown in the
   dashboard instead of being discarded.
4. Missing identity is marked `unresolved`. Unmapped/unresolved sales do not enter automatic bonus
   reconciliation until the employee identity is mapped.

For Auezova 134, the real cashier evidence confirmed `KASSA2`, remote Firebird server `MANAGER`, and
an `options.ini` that points to `C:\Standart-N\base\ztrade.fdb`. POSM selects only a current `zkassa`
session whose `WORKSTATIONS.COMPNAME` matches the local computer, so a shared database cannot silently
attribute another cash desk's receipt or pharmacist. Other Standard-N releases remain fail-safe: a
schema/connection failure preserves the last known UI state and the log compatibility path continues.

POSM sends API requests to `BackendBaseUrl` first. On public-gateway `404/502/503/504` or a connection
failure it retries configured HTTPS fallbacks within a bounded attempt budget and probes the primary
again every five minutes. Origins must not include `/login`; the client adds `/api/posm/*`. Remote
HTTP origins are rejected; only loopback development may use HTTP.

## Screen Modes

- `dev`: windowed display for debugging.
- `prod`: with two monitors, customer display opens fullscreen on the second monitor and popup stays
  on the pharmacist/cashier screen; with one monitor, customer display is suppressed and recommendations
  can still work.

The pharmacist popup is informational and does not steal keyboard focus from Standard-N. It displays
the exact recommendation count returned by the backend, keeps its header fixed, and scrolls only the
offer list when the content exceeds the compact window height.

The window reasserts native `HWND_TOPMOST` without activation and verifies its actual styles,
rendered state, monitor bounds, non-minimized/non-cloaked state and sampled content exposure before
acknowledging `displayed_at`. A successful recommendation API response is not display evidence.
The visibility guard retries while the popup remains open and rate-limits failure diagnostics.
This does not override Windows UAC/secure desktops or prove that a human read the popup; final
cashier acceptance still requires a real scan above Standard-N while scanner focus stays there.

### Closing the customer receipt

An explicit Standard-N print marker clears an unbound log-only cart's display; it does not queue
a sale. A tracked linked cart clears only after all its physical documents are durably queued or
strictly resolved; a late print of another cart leaves the current one visible. The active
receipt poll is normally 400 ms; two consecutive successful empty reads from one unambiguous,
exact local workstation session also clear a log-only cart whose document ID was never discovered.
This is a confirmation interval, not an arbitrary idle timeout. Unknown/ambiguous sessions and
database/schema failures preserve the live cart and break that empty-read sequence.

A late database response started before a log add, deletion, discount or print cannot overwrite
the newer customer screen. A just-printed known document cannot repopulate it. Clearing an unbound
log-only cart discards its draft and recommendation windows; it does not invent a completed sale.
If a print arrives before any `DOCS.ID` was observed, nonempty database snapshots are quarantined
until two authoritative empty reads confirm the receipt boundary. New log scans still work but
cannot release this barrier: they do not identify the old database document. A DB-only cashier
that immediately opens another receipt without an observed idle boundary may temporarily miss
DB-driven updates until the next confirmed empty receipt; do not guess identity from time or names.
The candidate's posted-row contract was derived from the actual Standard-N demo fixture;
other vendor schemas remain unknown until separately verified. POSM never writes or closes the
cashier's real receipt. Unit/source proof does not replace acceptance of the exact Windows binary.

## Build

WPF builds only on Windows.

```powershell
cd <repo>\App
dotnet run

# release/self-contained
powershell -ExecutionPolicy Bypass -File scripts\publish-exe.ps1
```

The release package must include the exe, runtime dependencies, LibVLC files, `posm.json`, and `run.bat`.

## Pilot rollout (backend v0.2.25+)

A client release can go to a few tills before the fleet. `app_releases.channel` is `stable` (every till) or
`pilot` (V110); each channel has at most one current release per platform. The backend gives a till listed in
`POSM_UPDATE_PILOT_DEVICES` (`pharmacyId|deviceId` or `pharmacyId|*`, comma-separated; the retired fleet key never
qualifies) the newer of the current pilot and stable releases; every other till gets the stable release.

1. Set `POSM_UPDATE_PILOT_DEVICES` in `.env.prod` (backend restart) to one or two tills you can watch.
2. Register the signed package with `"channel": "pilot"` through `POST /api/admin/app-releases`.
3. Check the pilot tills: heartbeat version, application log, the sales they send.
4. Register the same signed package with `"channel": "stable"`: the fleet updates on its next check. A stale pilot
   release never holds a pilot till below the stable one, so the pilot needs no cleanup.

**The backend enforces the pilot** (owner's rule 24.11.2026: till releases are checked thoroughly before they go out,
whoever publishes them). `POST /api/admin/app-releases` with `"channel": "stable"` returns 409 unless the same build
(platform, version, sha256) was registered as pilot at least `POSM_STABLE_AFTER_PILOT_MINUTES` (default 60) minutes
ago, a pilot till is online with that version now (heartbeat `appVersion`) and a pilot till sent a receipt since the
pilot started (`pos_sales.device_id`, last day at most). Going back to a build that was stable before needs no pilot.
The refusal says which condition is missing.

## Deployment

For long-running cash desk installation:

- use `dotnet publish` or `publish-exe.ps1`, not `dotnet run`;
- install scheduled tasks with `App/scripts/install-tasks.ps1`;
- enable Windows autologin if the client must start after reboot without manual login;
- use the watchdog task and heartbeat file;
- publish app releases through `/api/admin/app-releases` for auto-update.

`setup-autostart.bat` copies the package to `C:\Epharm\app-<mode>\<version>`, compares key package
hashes before reusing an existing folder, performs a bounded handover from an old POSM process, and
reports success only after the expected executable path and a fresh UI heartbeat are verified.

POSM sends backend presence every 30 seconds. Backend considers a device online for 90 seconds and
persists last-seen/pharmacy mapping in Redis with an in-memory fail-safe. Presence is keyed by the
pair `pharmacyId + deviceId`, not by the Windows machine name alone: `KASSA1` can therefore exist
in multiple pharmacies without one live cash desk hiding another. The admin screen polls the
connected-device endpoint every 30 seconds. POSM v1.0.46 sends `monitorCount` and `appVersion` on
every pulse,
so connecting or disconnecting the second monitor is reflected without restarting or reinstalling
the client. During rolling update, older clients remain online and are exported as `Не определено`.
The Excel report includes a separate POSM version column so an unknown screen has an explicit rollout
cause instead of being mistaken for a confirmed one-monitor installation. v1.0.46 also caps update
polling at five minutes and resumes interrupted release downloads from a persistent partial file.

Production binary releases are mirrored in the public, artifact-only repository
`sabirovv17/epharm-posm-releases`. Keep application source and pharmacy configuration out of this
artifact-only channel; its visibility is independent of the source repository. Prefer an immutable,
version-and-source-commit-named archive under the existing first-party HTTPS `/downloads/` route
when pharmacy networks cannot reliably reach an external CDN. Install only the exact Windows CI
artifact into `/srv/epharm-downloads`, already mounted read-only by Caddy; do not rebuild or change
its bytes. jsDelivr pinned to an artifact-repository commit and the GitHub release asset remain
independent recovery sources. Changing the distribution URL requires a newly signed manifest even
when the version and archive SHA-256 are unchanged; it does not require restarting updated clients.
Release archives must never contain `posm.json`, device keys,
credentials, pharmacy identifiers, or source code. The current bridge archive contains only the
application executable, DLL, deps file, runtime config, and the QR dependency DLL. Before a release becomes current, verify
an anonymous HTTPS download, Range resume, ZIP integrity, exact byte size, and SHA-256 from the final
distribution URL, including the public ingress address as well as internal DNS. Register only a
manifest signed by the offline ECDSA P-256 key. `AppUpdater` verifies the
independently pinned SPKI over platform/version/URL/hash/mandatory before download and then verifies
the ZIP SHA-256. A public P-256 trust anchor is embedded in current clients so older pharmacy
configs without an explicit SPKI can still verify a signed update. A malformed nonblank override
fails closed. The installer rejects remote HTTP backend/fallback URLs before reporting success.
Pharmacy-specific `C:\Epharm\posm.json` is preserved during the overlay update.

Do not equate a published manifest with a completed fleet rollout. Compare fresh (`<=90s`)
heartbeats by version after at least two five-minute updater intervals. For remaining older clients,
collect the actual process path, updater config/environment, `C:\Epharm\update.log` and the POSM
log through approved workstation access. Historical installation ZIPs do not prove current settings.
There is no general remote-command/RMM execution endpoint; never disable manifest signature checks
to repair a workstation-specific download, permissions or configuration failure.

## Merchandising task QR

The 1.0.75 candidate aligns this window with the existing ePharm admin: the same receipt-stamp
logo (`admin-panel/frontend/src/layout/Logo.tsx`, mirrored as a WPF vector), embedded shared Manrope,
teal header, light workspace and neutral controls. Brand resources are local to this window. The QR
keeps its white backing and quiet zone. Task selection, offline/expiry handling, topmost/no-activation
behavior, shown acknowledgements and the ten-minute reminder retain their existing contracts.

Starting with v1.0.52, the same authenticated POSM channel can retrieve an active merchandising task
for the device's pharmacy and acknowledge a QR only when it is visible. Backend authentication binds
individual device tokens to both `pharmacyId` and `deviceId`; the server-to-server CRM credential is
never returned to the cash desk. The client accepts HTTPS task links (plus loopback HTTP in local
development), deduplicates acknowledgements by delivery token, and retries after transient failures.
Current clients also send `X-Device-Id`; legacy clients without the header remain compatible. The
backend treats merchandising as an optional dependency: timeout, invalid payload, or a 5xx response
returns HTTP 200 with `available=false` instead of a gateway error. POSM applies an isolated bounded
backoff (one, five, then fifteen minutes), marks only the task window offline, and keeps the
recommendation, heartbeat, and sales channel on its normal backend route.
The CRM does not issue an overdue assignment to a cash desk once its due time has passed; a stale
locally visible QR must not be treated as a new assignment.
From POSM 1.0.65, healthy assignment polls use a fresh jitter instead of a fixed interval. Since
the INK-150 change it is 12–18 minutes (was 24–36 seconds; the fleet made 1.05 million task requests
a day): a new task may take up to about 18 minutes to appear, the first poll still runs at start-up.

### Poll intervals (INK-150, 24.11.2026)

Data that is not needed in real time is asked for rarely (`App/Services/PosmPollIntervals.cs`):

| Request | Interval |
| --- | --- |
| `POST /api/posm/heartbeat` | 60 s (was 30 s); backend keeps a device online for `POSM_HEARTBEAT_TTL`, default 150 s |
| `GET /api/posm/tasks` | 12–18 min with jitter; failures back off 1, 5, 15 min |
| `GET /api/posm/staff/capabilities` | 15 min while enabled, 1 h while switched off, 5 min when unreachable (was 60 s) |
| `GET /api/posm/fulfillment/capabilities` | configured `FulfillmentPollSec` while the order queue is shown, 1 h once the backend says orders are off |

A server-side switch therefore reaches a running cash desk within these intervals (a restart picks
it up at once). Recommendations, receipts and the sale queue are not throttled.

### Sales the backend refuses for good

`POST /api/posm/sales` answering 409 (identity conflict: the same Standard-N document came back with
another session or total), or 401/403 while the heartbeat with the same device key is accepted
(the queued sale names another pharmacy or device), is a permanent refusal. Such a queue row is
retried at most once an hour, is not made due by a network change, and after 5 refusals moves
unchanged to the `outbox_rejected` table of `outbox.db` (kept for investigation, never sent again).
Network errors, timeouts and 5xx keep the usual backoff (2^n minutes, at most 30).

The primary public QR destination is the CRM's exact HTTPS `/staff` page. During migration the
backend accepts only that configured page and the legacy exact ePharm `/merch/staff` page; it rejects
arbitrary hosts, paths, and insecure URLs. New CRM links use a fixed `?task=1` query marker and put
the bearer token in the `#task=` fragment, keeping it out of ordinary HTTP request logs. Caddy
redirects only `GET /merch/staff` from an already-issued legacy QR to the fixed CRM staff page;
it preserves that legacy query and disables referrer/caching. Other old task/media proxy paths stay
allowlisted; CRM admin/auth routes remain unavailable through ePharm. The server-to-server base URL must use
verified HTTPS when the merchandising service is outside the private INKAR network. Production values live in `.env.prod`:
`MERCH_TASKS_ENABLED`, `MERCH_TASKS_BASE_URL`, `MERCH_TASKS_INTEGRATION_KEY`,
`MERCH_TASKS_TIMEOUT_MS`, `MERCH_TASKS_MAX_CONCURRENT`, `MERCH_TASKS_STAFF_URL`, and
`MERCH_PORTAL_UPSTREAM`. A bounded CRM-only bulkhead rejects excess assignment polls immediately
with `available=false` if the upstream stalls; it does not reserve threads from recommendation or
order handling. Validate the legacy Caddy redirect and CRM HTML/assets first, then deploy the
dual-URL backend with fail-open task/ACK responses; verify legacy links are still accepted by the
backend, but do not assume an old QR token remains valid without a browser/token test. Only then
switch the CRM's public app base URL to `https://crm.inkar.kz` and verify one accepted shown
receipt from a provisioned device. Retire the old proxy paths after the canary.
Do not call an assignment `GET /tasks/active` as a read-only probe: CRM may issue/extend a task link.
After cutover, rescan a refreshed CRM QR instead of reusing a previously issued legacy QR.
The shown-ACK request is serialized to a byte array so HTTP carries a fixed `Content-Length`;
the existing CRM handler did not read chunked request bodies and would reject a valid UUID as
missing. A successful QR display is not complete until CRM returns an accepted receipt.

POSM 1.0.68 treats minimize and Later as a ten-minute, per-delivery tray reminder. A new delivery
does not inherit the previous reminder and opens automatically without activating cashier input.
A native topmost guard refreshes the QR window's z-order without focus changes; shown evidence is
queued only after a visible, non-iconic native window and rendered QR are verified. These checks
still require Windows acceptance above the actual Standard-N desktop.

Displayed receipts are stored atomically under LocalAppData/Epharm, protected by Windows DPAPI
and scoped to pharmacy/device. Retry survives window close and restart; retention is bounded by
token expiry, seven days, 200 entries and 1 MiB. Only an explicit valid `accepted=false` retires a
rejected receipt; transport failures, `available=false` and malformed replies retain it for retry.
Corrupt encrypted data is quarantined before a fresh queue is created, without logging tokens.
Overlay updates preserve this state and pharmacy configuration. A signed current manifest alone
does not prove that offline or broken legacy Windows updaters applied the package.

## Remote commands without AnyDesk (server contract, 25.11.2026)

HQ presses «Диагностика» or «Перезапуск» in «Все кассы»; the backend keeps the command (`till_commands`, V117).
The client is written against this contract (see `docs/02-backend.md`, «Remote till commands»):

| Step | Request | Answer |
| --- | --- | --- |
| Ask for work, at most once a minute (with the heartbeat) | `POST /api/posm/commands/next`, header `X-Posm-Key` (own key), no body | 200 `{"id":"<uuid>","action":"diagnostics"\|"restart","expiresAt":"<ISO-8601>"}`; 204 nothing to do; 401 fleet or unknown key |
| Report | `POST /api/posm/commands/{id}/result`, multipart/form-data: `status` = `done`\|`failed`, `message` (text, cut to 2000 characters), `file` (optional, ZIP, at most 5 MB, diagnostics only) | 200 `{"id","status","finishedAt"}`; 400 bad status, not a ZIP or a file for restart; 404 not this till's command; 409 not taken, already answered or past `expiresAt` (final, do not retry); 413 archive above 5 MB; 503 archive storage down (retry before `expiresAt`) |

- Run only actions compiled into the client; an unknown `action` is answered `failed` with a message, never run.
- `expiresAt` from `next` is the answer deadline (10 minutes after taking). A taken command is not given again.
- Restart: answer `done` first, then restart through the `EpharmPOSM-Watchdog` scheduler task.
- Diagnostics archive: no device key, passwords or full IIN (mask it), 3 days of logs; above 5 MB send `failed`
  with a message instead of the file.

## Operations

Useful docs:

- `App/scripts/README-distrib.md` - dev/release package operation.
- `App/POSM_DEPLOY.md` - production installation, scheduled tasks, update release flow.
- `App/WINDOWS_RUNBOOK.md` - Windows demo and barcode scan examples.

For Standard-N identity diagnostics, run `collect-posm-diagnostics.bat` as administrator on the
cash-desk machine and return the ZIP created on the desktop. The collector redacts device keys and
database passwords.

## Native merchandising candidate and old-client compatibility

The candidate retains `GET /api/posm/tasks` and `POST /api/posm/tasks/shown`, their
existing device authentication, request/response DTOs and historical fixtures.
`MERCH_NATIVE_DELIVERY_ENABLED=false` keeps polling through the existing CRM bridge.
Enabling it selects the native writer; a native failure returns `available=false`
without switching to another writer. An already native-owned delivery token stays
on the native ACK path after the issuance flag is switched off. Polling is not a
shown receipt. No RPS login or new mandatory device header is introduced.

New native QR links use the same canonical domain with the exact SPA path
`/tasks/staff?task=1#task=…`. Existing `/merch/staff` redirects and CRM links remain
compatible; the token is kept out of the new HTTP query. Generic and kiosk
capabilities remain explicitly legacy-owned until their complete workflow is
ported. Windows popup/Standard-N proof remains a separate acceptance gate.

## Receipt row refresh (1.0.73 candidate)

`MainWindow.StandardNReceipt.cs` now owns the same `UpsertItemSetQty` used by
Standard-N log and Firebird observations. ReceiptItem has plain properties, so a
changed row must replace its previous bound instance with a clone before mutation.
Reinserting the same mutated reference could leave WPF quantity/line amount stale
while the separately calculated total was already current. Identity merge, row order,
absolute quantity, zero/removal and recommendation triggers retain their contracts.
The core lifecycle suite compiles this real method instead of a fake upsert.
That receipt-rendering change itself does not enable staff UI or publish a fleet update.

### Password and presentation contract in 1.0.77

The reviewed release gate is enabled in the new binary; old paused binaries remain unchanged.
Both `StaffAuthenticationEnabled` and `POSM_STAFF_AUTH_ENABLED` are still required. The latter
remains false by default and is the fleet emergency disable switch (capability poll up to 60 seconds).
Do not confuse a prepared release with confirmed delivery to every connected or offline cash desk.

RPS registration, activation, reset and login accept 4–128 characters, at most 72 UTF-8 bytes;
all-whitespace passwords are rejected without trimming valid passwords. Existing-password login
in the separate `/learn` accepts the same minimum. Its unrelated password-creation flows and
admin-password policy are unchanged. Both public password-login routes share a durable per-IIN
budget of ten attempts per five minutes; the device limit and SMS limits remain independent.

The preserved teal/terracotta design uses the current ePharm logo, Manrope, responsive panels,
scrolling and input templates with an unclipped content host. Presentation does not replace
current context checks, fresh-generation acceptance, automatic closure or fail-open behavior.
The 17-case candidate WPF harness retains four-character registration and all six input geometries.
Native execution and fake HTTP proof remain separate from an actual Standard-N/real-SMS pilot.
