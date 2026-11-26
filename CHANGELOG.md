# Changelog

All notable production changes to Epharm are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and release tags use semantic versioning.

## [Unreleased]

### Removed

- Pharmacist cabinet, 26.11.2026 (owner): the «Советы» tab is out of the menu for now (four tabs; the page still
  opens at `/learn/recommendations`). «Данные фармацевта» on the profile shows the pharmacist's IIN (from
  `/api/mobile/auth/me`, «Не указан» when empty) and every value in full: the pharmacy with its address wraps
  instead of being cut with «...».
- POSM 1.0.97 (Grigoriy, 25.11.2026: «окна входа не должно быть»): the till no longer has a sign-in window. The old
  «Вход / регистрация сотрудника» window, its tray icon and menu, the employee grant channel from the till
  (`StaffAuthClient`), the process-scoped session state, the prompt policy and the capability polling are deleted
  with their 52 UI cases and unit tests (about 3,000 lines). The Standard-N session diagnostic stays: every five
  minutes the heartbeat reports `staffSourceStatus` («Стандарт-Н» in «Все кассы»), `staffAuthStatus` is always
  `local_disabled`. The server keeps the `/api/posm/staff/*` routes for tills that have not updated yet.

### Added

- Admin Analytics opens with «Фармацевты в ePharm» (owner, 26.11.2026): how many pharmacists are in the system
  (everyone except blocked), how many worked with ePharm today and in the last 7 days with the shares, and two
  charts by day since the launch (people, and their share of the system). «Скачать график» saves both charts with the
  figures as one PNG for management. The data and the rule are those of the pharmacists «Покрытие» tab.
  `GET /api/admin/pharmacists/coverage` reaches back up to 366 days (was 120) and every day carries `active1d` and
  `coverage1dPct`, the response `activeToday` and `coverageTodayPct`; an older backend shows «нет данных» for today.
  The empty blocks «Динамика принятых рекомендаций» (Pilot vs Control) and «Карта активности» are gone.
- Admin «Обучение» → «Онлайн-курсы»: the course drawer has a tab «Как видит фармацевт» (the first one for roles that
  cannot edit): the course on a phone screen as the pharmacist's cabinet draws it, with its path of lessons, the
  reading in parts, the self-check, the video and the graded test, and a selector and arrows to walk the lessons.
  It is the cabinet's own lesson screen in a preview mode (nothing is saved or sent, no lesson is locked, the video can
  be wound forward, the text does not wait for its reading time, a test is graded in the browser, a live lesson is a
  card). The phone is a frame with the page's own stylesheets, so the layout answers to the phone's width. Only the
  open lesson loads media. The lessons tab no longer dumps a player and raw text into every row: one line says what a
  lesson holds.
- POSM 1.0.99 (Grigoriy, 25.11.2026), built for the pilot: the greeting at the first Standard-N sign-in of a shift
  says «Доброе утро, Имя Отчество! Желаем хорошего дня» (by the time of day), two lines on how ePharm helps and «Что
  такое ePharm»; once per person per shift (again only after 8 hours without them at the till), also after a restart;
  never takes the focus, closes by itself
  after 45 seconds, waits for an open recommendation and stays silent during the works. The small «?» of the personal
  window became a clear «Что такое ePharm» entry and page (what it is, how to use it at the till, «Мой кабинет» and
  courses, how a pharmacist earns, where to ask), also in the icon menu. Notices from HQ show in the corner of the
  pharmacist screen until the person closes them with ×, one after another, hidden while a recommendation is open;
  closes survive a lost report and a restart; the history of 30 days opens from the personal window («Уведомления»)
  and the icon menu. The icon keeps its red dot while a required course is not finished or a notice is not closed.
- Notices to pharmacists at the till (server and admin): «Экраны» → «Уведомления на кассах» (SYSTEM_ADMIN, HQ_HEAD)
  writes a title and a text for everybody, chosen pharmacies (their tills and their staff) or chosen pharmacists, for
  1-30 days, and shows per notice how many people saw it on a till and how many closed it; a notice can be withdrawn.
  `POST/GET /api/admin/posm-messages`, `POST /api/admin/posm-messages/{id}/withdraw`; the till uses
  `POST /api/posm/self/messages` and `.../{id}/shown|closed` (device key, person by Standard-N user), and
  `GET /api/posm/notice` carries `messagesAt` so a till asks at once after a change. Tables `posm_messages`,
  `posm_message_targets`, `posm_message_receipts` (V122). `POST /api/posm/self/tasks` adds `requiredOpenCount`
  (required courses not finished, paused ones not counted) for the till's red dot.
- POSM 1.0.96 (Grigoriy, 25.11.2026): «Вход в ePharm выполнен». When the person signed in to Standard-N is known,
  a card in the corner greets them once a day by name («Доброе утро, …!»), says that replacement tips will appear
  here and opens the personal window on a click; it never takes the focus and stays silent during the works.
  The personal window sets or changes the password with two fields («Сменить пароль»), shows a bound phone with a
  tick, calls the tasks row «Задания» and its «?» says when a bonus is credited (minutes after the till sends the
  receipt, after a review when one is needed) and that the month is paid by statement.
- Tasks from HQ in the till's personal window (1.0.96): «Задания» lists the person's courses to take
  (`POST /api/posm/self/tasks`, training assignments) with «Новое», «В процессе · N%», «Просрочено» or «Выполнено ✓»,
  what to do, by when and the reward, and a «Пройти в кабинете» button; a new or overdue task lights the red dot.
  An older server without the feed shows none.
- Till management without AnyDesk, till side (1.0.96): about once a minute the till asks
  `POST /api/posm/commands/next` and does one of two fixed actions HQ gave from the admin: «diagnostics» (a zip of at
  most 5 MB with the tail of the program journal, the receipt journal listing and groups, versions, monitors and
  processes; IINs, phone numbers, keys and tokens masked) or «restart» (answers first, then starts a new copy and
  exits, as after a key change), and answers `POST /api/posm/commands/{id}/result`. No other action exists.
- The recommendation popup is compact (1.0.96): one-line header with the bonus, the asked product on one line,
  each offer in three short lines (name and bonus, maker with price, «Почему»), goal and confirmed stock in one
  small line; five replacements fit where 1.0.91 showed two and a half.
- Cabinet sign-in by phone (owner's decision 25.11.2026): `/learn` has one field «ИИН или номер телефона» and a
  password. `POST /api/mobile/auth/login` accepts `{phone, password}` besides the old `{iin, password}` (the cabinet
  still sends an IIN that way, so an older server keeps working). The number is normalized with `PhoneUtil`
  (`+7 7XX…`, `87XX…`, `7XX…`) and must belong to exactly one card, in any stored format; a number two cards share
  signs in nobody. Phone attempts spend the 10-per-5-minutes budget of the card's IIN, unknown numbers have their
  own. The SMS activation, registration and password reset screens are gone from the cabinet (their endpoints
  stay); a hint says the password is set at the till: ePharm icon by the clock, then «Мой профиль».
- The till changes a password that is already set: `POST /api/posm/self/contact` no longer answers 409 for the
  password (the till asks for it twice and sends the final one). A changed password ends the person's cabinet
  sessions (`mobile_refresh_tokens`); POSM staff sessions end through the existing `password_hash` triggers; the
  credential vault keeps the new password as on first set. The same password sent again changes nothing. A bound
  phone is still never replaced from the till (409). Till passwords follow the POSM minimum of 4 characters (6
  before). `passwordSet` and `phoneBound` in `/api/posm/self` are unchanged.
- Pharmacist tasks for the till (Grigoriy, 25.11.2026: a task is an active training assignment, no separate task
  system): `POST /api/posm/self/tasks` (device key, same body as `/api/posm/self`) lists the courses HQ assigned to
  the person signed in to Standard-N at the till, with how to take them («Откройте «Мой кабинет», раздел «Обучение»,
  курс «…».» plus the program's short description), the completion bonus (`rewardKzt`, the version's
  `completion_bonus`, null without one), due date, progress and state `new` / `in_progress` / `overdue` / `done`, plus
  `newCount` and `openCount`. Finished courses stay 14 days; cancelled ones and those HQ has not started yet are
  hidden. The state follows the training module, so a course completed in the cabinet turns the task done.
  Read-only: no task was created and nothing is sent; tills show the list once a POSM build reads it.
- Training admin, «Программы»: «выполнили N из M» under each program's assignment count
  (`TrainingProgramDto.completedAssignments`, current version), so HQ sees per program how many finished it.
- Tills managed without AnyDesk (Grigoriy, 25.11.2026): in «Все кассы» HQ (SYSTEM_ADMIN, HQ_HEAD) presses
  «Диагностика» or «Перезапуск» (restart asks for confirmation) and sees the latest command of every till, polled
  every 10 s while one is in work. A command is one of a fixed list (`diagnostics`, `restart`) without parameters;
  one command in work per till (409 otherwise). The till takes it with its own device key
  (`POST /api/posm/commands/next`, 204 when there is nothing) and answers with `POST /api/posm/commands/{id}/result`
  (multipart `status` done|failed, `message`, optional `file`: ZIP up to 5 MB, 413 above). A waiting command
  expires after 30 minutes, a taken one after 10 minutes without an answer. Diagnostics archives go to a private
  MinIO bucket (`POSM_COMMANDS_BUCKET`, default `epharm-private`, created on first upload; never the public
  `epharm-receipts`) under `till-diagnostics/`, are downloaded only by HQ and removed after 14 days. Table
  `till_commands` (V117). The till side ships with a later POSM release.
- «ePharm на технических работах» (Grigoriy, 25.11.2026): HQ switches it in `posm_notice` (`PUT
  /api/admin/posm-notice`, HQ roles); the till reads its view about once a minute (`GET /api/posm/notice`, device
  key). POSM 1.0.95 then opens a notice from the ePharm icon instead of the personal window, puts an amber dot on the
  icon and shows no recommendation popup; tills of the pilot channel stay live while `pilot_live` is on. When the
  works end, every till shows «ePharm снова работает» once in the corner of the pharmacist screen without taking the
  focus, also after a restart if it was off at that moment (`%LOCALAPPDATA%\Epharm\notice-state.json`). The
  migration starts the network in maintenance; tills before 1.0.95 do not read it.
- New ePharm icon by the clock (1.0.95): a teal-green rounded square with a white «e» and a small pharmacy cross,
  drawn at the tray's own size; red dot while something waits for the person (profile not filled, a pharmacy
  task), amber dot during the works.
- The recommendation popup groups offers by basket product: with several products, one tab per product («Товар · N»),
  the last scanned one open, each with up to five replacements and five cross-sells; only the open tab counts as
  shown. «Подсказки по чеку» in the ePharm icon menu reopens every offer of the current receipt after the popup was
  closed. The server puts the offers of the product just scanned first and keeps up to five per kind for each
  product (at most fifteen per kind per answer), so an earlier product no longer pushes the new one out; older tills
  keep taking the first five per kind.
- POSM 1.0.94, one ePharm icon by the clock: the personal window (name, earnings, profile, cabinet) also shows
  the pharmacy tasks («Задания аптеки», opens the tasks window), so the separate «i» tasks icon is gone. A «?»
  button explains what ePharm is, that no registration is needed, how the bonus per package is earned and that the
  month's earnings are paid out at the start of the next month. The window never grows taller than the screen
  (the body scrolls), keeps typed phone and password across refreshes, and updates the task count in place.
- No ePharm registration (Grigoriy and Dmitry, 24.11.2026): the pharmacist card is created from the central
  Standard-N directory (IIN, name, pharmacy) the first time the person sells or gets a recommendation at a till
  (`pharmacists.account_source = standardn`, phone unique only when set). `/api/posm/self` serves the till's
  personal window; the till may fill an empty phone (no SMS, `self_declared`) or password but never replaces them.
  A one-time link (`/learn/enter`, 10 minutes, single use) opens the cabinet signed in; such a session lasts one
  shift (`mobile_refresh_tokens.session_ends_at`, 12 h). The till registration of POSM 1.0.92 and the cabinet
  activation by SMS fill such a card instead of refusing the IIN. Recommendation requests resolve the seller
  through the directory too.
- Smart replacements: the rules engine does not offer to replace a product that is on a network promotion today
  (`chain_promo_barcodes`) or that earns the pharmacy at least as much as the promoted one
  (`product_trade_margins`, measured nightly at 04:30 Almaty from Standard-N, read-only). Settings and an
  overview with today's verdict per substitution rule: `/api/admin/replacement-guards`. Cross-sells are not
  filtered. Pilot-only campaigns (`promos.pilot_only`) run only on tills of the POSM pilot channel.
- POSM 1.0.93, no registration on the till: the recommendation popup names the seller signed in to Standard-N
  («● Айгерим Н., бонус начислим вам»), and the ePharm icon by the clock opens a personal window: name, masked
  IIN, pharmacy, earned this month, balance, last rewards, phone and password, «Мой кабинет» and a QR that open
  the web cabinet already signed in. A red dot on the icon stays until phone and password are set; the phone is
  saved without SMS. A phone or password already set is shown, never replaced from the till.
- Seller of every receipt by the central Standard-N directory, without ePharm registration: pharmacies are linked
  to their Standard-N profile by caption after each directory sync (`pharmacies.standardn_profile_id`, HQ links
  marked `manual` are kept), and `/api/posm/sales` resolves Standard-N USER_ID -> IIN -> the registered ePharm
  pharmacist (`standardn_directory`) or keeps the directory name (`standardn_directory_unregistered`). The IIN is
  stored in `pos_sales.seller_iin`. Analytics only: these sources never authorize money, recommendation requests
  keep the proven paths. Switch: `APP_POSM_IDENTITY_STANDARDN_DIRECTORY_ENABLED` (default on). Checked on prod data:
  on 48 144 receipts of 15-18.11 that carried the cashier name the directory named the same person in 98.7% of found cases.
- Standard-N directory names stored as UTF-8 but read through the WIN1251 connection («РљР°СЂРёРјРѕРІР°») are repaired on sync.

- Till builds reach every till only after a checked pilot: a `stable` registration in `/api/admin/app-releases`
  is refused (409) until the same build ran as `pilot` for `POSM_STABLE_AFTER_PILOT_MINUTES` (default 60), a
  pilot till is online on the version and a pilot till sent a receipt. Going back to a previously stable build
  stays allowed.

- Admin «Все кассы» tab (`/screens?tab=fleet`, `GET /api/admin/screens/fleet`): every till in one
  snapshot with online / offline / stale state, POSM version, customer screen, Standard-N status, the
  playlist the till actually gets, and receipts per till and per pharmacy today and over 7 days. Tiles
  filter tills without a Standard-N database, pharmacies without receipts today and pharmacies without
  tills. Tabs scroll sideways on phones instead of being cut off.

- POSM pilot channel: a client release registered with `channel: pilot` reaches only the tills listed in
  `POSM_UPDATE_PILOT_DEVICES` (`pharmacyId|deviceId` or `pharmacyId|*`); every other till keeps the stable
  release, and a pilot till always gets the newer of the two. V110 adds `app_releases.channel` and one current
  release per platform and channel.

- Monitoring alerts reach Telegram: Alertmanager sends firing and resolved alerts through
  `TELEGRAM_BOT_TOKEN` / `TELEGRAM_ALERT_CHAT_ID` (the storefront's alert bot); the token
  stays in a 0600 file, not in the rendered config.
- Business alerts: accepted sales per daytime hour below 300 or below 30 % of the same
  hour over the last 7 days, fewer than 400 POSM devices online in the daytime, 401/409
  rejections doubling against the same hour yesterday, missing business metrics. Metrics
  come from postgres-exporter queries (`ops/monitoring/postgres-exporter/queries.yml`).
- `tools/check-monitoring.sh` (CI): rule unit tests, Alertmanager config with and without
  Telegram, exporter queries against a throwaway PostgreSQL.

### Changed

- Payouts are in development (owner, 26.11.2026): «Финансы / выплаты» shows «Функционал выплат находится в
  разработке» at the top, «Сформировать выплату» and «Утвердить выплату» are disabled with the reason as a tooltip, and
  reading the batches and the Excel report stays. The pharmacist cabinet's profile shows the same notice instead of
  «Как подключить карту». Only the screens changed: the server routes and the 1st and 16th cron are as they were.
- Automatic recommendation reward: a recommended product counts when it is added after the actual display in the same
  checkout and stays in the paid receipt; the 120-second addition cutoff is gone (Grigoriy's decision 24.11.2026).
  Producer reports keep their separate 0–120-second metric.
- V111 adds the partial index `ix_merch_branches_epharm_pharmacy` (built concurrently): every till
  recommendation request looked up its pharmacy's merchandising branch by reading all 680 branches
  (29 million full scans on prod by 24.11).
- Backend gzip-compresses JSON responses over 8 KB when the client accepts gzip (`server.compression`):
  neither Caddy on .76 nor nginx on .74 compressed JSON, so the 657 KB fleet snapshot now travels as ~50 KB.
  POSM does not ask for gzip (no `AutomaticDecompression`), so till responses are unchanged.
- POSM asks for rarely changing data less often (INK-150): heartbeat every 60 s, merchandising
  tasks every 12–18 min, staff capabilities every 15 min (1 h while off), fulfillment capabilities
  once an hour while online orders are off. Sales the backend refuses for good (409, or 401/403
  with a working device key) are retried hourly and archived locally after 5 refusals.
- Backend writes `fulfillment_devices.last_seen_at` at most once a minute and the pharmacist IIN
  observation only on change (at most hourly otherwise); presence TTL default is 150 s.
### Changed

- POSM recommendation popup leads with the pharmacist's reward: a header with the largest
  bonus, a large `+N ₸` badge on each paid offer, the server's personal goal line and an
  auto-close note; ePharm colours and Manrope replace the red section titles. Offers, order,
  limits and focus behaviour are unchanged. Needs a signed POSM release to reach the tills.

### Fixed

- POSM 1.0.100 finds a cashier program that runs under another name and tells a till from a manager's computer
  (26.11.2026: 68 of 506 online tills reported `no_process`, so ePharm could not tell who sells there). The process
  probe asked for the exact names `zkassa` and `kassir`, so the renamed `zkassa_BACKUP_2_6_88` (Teriskey 2/21;
  Standard-N still records its session as `zkassa`) was never found. It now accepts `kassir` and any name that starts
  with `zkassa`, case-insensitively, and the receipt reader uses the same probe. On 10 of the 13 inspected computers
  only `ManagerXP2` (the Standard-N back office) runs: ePharm was installed on the manager's computer, not on the
  till. With no cashier program and a `ManagerXP...` process in the session the heartbeat sends the new
  `staffSourceStatus` `manager_only`; «Все кассы» shows «только «Менеджер»» with the tooltip «Только «Менеджер»
  Стандарт-Н: ePharm стоит не на кассе». Deploy the server and admin before the till.
- A video lesson can be finished when its clip is shorter than the planned minutes (26.11.2026: the 6-second clip of
  lesson 2 in the demo course, planned «2 min», showed «Просмотрено 100%» and a red «Досмотрите видео» and answered
  every progress save with 409). The server now reads the real length from the MP4 in the object storage itself
  (`moov` > `mvhd`, version 0 and 1, `moov` at the start or the end, small ranged reads, kept per URL for 30 minutes)
  and counts the lesson when the clip has been watched; what the browser reports no longer matters for a clip the
  server could measure, so nothing can be skipped. The former rule (planned minutes, the browser's figure between
  half and double of them) stays only for a file that cannot be read (WebM, an address outside our storage). A
  refusal says how long to wait («Видео засчитается через 42 сек. Подождите и повторите»), the cabinet brings that
  reason into view and removes it as soon as the server accepts a save.
- Tips for a product that exists twice in the products table (a website record `prod_01K...` and a Daribar record
  `prod_Daribar...`) are counted as accepted when the till sells the other record: 6 of 9 real tip sales in 30 days
  were lost this way («Тайлолфен хот 20 г № 12» sold 255 times under 8811000 codes, never to the recommended id).
  Duplicates (same normalized name, or a shared barcode with the same numbers, doses and forms) are one product in the
  sale journal and in tip attribution only; showing tips and every money path stay strict and never match by name.
  The name index also logs one summary line per call instead of one warning per catalogue name (hundreds per scan).
- «Принято» in the admin was always 0: the Dashboard tiles and top lists, Lift and the pharmacy registry column
  `rulesAccepted` counted `recommendation_events.outcome='accepted'`, which no till sends since POSM 1.0.13
  made the popup informational (no accept key, no `/outcome` call; on 25.11 there were 0 accepted among 113 thousand
  events and only 2 rejections). A recommendation now counts as accepted when its product was sold in the
  same receipt (`sold_at`, set by the existing attribution) or carries a legacy accepted outcome, once per row.
  The Dashboard tile gets the caption «товар попал в чек». The stored `outcome` and the bonus path are untouched.
- POSM 1.0.92 reads receipts and determines the seller on tills where Standard-N keeps earlier cashier sessions
  open: sessions that started before the running cashier program are ignored, both by the receipt reader and by
  the pharmacist login. Standard-N refreshes the heartbeat of every unclosed session of a workstation and a
  power-off never closes one, so the receipt reader found several live sessions, read no receipt and confirmed
  no sale (24.11: before 1.0.91 only about 20 of 514 tills sent receipts), and 335 tills reported
  `ambiguous_session` for login. While the login is switched off on the server the till still reports, every
  five minutes and without any window, whether the seller would resolve.
- POSM opens the Standard-N database on the pharmacy's main computer when options.ini keeps the server inside
  `base` (`MANAGER:C:\Standart-N\base\ztrade.fdb`, `MANAGER/3050:...`, `MANAGER:alias`, `inet://...`) and has no
  `Server=` key. The server part used to be dropped, so second cash desks looked for the database locally and
  reported `database_unavailable`: about a hundred tills in 86 pharmacies sent no receipts since 1.0.74 required
  the database for receipt capture (3.11). An explicit `Server=` still wins.
- POSM 1.0.91 restores sales capture on busy tills. Only the current and recently
  seen receipts take a print cue, old groups are read apart from them, and groups
  unseen for 6 hours (or the oldest ones while more than 28 of 32 slots are taken)
  move to a private local archive without creating a sale. Before this, one receipt
  paid while its cue was missed, or 32 held receipts, stopped capture for good.
- POSM 1.0.89 separates automatic RPS presentation from grant/session invalidation:
  prompt for a confirmed Standard-N user change, preserve an unfinished same-user
  form through technical context changes, and refresh context only on explicit
  submit. Expired or revoked grants remain invalid; cashier operation stays available.

## [0.2.24] - 2026-11-16

### Fixed

- Use the same captured-sale receipt count in the pharmacist registry, its XLSX
  export and future payout snapshots. Preserve historical payout snapshots,
  stored legacy counters, wallet amounts and the mobile response contract.
- Include the reviewed v0.2.23 changes below in the combined production rollout.

## [0.2.23] - 2026-11-16

This candidate was superseded by v0.2.24 before production deployment.

### Fixed

- Add an explicit unlimited funding mode protected by financial permissions and a
  V107 recovery fence; existing campaigns remain capped and money flags stay unchanged.

- Count pharmacist receipts from captured sales in the current pharmacy, refresh
  dashboard totals, and display refunds and all recommended receipt lines correctly.
- Preserve ordinary-sale source provenance and historical staff proof, isolate
  receipt reconciliation and reward decisions by pharmacy, source and recipient,
  and prevent stale learner requests from mutating a different signed-in session.
- Refuse a pre-V106 cleanup binary on the proof-retaining schema, including when
  the archive is empty. Recovery retains the original protected backup boundary.

### POSM

- Prepare POSM 1.0.90 with scoped seller/name snapshots, source-status diagnostics
  and receipt-collision evidence preservation. The server must be upgraded first.

## [0.2.22] - 2026-11-16

### Fixed

- Read lightweight rule references on each POSM scan and hydrate rich rule cards only
  for matching candidates. Keep global barcode ownership, the complete legacy name/SKU
  fallback pool, campaign dates, AND conditions and ranking without a cross-request cache.
- Use read-only ORM loaders only for matching; administrative edits remain writable.
- Map scalar rule rows directly to immutable values, avoiding per-row projection reflection
  while retaining transaction visibility and fresh campaign status.

## [0.2.21] - 2026-11-16

### Added

- Separate pharmacy inventory, registered POSM identity and recent activity counts,
  with scoped access, observation timestamps and unknown inventory preserved.
  The additive V104 schema performs no inventory import or backfill.

### Fixed

- Reject stale learner authentication responses after identity, step or page changes.
- Correct even-sized recommendation timing medians and distinguish unavailable,
  failed, loading and stale analytics from real zero values.
- Release cancelled Excel export state when filters or permissions change.
- Preserve pharmacy passport and inventory edits during background metrics refresh
  through column-specific updates and locked administrative writes.
- Batch recommendation product and legacy INN reads instead of issuing a separate
  query for every active rule's product, preserving trigger and ranking semantics.

## [0.2.20] - 2026-11-16

### Added

- Opt-in automatic enrollment in an explicitly selected current online program
  for existing and newly registered active or pending pharmacists, including
  accounts created on a POSM cash register or without an initial password.
- A bounded reconciliation worker validates the accountable administrator's
  current authority and the published program/course before enrollment. It
  preserves assignment history across versions, progress, deadlines and money;
  retries do not create duplicate assignments or notifications.

### Fixed

- Serialize manual and automatic training assignment creation through the same
  database authority gate while retaining manual active-only eligibility.

## [0.2.19] - 2026-11-16

### Fixed

- Revalidate the current HQ cashier mapping and source conflicts before a late
  shown acknowledgement can credit a previously journaled sale. Lock all replay
  recipients and native accounts before receipt and campaign-budget locks.
- Reject pre-fix financial recovery artifacts that lack late-ACK identity
  revalidation, preserving the existing V102/V103 forward-recovery boundary.
- Refresh the customer display and recommendation when Standard-N reuses a native
  line ID for another product; never inherit the previous product's identity.
- Preserve unsupported outbox events through restart, reconnect and rollback,
  without acknowledging them or blocking supported sales and shown events.
- POSM 1.0.88 removes SMS wording and legacy-code prompts from new-employee
  registration in Russian and Kazakh. Existing login, recovery, activation and
  separate `/learn` contracts remain; automatic monetary activation stays off.

## [0.2.18] - 2026-11-15

### Added

- Recommendation-sale rewards: immutable visible-show baselines, exact
  product barcode aliases, new full packages added within 120 seconds, confirmed
  later payment, source-scoped IIN/full-name cashier binding, atomic campaign budget
  and one common-wallet credit. Uncertain evidence goes to HQ; returns create a
  review without automatic debit. The separate `/learn` reads the same balance.
- POSM 1.0.87: server-negotiated evidence/refund capture on explicitly
  selected devices, preserved local opt-outs, durable native refund observation
  and exact print batches. Existing RPS fixes, customer display, QR and queues
  remain. Automatic money defaults off; Windows and production pilot acceptance
  are required before activation.
- Additive reward evidence schema and guarded forward recovery: a pre-reward
  backend cannot process the new reservations through legacy financial writers.

### Fixed

- Preserve disappeared unpaid native sources in immutable private archives before
  releasing hot receipt/journal capacity. Suspended, printed, posted and queued
  evidence is retained; archived checkouts cannot revive after restart.
- Keep an idle connectivity gap from poisoning a future unobserved checkout,
  without healing observation gaps in an already open receipt.

- Recover a confirmed created POSM profile into explicit manual sign-in when fresh
  local process confirmation rejects the login handoff. Preserve form-only credentials,
  clear confirmation fields, revoke rejected grants and never replay registration.
- Bound a retained staff grant when Windows cannot confirm the cashier process,
  including partially readable process enumeration;
  recovery still requires fresh server verification and cannot extend its deadline.
- Distinguish existing-profile activation from new SMS-free registration in RU/KK.
- Prepare signed POSM 1.0.86 as a separate client release, gated on exact-main
  Windows update/recovery, registration and two-display acceptance. Backend/frontend
  v0.2.17 and their explicit JVM heap bound are unchanged by this client-only release.

## [0.2.17] - 2026-11-13

### Fixed

- Bound the backend runtime heap to 1280 MiB inside its 2 GiB container. The
  explicit JVM limit prevents host-sized heap ergonomics from causing a cgroup
  OOM; application behavior, frontend source, POSM and protected configuration stay unchanged.

## [0.2.16] - 2026-11-12

### Fixed

- Keep cashier work available when the POSM login window is open, minimized,
  dismissed or waiting for a request. Bound automatic prompts independently of
  security generations; manual tray login and immediate grant revocation remain.
- Recheck the current OS cashier process after background session validation before
  restoring a retained grant, preserving the existing bounded fail-closed recovery.
- Check the current pharmacist account status for issued JWTs, including learner
  requests. Blocked/deleted accounts lose access; pending onboarding remains.
- Preserve HQ sessions during transient refresh failures and prevent stale merch
  login/refresh completions from replacing a different account's session.
- Prepare POSM 1.0.85 as a new immutable client release. Publication remains gated
  on exact-main Windows upgrade, recovery, foreground and two-display acceptance.

## [0.2.15] - 2026-11-11

### Fixed

- Recover a retained POSM staff grant after temporary transport errors only within
  a bounded two-minute verification window. Pending checks do not authenticate the
  employee; definitive refusals, expiry, changed process/cashier and closed forms
  remain terminal. Successful login waits for fresh process proof without replaying
  credentials, and Retry-After cannot extend the recovery deadline.
- Apply the shared 72-byte UTF-8 BCrypt boundary to new pharmacist passwords in
  HQ, learner and POSM flows before consuming recovery proofs. Preserve exact
  password bytes and canonicalize IIN lookup and quota keys consistently.
- Replace scheduled Standard-N directory snapshots in one PostgreSQL transaction
  after reading Firebird, preserving the old snapshot on an insert failure.
- Explain pending or mismatched pharmacy assignments after a correct password
  without granting access or activating employees; preserve the first logout time.
- Prepare signed POSM 1.0.84 as a separate immutable client release. Client
  publication remains gated on exact-main Windows acceptance and artifact checks.

## [0.2.14] - 2026-11-09

### Fixed

- Bound OTP transaction admission and provider concurrency independently, preserve
  registration without SMS, and return truthful retry deadlines without weakening
  password, one-use code or staff-device checks. Absorb short admission bursts with
  a fair two-second wait before borrowing a database connection; sustained overload and
  interruption still stop without leaking permits or consuming the pool reserve.
- Serialize current POSM release publication per platform and enforce the invariant
  with additive V099. Existing duplicate current releases must be resolved before
  migration; no release history is selected or rewritten automatically.
- Preserve financial identity-conflict responses while replacing raw diagnostic
  payloads with safe fingerprints and changed-field names.
- Prepare POSM 1.0.82 with hardened recommendations, staff windows and recoverable updates.
  Windows distribution remains gated on a separately verified signed version and
  old-client update/recovery pilot; a server deployment alone does not update cash desks.
- Keep Linux dangling-link cleanup compatible with Unix while preserving all Windows
  junction, reparse-path and recovery regression checks in required CI.

## [0.2.13] - 2026-11-09

### Fixed

- Initial RPS registration never requests SMS. The backend always advertises the
  SMS-free registration policy and rejects new registration OTP issuance before
  contacting the provider, even with a historical SMS-required setting. Existing
  genuine challenges may complete; password recovery/activation proofs stay unchanged.
- POSM 1.0.81 hides registration SMS controls for every initial, cached and legacy
  policy state. Unsupported registration preserves the form and permits cash work.
  Registration still needs an authenticated device and the supported server route.
- Preserve both password fields after local validation and HTTP errors, allowing an
  explicit retry without re-entering them. Successful accepted authentication closes
  the window and clears its fields; passwords never enter logs or persistent storage.

## [0.2.12] - 2026-11-09

### Fixed

- Accept checksum-valid IINs without interpreting their digits as birth date, century
  or sex in shared backend validation and the admin pharmacist form. Preserve ASCII
  length, both checksum passes, the existing empty-identifier rejection, uniqueness,
  authentication, passwords and pharmacy assignment. No migration or SMS change.
- Cover SMS-free registration, RPS device login and the separate learner web login
  with a synthetic checksum-valid identifier that has no demographic encoding.
  Existing POSM 1.0.80 already accepts twelve digits and needs no binary update.

## [0.2.11] - 2026-11-09

### Fixed

- Scope staff device quotas and advisory locks by authenticated pharmacy and device,
  so independently authenticated cash desks named `KASSA` or `MANAGER` no longer
  consume one another's registration, login or SMS budgets. Preserve existing
  per-IIN/phone budgets and assignment, uniqueness and password checks; no migration.
- POSM 1.0.80 preserves known SMS policy and an in-flight registration during a
  transient background capability failure. Explicit close, process changes and
  server disable still reject stale replies. Show attempt limits as HTTP429 rather
  than overwriting them with a generic service-outage message.

### Testing

- Add isolated500-desk RPS registration/login/session workloads with individual
  keys, repeated device names, positive recommendations/playlists and SQL verification.
  Strengthen the existing six-workload model and make offline replay IDs independent
  of VU allocation. Never use real accounts, SMS or production for these workloads.
- Pause unused Flutter/Android/iOS CI at the owner's request. An explicit
  `MOBILE_APP_CI_ENABLED=true` resumes both mandatory mobile jobs; all remaining
  backend, admin, POSM, security and merge checks remain required.

## [0.2.10] - 2026-11-06

### Added

- Add an explicitly controlled, temporary SMS-free registration flow for POSM 1.0.78:
  IIN, full name, declared phone and password create an active employee at the
  authenticated cash device's pharmacy and issue its normal process-bound grant.
- Record phone provenance as `self_declared` in an additive nullable column; existing
  profiles are not backfilled or reclassified.

### Changed

- When the server explicitly disables registration SMS, the RPS form hides SMS sending
  and code entry. Registration does not contact the SMS provider or accept a fake code.
- Keep passwords, duplicate-account protection, device authentication, Standard-N
  observations, password recovery, `/learn` and all unrelated OTP flows intact.
- Default to SMS-required registration; enable the temporary production policy with
  `POSM_STAFF_REGISTRATION_SMS_REQUIRED=false`. Missing capability on rollback restores
  the compatible SMS flow without replaying registration requests.

## [0.2.9] - 2026-11-06

### Added

- Prepare POSM 1.0.77 employee sign-in on a real Standard-N process start, independently
  of its database or readable cashier identity; keep one prompt per process generation.
- Add a device/pharmacy-bound employee authentication mode with real IIN/password/SMS,
  separate Standard-N identity observations and an additive staff-grant migration.

### Fixed

- Keep simultaneous terminals using one pharmacy package from revoking each other's
  employee grants; replace grants within the device/pharmacy/process scope.
- Assign the registered POSM device pharmacy on both registration routes even when
  Standard-N session data is unavailable; preserve existing account assignment policy.
- Retain the employee form and entered fields on SMS/service failures while releasing
  cashier work, rather than closing the form before a delayed SMS arrives.
- Narrow Standard-N staff-session candidates before applying the bounded overflow guard,
  preserving uncertainty checks and rejecting genuinely ambiguous current sessions.
- Keep cashier work available during database, service and SMS failures; preserve the
  customer display, recommendations, durable receipts and the separate `/learn` cabinet.

### Deployment

- Preserve old protocol-2 clients and require an explicit device-employee capability for
  the new client. No inferred Standard-N identity establishes seller or financial trust.
- Use fresh backup, exact-source images, migration/rollback rehearsal, native acceptance
  and the existing POSM signing trust anchor before the common-channel update.

## [0.2.8] - 2026-11-06

### Added

- Prepare production POSM 1.0.76 staff sign-in with the current ePharm design, native input
  rendering, registration, SMS recovery and the existing Standard-N session/IIN checks.
- Accept passwords of at least four characters in RPS registration, activation, recovery and
  login, including existing-password login in the separate `/learn` cabinet. Keep administrator
  password policy unchanged and enforce one shared IIN login limiter across both entry points.
- Compare the authenticated pharmacist's full name with the exact Standard-N user's display
  name when source IIN is missing or unavailable. Keep actual IIN mismatches authoritative,
  preserve personal IIN/password authentication, and report name matching separately in the registry.

### Fixed

- Clear notification timers when the toast host unmounts, preventing stale callbacks after
  navigation or application teardown.
- Ignore provably expired orphan Standard-N session rows before checking session ambiguity;
  retain conservative handling of unknown timestamps, simultaneous fresh sessions and user switches.
  Detect truncated session snapshots before resolving a person.

### Deployment

- V096 adds optional session name observations and separate pharmacist comparison metadata;
  existing identities, data, campaigns, merchandising, POSM and `/learn` are preserved.
  Enable server staff authentication and publish the signed POSM update only after release
  acceptance and the live pilot. POSM order fulfillment and automatic sale bonuses remain off.
- After accepting four-character passwords, recovery must keep a compatible backend and disable
  its RPS gate if necessary; v0.2.6 cannot authenticate these new short credentials.
- This forward release supersedes the undeployed v0.2.7 candidate. Its published tag is retained
  unchanged; that candidate failed the dated-changelog release check before reaching Sentry.

## [0.2.6] - 2026-11-04

### Fixed

- Recover the manufacturer workspace locally after a section error; keep unrelated administrative
  pages available and distinguish failed pharmacy searches from an empty result.
- Make merchandising tasks, pharmacy/device tables and navigation usable on narrow screens,
  preserving the existing task panels, server permissions and keyboard focus.
- Correct Kazakh labels and dates, monthly-visit binding guidance and learner history scores;
  preserve actual certificate results and distinguish courses without a test from missing results.
- Serialize payout approval and reject inconsistent reservations or invalid amounts before changing
  approval metadata; concurrent reviewers cannot approve the same batch twice.
- Validate published course materials, derive read-only event status, and keep operational metrics
  and fulfillment alert projections consistent with their existing source records.
- Mask pharmacist IIN and phone until an authorised account explicitly reveals them; clear the
  reveal state when the account, role or access version changes.

### Deployment

- Two-image application release with no new migration. Preserve POSM, compound ACC triggers,
  native merchandising, the separate `/learn`, files, identities and financial history.
- Production cashier staff authentication and POSM order fulfillment remain disabled.

## [0.2.5] - 2026-11-04

### Changed

- Integrate PR #146's merchandising workspace refinements into the native ePharm task screen:
  compact queue, full-height right panels, grouped task fields and visible creation actions.
- Keep server-scoped queues, bulk/template assignments, monthly visits, item reviews, history,
  authenticated evidence, Excel and separate pharmacist/QR flows on their existing native APIs.

### Deployment

- Frontend-only audited release; the running v0.2.4 backend, database, Caddy and POSM remain pinned.
  No migration or cashier staff-auth activation is required.

## [0.2.4] - 2026-11-03

### Fixed

- Open and reload native merchandising pages directly without the legacy QR route fence returning
  404; retain exact route allowlists and legacy API protections.
- Wrap recommendation editor actions, previews and long product names without overlapping controls.
  Existing compound ACC conditions and their same-product AND matching remain unchanged.

### Added

- Default-off POSM staff sign-in candidate with current Standard-N user IIN observations,
  contextual registration, session-change guards and fail-open diagnostics.
- Restricted pharmacist-register IIN comparison status and seven isolated native Windows UI checks.

### Deployment

- V095 adds staff IIN observation columns without replacing existing records or financial bindings.
- Keep production staff-auth gates disabled; enabling the cashier prompt requires a separate
  Standard-N/SMS pilot. Existing POSM installations and the separate `/learn` remain compatible.

## [0.2.3] - 2026-11-02

### Added

- Combined ACC trigger factors with same-product AND matching in campaign creation, editing and
  read-only pharmacist recommendations; existing product/group/subgroup/INN triggers remain compatible.
- Confirmed video removal from occupied general broadcast slots in both screen-management views.
- A separate server gate for POSM order fulfillment, preserving incoming website orders,
  administrative history, device credentials and other cash-desk modules.

### Fixed

- Preserve campaign budget edits without catalogue-prefill false dirty state; validate both save
  actions, distinguish prepared/published budget and reject conflicting replacement/cross-sell pairs.
- Preserve distinct recommendation pairs and their history when editing compound conditions;
  equivalent reordered conjunctions cannot bypass conflict or authorization checks.
- Refuse stale admin writes to compound campaigns until the editor is refreshed, preventing
  older cached tabs from silently deduplicating or broadening saved conditions.
- POSM 1.0.74 candidate stops removed cached videos on successful playlist refresh, including an
  authoritative empty playlist, while retaining allowed cache during a network failure.
- POSM order fulfillment is opt-in locally and requires a positive authenticated server capability
  before opening cached queues; production disables this capability independently of order ingestion.

### Deployment

- V094 adds a JSON-shape constraint and durable compound-trigger rollback boundary. Install the
  reviewed shared release verifier before migration; older condition-unaware backend images must
  not serve this database. No production activation or POSM fleet rollout is performed by this change.

### Security

- Update vulnerable frontend redirect/glob dependencies and the modern storefront minimatch branch.
- Keep production npm findings forbidden. Pin and verify the legacy development-only minimatch
  implementation for the narrowly reviewed advisory until the existing November 6 deadline;
  unknown advisories, changed sources and modern vulnerable branches remain blocked.

## [0.2.2] - 2026-10-31

### Added

- Author-scoped Excel/ZIP course import with digest preview and safe repeated imports.
- Internal account profiles, password changes with versioned sessions, and managed account blocking.
- Verified personal merchandising queues with current item and review-count navigation.

### Fixed

- Serialize quiz attempts, administrative assessments and lesson progress per assignment before
  reading their state, preserving accepted attempt counts, best successful grades and final certificates.
  Different assignments remain independent; rejected requests consume no attempt.
- Use actual successful quiz and assessment grades in course results and certificates, preserve
  successful retries, check passing thresholds before rounding and hide learner answer keys.
- Preserve focused form inputs when opening modals and immutable source bindings in test fixtures.
- Retain an always-empty quiz-result array for already open learner tabs, preserving response
  compatibility without returning answer keys, explanations or per-question correctness.
- Refuse administrative-session downgrades once V093 exists, preventing a rollback from
  accepting access tokens already revoked by password changes.

### Changed

- Separate active courses and learning history with continuation and material review.
- Improve merchandising calendar, task review, drafts and producer workspace navigation.

## [0.2.0] - 2026-10-30

### Added

- Separate D9 learner credential export for explicitly authorised HQ accounts: current-password
  proof, scoped one-use XLSX, version-bound encrypted copy and an external recovery key ring.
- Unified-admin development candidate: seven navigation sections, verified account and grant
  management, native merchandising inventory, producer workspace, placement review, pharmacy task
  attempts/evidence/review, compatible POSM task delivery, and separate scoped Excel reports.
- Offline-only rehearsal tools preserve source rows, identities, capabilities and private evidence;
  native automation and new authentication realms remain opt-in pending migration acceptance.
- Added fail-open merchandising recommendations and structured pharmacy/network/warehouse stock
  context to the existing POSM recommendation flow, while preserving ePharm's canonical rules,
  event attribution and pharmacist balance accounting.
- Completed pharmacist course materials end to end: administrators can publish external links,
  interactive content, downloadable attachments, required lessons and minimum video-view targets;
  the mobile app now renders and opens every published material.
- Added local search and operational sorting to the pharmacist training list.

### Fixed

- Preserve verified historical CRM task capabilities during an explicit native cutover,
  including free late submission until original link expiry, without expanding POSM issuer
  authority. Selective migration tooling keeps new live sales and refuses partial/repeated imports.
- Keep the pharmacy recommendation/stock context in the common database after CRM cutover,
  preserving exact product keys, source identities and reviewed manual imports. Optional SQL
  errors leave local recommendations usable; concurrent imports recheck state after their lock.
- POSM 1.0.73 candidate: replace changed receipt rows with a fresh object so quantity,
  price, discount and line amount refresh together with the total after a Standard-N scan.
  The real shared upsert is covered by both log and Firebird lifecycle regression tests.
- POSM candidate: do not report unpaid receipt switches or disappearance as sales. Withhold
  the two known unsafe legacy replay sources, preserve their files and original queue rows,
  and recover a later confirmed print under the same physical sale id without duplicate credit.
- Preserve complete linked Standard-N carts and correlate late prints using fresh physical
  posted rows. Keep original seller/session and unresolved evidence across restart; reject
  unsupported monetary precision without rounding or acknowledging a fabricated sale.
- Forward-recover a recorded failed release with exact main artifacts and a fresh protected
  backup, preserving physical database identity, migration history, grants and new sales.
- Forward the explicitly designated HQ owner through production configuration; an empty value
  creates no owner or additional permissions.
- Add a default-off first-import write freeze on public and internal ingress while core POSM
  sales and heartbeat continue; retain strict refusal to overwrite unexpected native data.
- Guard expanded-schema recovery against older authorization which cannot enforce managed
  scopes, and preserve native evidence, independent credential keys and exact MinIO policies.
- Preserve MinIO object headers, user metadata and tags alongside exact bodies; validate the
  archive before restore and replay only into an isolated, identity-pinned restore container.
- Prevent a delayed pharmacist course response from reopening a course after navigation away from
  it; preserve the separate Training tab and bank-card placeholder from the latest main.
- Added a bounded mobile API timeout so unavailable services fail with an actionable retry state
  instead of leaving the application waiting indefinitely.
- POSM 1.0.65 spreads healthy QR-task polling over 24–36 seconds per device, reducing
  synchronized load on the merchandising fallback without changing recommendations or orders.

## [0.1.53] - 2026-10-29

### Added

- Separate pharmacist Training navigation for all personally accessible courses, including completed
  assignments, with status, progress and deadline. Home keeps one current available course.
- Honest disconnected bank-card placeholder in the pharmacist profile; the action only explains the
  future feature and never collects payment data, links a card or transfers money.

### Rollout

- Frontend-only release; preserve backend, POSM, training data, balances and the current admin UI.

## [0.1.52] - 2026-10-22

### Fixed

- Unified the pharmacist portal accents in the pharmacy teal palette while keeping the
  administration interface unchanged.
- Highlight correct quiz answers in green after submission and retain a distinct error state
  for an incorrectly selected option.
- Updated the test runner to the patched Vitest 4.1.12 release used by the frontend quality gate.

### Rollout

- Frontend-only release. Preserve the running backend, database, POSM services and administration
  workflows; replace only the deployed web frontend image after creating a verified rollback bundle.

## [0.1.49] - 2026-10-22

### Added

- Added a responsive ePharm rating card beside the pharmacist balance, calculated from real course
  progress and assessment scores with a clear 0–100 scale and status.

## [0.1.48] - 2026-10-21

### Fixed

- Keep the pharmacist's Recommendations button inside the learner portal. Its own
  route displays the active recommendation groups and offers from the self-service
  API, without an HQ login or access to the administration console.
- Reconcile the deployed learner reading flow, navigation and personal-data views
  with current main instead of rolling back campaign authoring, ACC triggers,
  reports, merchandising identity confirmation or the training administration.
- Show personal-data authorization failures explicitly rather than displaying a
  fallback balance; discard responses from an account that has already signed out.

### Rollout

- Frontend-only release with an audited, rollback-capable transaction. Keep the
  running backend v0.1.47, POSM, balances, active rules and integration settings.

## [0.1.47] - 2026-10-21

### Added

- Read-only pharmacist self-service APIs for the canonical personal balance in KZT and
  a paginated directory of active substitution/cross-sell recommendations. Both derive
  identity from a valid ePharm pharmacist access token, reject inactive accounts and
  prohibit shared response caching; caller-supplied user identifiers grant no access.
- Recommendation responses retain exact-product, ACC group, subgroup and INN triggers,
  up to five offers per group, per-offer rewards, scripts and confirmed barcode aliases.
  This directory does not replace POSM cart matching or local stock checks.

### Fixed

- Reject expired, malformed, unsupported and invalid-claim JWTs as authentication errors
  instead of allowing token-parser failures to become server errors.

### Rollout

- Backend-only release: preserve the deployed frontend and POSM artifacts, existing
  pharmacist balances, campaign states and integration settings. No database migration,
  automatic account activation, reward payment or rule publication is included.

## [0.1.42] - 2026-10-21

### Changed

- Temporarily pause employee sign-in on cash desks. POSM 1.0.72 does not start the staff
  module, so no login, registration, recovery or staff-status window/tray is created even
  when an older local configuration enables it. A reviewed new binary is required to resume.
- Keep production `POSM_STAFF_AUTH_ENABLED=false` for clients awaiting the update. Preserve
  the complete staff implementation and stored profiles/password hashes for a later pilot.
- Client-only release: backend/admin remain v0.1.41. Standard-N, receipt delivery, recommendations,
  merchandising QR, customer display and the signed updater are unchanged.

## [0.1.41] - 2026-10-21

### Added

- Visible IIN/JSN column in the pharmacist registry, preserving leading zeroes and credential role restrictions.
- POSM 1.0.71 offers a clear registration/recovery choice after failed sign-in, retaining the typed IIN
  while clearing passwords and SMS state; unknown IIN and wrong password remain indistinguishable.
- Connected-cash-desk diagnostics distinguish staff capability, local session/database failures,
  a visible sign-in window and a confirmed staff grant using fixed non-personal status codes.

### Fixed

- Optional staff-channel initialization and capability errors no longer disappear without a bounded reason.
  Diagnostic input cannot insert arbitrary personal data into heartbeat storage or the admin page.
- A heartbeat from an old client clears an obsolete staff-auth diagnostic rather than implying an
  authenticated employee. These self-reported codes are not authorization or monetary identity.

### Rollout

- Standard-N, Windows, receipt delivery, recommendations, QR and customer display remain independent
  of staff sign-in. Passwords remain bcrypt hashes; existing employees/campaigns are not reseeded.
- Read the live diagnostic after rollout before naming a particular Standard-N compatibility issue;
  server capability success alone does not prove that the cashier session is readable or a person signed in.

## [0.1.40] - 2026-10-21

### Fixed

- Restore exact-product campaign selection when the website omits EANs but supplies an exact
  WARE_ID present in the reviewed active ACC snapshot; never guess from a marketplace SKU or name.
- Retain every confirmed barcode alias through catalogue, campaign creation/editing and refresh;
  any alias triggers the same product rule once, while four trigger modes and per-offer rewards remain.
- Refresh picker metadata even when search returns the same product IDs; display barcode counts
  and reject genuinely unidentified products instead of saving an unusable scan trigger.
- Reject globally ambiguous aliases, retain actual scanned-code popup binding, suppress already
  purchased offers under alternate codes, and revoke removed source identities without clearing
  saved keys during an outage. Different strengths, forms and packs are not merged.
- Attribute journal conversion time to the resolved sale product or confirmed legacy alias,
  never an arbitrary first receipt item. Financial gates and medical-rule statuses are unchanged.
- Apply compatible patched Vite and follow-redirects versions required by the security audit,
  without extending the existing development-only advisory exception or bypassing CI.

### Deployment

- Additive barcode-identity migration; backend/admin update only. Existing POSM clients already
  send the actual scan code and do not require another binary release for this change.

## [0.1.39] - 2026-10-20

### Added

- One atomic pharmacist wallet ledger for approved recommendation/training rewards, audited
  adjustments and payout reservations, preserving existing opening balances without fake history.
- A disabled-by-default CRM reward path: HQ-verified executor, separate fresh OTP, one-use grant,
  immutable task approval and idempotent credit into the same wallet. Viewing a QR never credits money.
- An independently gated real-display plus exact printed-sale bridge that creates one bonus
  reservation for HQ moderation, without invented acceptance or historical backfill.

### Fixed

- Serialize manual and imported receipt decisions so a late rejection or Excel row cannot erase
  a confirmed credit or override a final decision.
- Restrict receipt approval, Excel financial imports and balance adjustments to authorized roles;
  only HQ/system administrators may reset another pharmacist's phone or password.
- Reject legacy backend transitions after financial schema expansion, including automatic failure
  rollback, and test the actual previous binary against the forward schema in CI.

### Deployment

- Deploy all financial migrations together. Monetary bridges remain disabled until paired CRM
  and ePharm CI, exact employee mapping, synchronized clocks, backup/restore and a controlled
  real-phone/manager approval pilot. POSM 1.0.70 does not need a second binary update for these APIs.

## [0.1.38] - 2026-10-20

### Fixed

- POSM 1.0.70 clears the customer receipt immediately on confirmed print and after confirmed
  workstation-bound empty reads, rejects stale database results after newer cashier events,
  and preserves live carts when database/session identity is uncertain.
- Recommendation display acknowledgements require actual non-activating native topmost-window
  evidence instead of a hard-coded success flag; the guard retries without taking scanner focus.
- Expired merchandising deadlines hide QR codes even when a delivery token has not expired.
- CRM recommendations tolerate aligned product identifiers, reject unavailable/unmapped context,
  and preserve local offers on stale stock or contradictory external rules within the 5+5 limit.
- Production catalogue defaults and release checks use aptekasosklada.kz; enabling that website
  prevents legacy Medusa remote calls while retaining historical campaign IDs and local snapshots.
- Exact-product, ACC group, subgroup and INN campaign authoring remains protected by compiled-image
  browser regression tests. Receipt, order, task and recommendation paths remain separate.

## [0.1.37] - 2026-10-16

### Added

- HQ-only campaign PDF/JSON reports distinguish confirmed popup displays, confirmed replacement
  sales, pharmacist actions and the separate 0–120-second conversion, with charts and methodology.
- POSM 1.0.69 adds optional employee IIN/password sign-in, SMS-confirmed pending registration,
  first-password activation and bound-phone password recovery. Standard-N and Windows stay usable.
- Staff sessions are scoped to the individual device, pharmacy and observed cashier context;
  account/device changes revoke grants. Phone-wide OTP requests are serialized across mobile/POSM
  purposes, and failed-attempt limits survive transaction rollback. Transient auth data expires.

### Fixed

- Preserve invalidation of an older SMS challenge even when a provider sends a replacement code
  but its response fails. Coordinate first-password activation with the staff password writer.
- Include main's verified, bounded local backup and rollback-bundle retention contracts.
- Remove disposable restore-test anonymous volumes along with their containers, preventing
  repeated verification from accumulating abandoned database copies on the application disk.

### Rollout

- Use the reviewed main source, not the out-of-band v0.1.36 source label. Existing campaigns and
  financial data are not reseeded. Keep the previous images for application rollback.
- `POSM_STAFF_AUTH_ENABLED` defaults to false. Enable the server for a controlled Windows/SMS
  pilot; promote the signed client to the fleet only after the manual acceptance checklist passes.

## [0.1.35] - 2026-10-15

### Fixed

- Restore the reviewed main application after an out-of-band deployment removed product/ACC
  group/subgroup/INN campaign authoring. Preserve the linked catalogue and existing recommendation data.
- Exercise all four trigger choices and draft request contracts against the final admin image,
  not only the development server; reject the obsolete product-only build before promotion.
- New application promotions require the exact current main commit and its successful trusted CI.
  Explicit recovery/rollback remains separate and does not depend on GitHub availability.

## [0.1.32] - 2026-10-14

### Fixed

- POSM 1.0.68 restores new merchandising assignments after a bounded tray reminder and keeps
  QR windows above the pharmacist desktop without activating them or interrupting barcode input.
- Display receipts survive network failures and restarts in a bounded, DPAPI-protected,
  pharmacy/device-scoped queue; expired/revoked receipts do not retry indefinitely.
- Merchandising rejects private-looking DNS names and credential-forwarding redirects. Metrics
  distinguish accepted receipts from HTTP-success rejections, with dedicated alerts.
- CI, Docker builds and transactional release gates inspect the actual JAR for the protected bridge
  and configuration before accepting a release; exact-commit source remains the release authority.
- CRM lifecycle/media fixes are released from its separate repository with independent provenance.

## [0.1.31] - 2026-10-14

### Fixed

- The impressions-and-sales journal bounds receipt candidates before expanding their items,
  avoiding a full historical item scan for each page while preserving counts, ordering and attribution.
- Journal load failures now show an explicit retry state instead of zero metrics and an empty
  history. Failed refreshes retain the last successful data with a visible stale-data warning.

## [0.1.30] - 2026-10-13

### Fixed

- Campaign and recommendation prices now follow the same configured website catalogue as the
  product picker, preserving historical source fallback and previously saved prices on outages.
- Persisted website product references retain their original handles across backend restarts;
  scheduled price refresh no longer relies on an in-memory catalogue visit or an old Medusa ID.
- The trigger-first campaign form retains exact-product, ACC group, subgroup and INN choices.
  Products without a usable barcode or Standard-N identity are clearly unavailable as initial
  exact-product scan triggers; offered products and broad triggers remain independently selectable.

## [0.1.29] - 2026-10-13

### Fixed

- Restored the Firebird JDBC driver required by the Standard-N pharmacist-directory sync. The
  v0.1.28 backend image omitted it; the previous backend remained available while this fix was
  tested. CI now checks both runtime loading and packaging in the production boot JAR.

## [0.1.28] - 2026-10-13

### Added

- Reconciled the live-course editor, broadcast studio and pharmacist live lesson with the
  versioned backend and frontend. A new forward-only migration permits live lessons on databases
  that already received the course-live schema; stream links require safe HTTPS URLs.
- Preserved the deployed website catalogue source behind its existing production feature flag,
  with the local catalogue snapshot retained for outages and existing campaign product IDs.

### Fixed

- Retained the tested cash-pickup-only POSM order guard, ACC group/subgroup/INN recommendation
  triggers and the reliable screen-video upload flow while reconciling server-only changes.
- Protected completed live-room interactions against deletion and poll-id changes, and serialized
  voting, chat and reactions with lesson edits so concurrent actions cannot orphan audit data.
- Kept post-launch broadcast timestamps immutable in the admin editor and made website category
  filtering use category handles, with bounded failure when only the retired origin is unavailable.

## [0.1.26] - 2026-10-12

### Added

- Campaign creation now starts by choosing the recommendation trigger: exact product, ACC
  group, subgroup or international nonproprietary name. The editor shows the matching
  searchable options while retaining the existing product-trigger flow.

### Fixed

- Screen media uploads use a bounded upload-specific timeout instead of the 15-second API
  default. After an uncertain response, the active broadcast slot is refreshed from the server.
- The isolated backup restore test waits until its target PostgreSQL database accepts a real
  query before restoring, avoiding a startup race.
- Updated the storefront Sharp dependency and audited the reproducible merchandising patch
  dependencies used by the release checks.

## [0.1.25] - 2026-10-06

### Added

- ACC group, subgroup and international nonproprietary name (INN) can be selected as
  recommendation triggers alongside the existing exact-product trigger. The classification
  comes from an explicitly reviewed, versioned ACC catalogue snapshot; ambiguous barcodes
  do not trigger a broad rule, and newly authored broad rules start as drafts.
- Reconciled the live certificate editor, course Excel import, program-cover upload and mobile
  retail-price fallback with the new recommendation code; Kazakh certificate glyphs and image
  size limits are covered by regression tests.
- Added a content-verified two-image transaction for the archive-based production host, including
  automatic backend/frontend rollback and a forward-schema compatibility drill.

### Fixed

- Updated the storefront to a patched Next.js release and refreshed available build-tool
  transitive fixes after newly published dependency advisories.
- The frontend dependency gate now fails on every production advisory and every new audit
  finding. Its one time-limited exception is the unpatched, dev-only `braces` advisory
  inherited through build/lint tooling; it expires on 2026-11-06.

## [0.1.17] - 2026-09-25

### Changed

- Redesigned the pharmacist recommendation window for legible replacement and cross-sell lists,
  with up to five products in each section and mouse-wheel scrolling. POSM 1.0.67 preserves
  scanner focus and the existing recommendation trigger and acknowledgement behavior.
- Administrators can set an optional reward separately for every recommended product. Only
  products with a positive reward receive the green highlight; a zero reward is displayed without
  bonus. Existing rules keep their campaign-level reward unless explicitly changed.

### Fixed

- Preserved the production training portal and code-based event check-in in the versioned source
  before this release, including legacy course access and idempotent attendance checks.

## [0.1.14] - 2026-09-23

### Fixed

- The immutable release smoke gate now waits for backend and frontend to report the candidate
  release after a rolling restart. A transient startup 502 no longer triggers a false rollback;
  stale or unavailable routes still fail within a bounded interval.
- Carries forward the application changes prepared in v0.1.13, whose production attempt was
  automatically rolled back before this readiness correction.

## [0.1.13] - 2026-09-23

### Changed

- Cashiers receive and can act on internet orders only for explicitly selected pickup with cash
  payment; existing delivery, online-paid and demo orders remain available for HQ audit.
- POSM 1.0.66 ships the cashier-side eligibility guard with the same signed auto-update channel;
  its offline cache no longer exposes non-cash or non-pickup orders from older releases.
- Reconciled the production training portal, lesson-progress persistence and SPA caching fix with
  the GitHub mainline so the next deployment preserves the live training experience.

### Fixed

- Kept legacy completed lessons readable and URL-only online stages completable after introducing
  per-lesson tracking; a lesson with recorded progress now returns a clear conflict instead of a
  database error when an administrator tries to delete it.

## [0.1.10] - 2026-09-23

### Fixed

- Corrected the public merchandising QR portal's `/merch/staff` asset, task API and media paths;
  limited the ePharm proxy to staff-only routes and required a trusted transport for its service key.
- Bound the POSM offline fulfillment cache to the pharmacy and device so a reassigned cash desk cannot
  display another pharmacy's cached orders. POSM source is versioned 1.0.64 for a separately signed release.
- Validated POSM installer HTTPS and release trust-anchor settings before completing installation.

### Operations

- Added reproducible checks for preserving merchandising photo sidecars across a frontend build,
  atomic `dist` exchange and rollback, and immutable application release preparation.

## [0.1.9] - 2026-09-22

### Fixed

- Merchandising QR delivery receipts now use an explicit JSON byte body with `Content-Length`,
  keeping the ePharm bridge compatible with the fallback Python service while preserving the
  fail-open isolation of recommendations, heartbeats, sales and other POSM schedules.

## [0.1.8] - 2026-09-22

### Added

- Integrated pharmacy merchandising assignments with POSM through an authenticated, fail-open
  task bridge: a configured pharmacy receives the task QR without blocking recommendations when
  the merchandising service is unavailable.

### Fixed

- Kept a recommendation request alive when zkassa.log and the Standard-N Firebird receipt observe
  the same scan, and retriggered recommendations after a POSM restart with an already-open receipt.
- Anchored the pharmacist popup to the visible Standard-N window and moved the customer advertising
  kiosk to the other monitor, including when Standard-N starts after POSM.
- Bound each multi-item response to its real local trigger and prevented transparent windows from
  being acknowledged as displayed.
- Excluded active promotion rules before their start date and after their end date using the
  Kazakhstan pharmacy calendar.
- Produced each POSM update bridge on Windows CI with its exact source commit and SHA-256 provenance,
  preventing a stale binary from being published under a newer version label.

## [0.1.7] - 2026-09-21

### Added

- Production-grade PostgreSQL and MinIO backup, retention, isolated restore testing and backup metrics.
- Prometheus, Alertmanager, Grafana, exporters and actionable availability/SLO/capacity alerts.
- Sentry integration points for backend, admin frontend and Flutter mobile application.
- Immutable release identity, deploy verification and rollback tooling.
- A k6 workload model for a 500-cash-desk fleet.
- Automated mobile release gates, privacy manifest and deep-link declarations.
- Reproducible pharmacy-specific POSM package generation with exact catalog matching, per-device
  credentials and secret-free rollout manifests.

### Fixed

- Added a stable provisioned POSM device identity so packages prepared before installation can use
  heartbeat, recommendations and signed remote updates without a shared bootstrap key.
- Kept the pharmacist recommendation card fully on-screen at 100-200% Windows scaling and above
  Standard-N without stealing scanner or keyboard focus.
- Changed recommendation `displayed_at` acknowledgement to require a rendered, native-topmost window
  whose physical bounds are verified inside the pharmacist monitor.
- Normalized Medusa products containing several GTINs in one barcode field so promotion campaigns
  can be created and matched by the first canonical EAN.
- Included field-level backend validation details in admin error messages instead of showing only
  the generic “check the data” response.

## [0.1.6] - 2026-09-20

### Fixed

- Replaced the stale `0 pharmacies` campaign counter with the current global active-pharmacy target.
- Added an auditable POSM rollout coverage endpoint and admin warning for unprovisioned pharmacies.
- Made recommendation matching fast and resilient to local cash-register barcodes and safe name variants.
- Restart POSM once after device enrollment so every API immediately uses the new per-device credential.

## [0.1.4] - 2026-09-19

### Fixed

- Replaced the generic promotion-rule save error with field-level, accessible validation and an
  actionable error summary.
- Normalized read-only Medusa product snapshots before saving so external catalog text cannot block
  campaign rule authoring.
- Added conditional validation for campaign goals and comparison rows while keeping barcode,
  iPartID and an unused goal optional.

## [0.1.3] - 2026-09-18

### Fixed

- Replaced slow, failure-prone remote Medusa search with an automatically refreshed PostgreSQL snapshot.
- Kept catalogue browsing and promotion product search available during Medusa outages.
- Added release smoke coverage for snapshot readiness and exact-name catalogue search.

## [0.1.2] - 2026-09-18

### Fixed

- Restored secure per-device POSM presence and the live register count during the credential migration.

## [0.1.1] - 2026-09-18

### Fixed

- Restored the live Medusa catalogue for admin, mobile and promotion workflows.
- Reduced Medusa listing latency, added last-known-good cache fallback and hourly linked-product refresh.
- Added release smoke gates that reject an empty production catalogue.

## [0.1.0] - 2026-09-08

### Added

- Initial versioned baseline for the existing backend, admin, mobile and POSM applications.
