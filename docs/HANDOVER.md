# Kosh — project handover

Everything a person or an AI agent needs to understand, build, run and continue Kosh — including from a different GitHub, Google or Claude account. Last updated 20 Nov 2026, at Android **1.0.2** (versionCode 8) and web build of the same commit.

---

## 1. What Kosh is

Kosh (Sanskrit/Kannada for "treasury") is a **personal finance app for one person** (Prash, Bengaluru, India). It has two clients that share one encrypted data store in the owner's Google Drive:

| Part | Tech | Where it runs |
| --- | --- | --- |
| Android app (`app/`) | Kotlin, Jetpack Compose, Room + SQLCipher, WorkManager | Owner's Samsung S25 Ultra (arm64). Installed from a signed APK, not Play Store |
| Web app (`web/`) | React 18 + TypeScript + Vite, IndexedDB, WebCrypto | https://gourishankarbp-god.github.io/kosh/ (GitHub Pages) |
| Sync store | Encrypted files in Google Drive **appDataFolder** (`drive.appdata` scope) | Owner's Google account |
| AI (optional) | Google Gemini REST API, owner's free AI Studio key | Called from phone and browser directly |

There is **no server** of our own. Everything is free-tier: GitHub Pages (public repo), Google Cloud project without billing, Drive storage, Gemini free tier, on-device OCR.

### Core behaviours (the "spec" as agreed with the owner)
- Captures transactions automatically from **bank/UPI SMS** and **payment-app notifications** (GPay, PhonePe, Paytm, BHIM, CRED, Amazon Pay, bank apps); also manual entry, shared text, **screenshots (OCR)**, and **statements** (CSV on phone; CSV, Excel, PDF on web).
- Rule-based recognition engine with a **confidence score 0–100**. Defaults (configurable): **≥95 auto-saved**, **80–94 "Check" queue**, **<80 "Review" queue**, suspected duplicates held. Only `POSTED` transactions count in totals.
- **Transfers are not income/expense**: ATM withdrawal = bank → Cash transfer; cash deposit = Cash → bank; credit-card bill payment = bank → card transfer; own-account transfers. Cash spending is an expense from the Cash account.
- Accounts: bank, credit card, wallet, cash. Balance = opening balance + inflows − outflows of POSTED transactions (both sides of transfers). Card "owed" = −balance. **Correct balance** creates a `transfers.adjust` transfer (or changes the opening balance).
- Split transactions across categories; budgets (with rollover); recurring payments with reminders; savings goals.
- **Investments limited by owner's choice to LIC, insurance and bonds**, plus FD/RD added in future. Only **amounts paid** are tracked (no market value, no MF/stocks/gold/portfolio). Payment history + policy documents (encrypted in Drive).
- Reports: monthly, annual (**Indian FY April–March**), category, merchant, income, cash, transfers, LIC premium history, bond instalment history; CSV/Excel/print.
- Insights (phone): recurring expense ratio (month/year), discretionary burn rate for quick commerce (optionally food delivery), savings rate, time-of-day greeting.
- **Rolling 12 months on devices**; older months archived to Drive and fetched on request; their totals stay (archive_index).
- **AI agents** (Gemini, masked data): (1) read messages the rules skipped → suggestions go to Review, never auto-saved; (2) answer questions from aggregates; (3) monthly summary.
- Security: never store bank passwords, UPI PINs, OTPs; only last 4 digits of account/card/policy numbers; encrypted DB on phone; end-to-end encrypted sync; audit log of every transaction change; AI only receives masked text or aggregates.
- Battery: no polling; debounced uploads; periodic work gated on battery-not-low / charging.

### Owner's banks (seeded recognition)
Accounts: Union Bank, HDFC, Karnataka Bank. Credit cards: HSBC, HDFC, RBL. Real SMS samples from these banks are covered by tests (`RealSamplesTest` and `RealSamplesNov19Test` in `engine/RealSamplesTest.kt`). WhatsApp capture was explicitly dropped.

---

## 2. History of decisions (why things are the way they are)

| Version | What changed | Key decisions |
| --- | --- | --- |
| 0.1 | Phone-only, offline, rule-based capture, review queue, categories, merchant/keyword rules, backups (encrypted file via Android file picker to Drive/OneDrive), app lock, themes | Owner chose **rules instead of AI** at first; personal use only; S25 Ultra only |
| 0.2 | Budgets, recurring, goals, insights (recurring expense ratio, burn rate), fixed-investment tracking (LIC, SIP amounts), greeting, instruction manual | SIP growth not tracked — amounts only |
| 0.3 | New master prompt: accounts incl. cash & cards, cash management, transfers model (`toAccountId`), splits, configurable thresholds, SMS retention, FY helpers, reports, documents, sync change-capture (triggers/outbox) | 80–94% now waits in Check (not counted) |
| 1.0 | Google Drive sync, 12-month archive, Gemini agents, screenshot OCR, CSV import (phone), **web app**, GitHub Pages | Drive is the only sync provider; Gemini free tier with masked data; investments = LIC/insurance/bonds (+ future FD/RD), **SIP/PPF dropped**; GitHub user `gourishankarbp-god` |
| 1.0.1 | Gemini 503 handling: retry once, then fall back through other free models | Model default `gemini-flash-latest` (alias survives renames) |
| 1.0.2 | Correct balance / amount owed (web + phone); web Categories & rules page; privacy page for the OAuth consent screen | Adjustment is a `TRANSFER` in `transfers.adjust`, not income/expense |

---

## 3. Repository layout

```
kosh/
├── AGENTS.md / CLAUDE.md        Short rules for AI agents → points here
├── README.md
├── docs/HANDOVER.md             This file
├── .github/workflows/pages.yml  Builds web/ and deploys to GitHub Pages on push to main (paths web/** or the workflow)
├── scripts/cross-test.sh        Phone → web → phone sync round trip test
├── build.gradle.kts, settings.gradle.kts, gradle.properties, gradlew*, gradle/wrapper/
├── keystore/  (NOT in git)      kosh-release.jks + signing.properties — release signing key
├── app/                         Android app
│   ├── build.gradle.kts         SDK 35, minSdk 26, versionCode/Name, signing, deps
│   ├── schemas/…/AppDatabase/{1,2,3}.json   Room schema history (used by migration test and to generate web/src/schema.ts)
│   └── src/
│       ├── main/AndroidManifest.xml
│       ├── main/java/com/prash/kosh/
│       │   ├── KoshApp.kt               Manual DI container (db, repo, settings, backup, sync, ai), schedules workers
│       │   ├── MainActivity.kt          App lock (BiometricPrompt), Compose NavHost with every route, sync-on-open
│       │   ├── ai/AiAgents.kt           Masker, Gemini client (retry/fallback), unknown-message reader, Q&A, monthly summary
│       │   ├── backup/Backup.kt         Encrypted full backup file ("KOSH" v1, PBKDF2 210k + AES-GCM), daily BackupWorker
│       │   ├── capture/Capture.kt       SmsReceiver, PaymentNotificationListener, SmsInboxImporter, ShareReceiverActivity, ScreenshotReader (ML Kit)
│       │   ├── data/
│       │   │   ├── Entities.kt          Room entities + views (see §5)
│       │   │   ├── Daos.kt              All queries; COUNTED = ('POSTED')
│       │   │   ├── AppDatabase.kt       Room DB v3 (SQLCipher on device), migrations, trigger callback
│       │   │   ├── Migrations.kt        V1→V2, V2→V3, SYNCED_TABLES, change-capture triggers
│       │   │   ├── Repository.kt        Ingest pipeline, duplicates, status routing, learning, audit, splits, statement import, review-queue adds
│       │   │   ├── PlanOps.kt           Budgets, recurring, goals, investment linking, remaining payments
│       │   │   ├── Settings.kt          Non-secret prefs (thresholds, theme, name, retention…)
│       │   │   └── Documents.kt         Policy document attach/open/delete (FileProvider)
│       │   ├── engine/                  Pure Kotlin, no Android deps (unit-testable)
│       │   │   ├── Model.kt             Enums (CaptureSource, PaymentMethod, TxnType, TxnStatus, IgnoreReason…), Recognition.statusFor()
│       │   │   ├── MessageFilter.kt     OTP/promo/failed/collect-request/reminder filtering
│       │   │   ├── TransactionParser.kt Bank templates + generic parser, sender→bank map, notification apps
│       │   │   ├── MerchantNormalizer.kt Alias dictionary matching
│       │   │   ├── RecognitionEngine.kt Categorise, classify type, ConfidenceScorer
│       │   │   ├── DuplicateDetector.kt Merge vs suspected duplicate rules
│       │   │   ├── StatementParser.kt   CSV statement → rows → message-like text
│       │   │   ├── SeedData.kt          Categories, merchants dictionary, keyword rules, phrases
│       │   │   ├── Metrics.kt           FY helpers, greeting, ratios, burn rate, due dates
│       │   │   └── Money.kt             Paise formatting (Indian grouping), parsing
│       │   ├── notify/Notifications.kt  Channels, budget alerts, ReminderWorker (daily)
│       │   ├── security/KeyManager.kt   Android Keystore wrap/unwrap; DB key; backup password
│       │   ├── sync/
│       │   │   ├── SyncCrypto.kt        "KSY1" file format + HLC clock
│       │   │   ├── RemoteStore.kt       RemoteStore interface, DriveRemoteStore (REST v3 appDataFolder), FolderRemoteStore (tests)
│       │   │   ├── SyncEngine.kt        Push/pull/apply/compact/archive/documents (see §6)
│       │   │   └── SyncManager.kt       Android glue: state in prefs, GoogleAuth (Identity AuthorizationClient), SyncWorker, MaintenanceWorker
│       │   └── ui/                      Compose screens: Home/Dashboard, Transactions, Review, Edit, Accounts, Cash, Plan hub, Budgets, Upcoming, Goals, Investments, Insights, Reports, Recognition settings, Capture, Categories/Merchant/Keyword rules, Ignored, Backup, Security, Appearance, Sync & archive, Archive month, AI assistant
│       └── test/java/com/prash/kosh/    JUnit + Robolectric (sdk 34) tests, ScreenshotTour (renders every screen to build/screenshots)
└── web/                         Web app
    ├── index.html               CSP, Google Identity Services script, manifest
    ├── public/                  icon.svg, manifest.webmanifest, sw.js (offline shell), privacy.html (OAuth consent screen link)
    ├── vite.config.ts           base '/kosh/' (must equal the GitHub repo name)
    ├── src/
    │   ├── schema.ts            Generated from app/schemas/3.json: tables, columns, refs, complete()
    │   ├── crypto.ts            Same KSY1 format via WebCrypto + CompressionStream; Hlc
    │   ├── remote.ts            DriveStore (fetch), MemoryStore, GIS token client
    │   ├── db.ts                LocalDb: uid-keyed records in memory + IndexedDB; push/pull/apply/archive/documents
    │   ├── logic.ts             Money, dates (IST), totals, balances, budgets, due dates, import classifier
    │   ├── statement.ts         CSV/Excel(SheetJS)/PDF(pdf.js) statement readers
    │   ├── importer.ts          Statement import with SMS merge; screenshot OCR (tesseract.js)
    │   ├── ai.ts                Masker, Gemini (retry/fallback), context, ask, summary, unknown messages
    │   ├── app.tsx              Shell, routes (hash), sync controller
    │   ├── pages.tsx            All pages
    │   ├── demo.ts              Sample data for ?demo
    │   ├── main.tsx, styles.css
    └── test/web.test.ts, test/cross.test.ts (vitest)
```

---

## 4. Build, test, release

### Prerequisites
- JDK 17, Android SDK (platform 35, build-tools 35), `local.properties` with `sdk.dir=…`
- Node 22 + npm (web)
- AGP 8.9.1, Gradle 8.14.3 (wrapper), Kotlin 2.0.21, Compose BOM 2024.12.01, Room 2.6.1 + KSP, SQLCipher 4.6.1, WorkManager 2.10.0, play-services-auth 21.3.0, ML Kit text-recognition 16.0.1, kotlinx-serialization 1.7.3, Robolectric 4.14.1

### Commands
```bash
# Android
./gradlew testDebugUnitTest                 # 110 tests (includes ScreenshotTour → app/build/screenshots/*.png)
./gradlew assembleRelease                   # app/build/outputs/apk/release/app-release.apk (arm64 only, signed if keystore/ present)

# Web
cd web && npm ci
npx vitest run test/web.test.ts             # unit tests
npm run build                               # tsc + vite → web/dist
npx vite preview                            # http://localhost:4173/kosh/?demo

# Phone ↔ web compatibility (uses build/cross-drive as a fake Drive)
scripts/cross-test.sh
```

### Releasing the phone app
1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
2. `./gradlew assembleRelease` with `keystore/` present.
3. Give the APK to the owner; it installs over the old one **only if signed with the same key**.

Signing key: `keystore/kosh-release.jks`, passwords in `keystore/signing.properties` (keys: `storeFile`, `storePassword`, `keyAlias`, `keyPassword`). **Not in git.** The owner holds a copy.
Certificate SHA-1: `0A:1D:DC:85:D8:25:F4:25:48:ED:6E:3F:56:02:02:71:BC:AD:50:A0`
SHA-256: `02:F0:32:63:F3:FE:14:8F:F1:ED:DE:56:AC:86:96:73:02:11:8F:E4:4B:7A:5D:E6:50:55:44:DD:C4:52:E4:43`

### Releasing the web app
Push to `main` (changes under `web/`). The workflow runs the web tests, builds and deploys to Pages. Repo Settings → Pages → Source must be **GitHub Actions**.

---

## 5. Data model (Room v3; identical column names in sync files and the web app)

Money is always **paise as Long/integer**. Dates are epoch millis; calendar logic uses **Asia/Kolkata**. Booleans are 0/1 integers.

| Table | Key | Purpose / notable columns |
| --- | --- | --- |
| accounts | id | name, kind (BANK/CARD/WALLET/CASH), bank, last4, openingBalancePaise, archived |
| categories | id (text, e.g. `food.delivery`) | name, parentId, kind (EXPENSE/INCOME/TRANSFER/INVESTMENT), icon, sortOrder, hidden, isFixed |
| merchants | key (text) | displayName, categoryId, source (SEED/USER), aliases (`|`-separated) |
| keyword_rules | id | keyword, categoryId, sortOrder |
| transactions | id | type (EXPENSE/INCOME/TRANSFER/REFUND/INVESTMENT), direction (DEBIT/CREDIT), status (POSTED/PENDING_VERIFICATION/NEEDS_REVIEW/SUSPECTED_DUPLICATE/REJECTED), amountPaise, dateMillis, accountId, toAccountId (transfers), accountLast4, merchantKey/Name, categoryId, method, reference, note, confidence, sources (comma list of SMS/NOTIFICATION/SHARED/MANUAL/IMPORT), linkedTxnId (refund→expense or duplicate→original), recurringId, createdAt, updatedAt |
| transaction_splits | id | transactionId, categoryId, amountPaise, note |
| raw_messages | id | source, sender, body (emptied after retention), receivedAt, hash (unique), outcome, ignoreReason, confidence, transactionId |
| audit_log | id | transactionId, at, action (CREATED/EDITED/MERGED/STATUS_*/DELETED…), before/after JSON |
| budgets | categoryId | monthlyLimitPaise, rollover, startMonth (yyyy-MM) |
| recurring_rules | id | name, type, amountPaise, categoryId, merchantKey, accountId, frequency (MONTHLY/QUARTERLY/HALF_YEARLY/YEARLY/ONE_TIME), startDate, nextDueDate, lastPaidDate, remindDaysBefore, active |
| savings_goals / goal_contributions | id | goals with target; contributions |
| investments | id | kind (LIC/INSURANCE/BOND/FD/RD; legacy SIP/PPF rows may exist), name, institution, refLast4, startDate, maturityDate, ratePercent, installmentPaise, frequency, sumAssuredPaise, maturityAmountPaise, matchKeywords, status, note |
| investment_entries | id | investmentId, kind (CONTRIBUTION/WITHDRAWAL…), amountPaise, dateMillis, transactionId, note |
| summaries | id (unique month) | month, text, source (AI/LOCAL), createdAt |
| documents | id | investmentId, name, mime, sizeBytes, fileKey (file in app storage and `doc-<fileKey>.kosh` in Drive), addedAt |
| archive_index | month (yyyy-MM) | income, expense, refunds, invested, transfers, recurring, quickCommerce, categoryTotalsJson (top-level category → paise), transactionCount, archivedAt |
| sync_meta | (tbl, uid) | uid ↔ localKey map, version, deleted |
| sync_outbox | seq | tbl, localKey, op (U/D), at — filled by triggers |
| sync_flag | id=1 | suppress (1 while applying remote changes so triggers don't echo) |
| view expense_lines | — | Expense lines: split parts, else the transaction itself (category stats use this) |

**Loans and debit cards (web feature, stored in existing tables so the phone syncs them unchanged).** A loan is a `WALLET` account named `Lent · <person>` / `Borrowed · <person>` with `bank = 'Lent'|'Borrowed'`; lending, receiving back and paying back are `TRANSFER`s (category `transfers.people`) between the user's account and that loan account; "closing" a loan sets `archived = 1`. A debit card is a `WALLET` account with `bank = 'debit:<uid of the linked bank account>'` that never holds money — the web editor rewrites `accountId` to the linked bank account on save. The phone currently shows loans/cards as plain wallets (it counts loan balances in its wallet total) and has no UI for them; native phone screens would need a schema-free port of `web/src/loans.ts` plus `isLoanAcct` / `isDebitCard` filters in its account lists and totals.

Migrations: V1→V2 (plans), V2→V3 (accounts/cash/splits/sync tables). `MigrationTest` checks 1→2→3 with real schema JSON. To change the schema: add entity changes, bump version, write `V3_TO_V4`, export schema 4.json, extend `MigrationTest`, **regenerate `web/src/schema.ts`** (see §7), and keep `SYNCED_TABLES` / web `TABLE_ORDER` / `REFS` in sync.

---

## 6. Sync protocol (format 1) — the contract between phone and web

All files live in Drive **appDataFolder** (invisible in the Drive UI; only this OAuth client can see them). Any client implementing this spec can join.

### Files
| Name | Content |
| --- | --- |
| `kosh-meta.json` (plain JSON) | `{format:1, salt:b64(16 bytes), iterations:210000, check:b64(encrypt(key,"kosh-sync-check-v1"))}` — created by the first device (the phone) |
| `ops-<device>-<seq 10 digits>.kosh` | One batch of changes from one device; never rewritten. `{format, device, seq, createdAt, changes:[…]}` |
| `snap-<hlc>.kosh` | Full state `{format, device, createdAt, records:[…], cursors:{device:seq}}`; written by the phone when ≥300 ops files exist; ops covered by it and older than 14 days are deleted |
| `archive-<yyyy-MM>.kosh` | `{format, month, records:[…]}` — transactions, splits, raw messages, audit rows of a month moved off devices |
| `doc-<fileKey>.kosh` | An attached document's bytes |

### Encryption (`SyncCrypto.kt`, `web/src/crypto.ts`)
```
key  = PBKDF2-HMAC-SHA256(passphrase, salt, 210000, 256 bits)
file = "KSY1" || iv(12 bytes) || AES-256-GCM(gzip(plaintext), aad="KSY1")   (16-byte tag appended, as Java/WebCrypto do)
```
Wrong passphrase → GCM tag failure → `WrongPassphrase`. The passphrase never leaves devices; on Android the derived key is wrapped by the Keystore; on web the CryptoKey is stored non-extractable in IndexedDB.

### Records and changes
```json
{ "t": "transactions", "u": "<uid>", "v": "<hlc version>", "r": { …columns… } }   // upsert
{ "t": "transactions", "u": "<uid>", "v": "<hlc version>", "d": true }            // delete (tombstone)
```
- `r` holds every column **except** the local integer key; for string-keyed tables (`categories`, `merchants`, `budgets`, `archive_index`) the uid **is** the key and the key column is included.
- Reference columns (`REFS`: e.g. `transactions.accountId`, `toAccountId`, `linkedTxnId`, `recurringId`; `transaction_splits.transactionId`; `raw_messages.transactionId`; `audit_log.transactionId`; `investment_entries.investmentId/transactionId`; `goal_contributions.goalId`; `recurring_rules.accountId`; `documents.investmentId`) carry the **uid** of the referenced record. Unknown references apply as null.
- Deterministic uids so seed data from two devices matches: Cash account `acct:cash`; accounts with last4 `acct:<bank lowercase>:<last4>:<kind lowercase>`; keyword rules `kw:<keyword>`. Otherwise random UUIDs.
- Writers must send **complete** rows (all NOT NULL columns) — the phone inserts with ContentValues. The web uses `complete()` from `schema.ts`.
- **Version** = hybrid logical clock string `%013d-%04d-%s` (millis-counter-device). Compare as strings; newest wins (last-writer-wins per record). Devices `observe()` remote versions to stay ahead.
- Applying is ordered by table (`TABLE_ORDER`, parents first) inside one DB transaction with `sync_flag.suppress=1`.

### Device flow
- **Setup (phone)**: no meta → create meta, enqueue all rows, push → `CreatedNew`. Meta exists → verify check, pull, and if the phone already had transactions, enqueue everything and push → `JoinedExisting`. A fresh install clears its seed outbox before pulling.
- **Push**: outbox (deduped to latest per row) → one ops file, `seq++`, clear outbox.
- **Pull**: list `ops-*` of other devices; if no cursors yet or a gap exists (ops tidied away) apply the latest snapshot first; then apply each newer ops file in seq order and advance `cursors[device]`.
- **Web**: device id `w<10 hex>`; keeps records in uid space (no local ids); `connect()` requires an existing meta (phone must be set up first).
- **Archive** (phone only, weekly while charging, `MaintenanceWorker`): months before `firstKeptMonth = currentMonth − 11` → push pending → write/merge `archive-<month>.kosh` → upsert `archive_index` (synced) → delete those rows locally with suppress on. Any device applying an `archive_index` record drops that month's transactions locally. Web/phone "Archive" screens read the archive file on request (read-only).
- **Documents**: phone uploads local files missing in Drive and downloads missing ones; web downloads on click and uploads new ones.

### Android scheduling (battery)
| Work | Trigger | Constraints |
| --- | --- | --- |
| `sync-soon` (SyncWorker, unique KEEP) | first local change (outbox count > 0) + 120 s delay; app open if last sync > 15 min (delay 0) | network |
| `sync-periodic` | every 6 h | network, battery not low |
| `maintenance` (MaintenanceWorker) | every 7 days | charging, battery not low — trims SMS bodies per retention, syncs, archives; if sync is off, clears the outbox |
| `daily-backup`, `payment-reminders` | 24 h | battery not low |
Web: push 20 s after last edit (only with a live token); pull every 10 min only while the tab is visible and on returning to the tab after 5 min.

---

## 7. Keeping phone and web consistent (checklist for changes)

1. Schema change → bump Room version + migration + `MigrationTest` → regenerate `web/src/schema.ts` from `app/schemas/com.prash.kosh.data.AppDatabase/<n>.json` (the generator logic: list columns per table in `TABLE_ORDER`, skip integer `id` keys, keep text keys; mark NOT NULL and defaults). Update `REFS` on both sides if a new reference column appears.
2. Business rules duplicated in both clients: balances (`balanceOf`), counted = POSTED, expense lines with splits, budget rollover, FY = Apr–Mar, transfer categories (`transfers.cash`, `transfers.cashdep`, `transfers.ccbill`, `transfers.self`, `transfers.adjust`), import classification, Gemini masking. Change both and their tests.
3. Run `scripts/cross-test.sh` after any sync-format change.
4. Web base path `/kosh/` must equal the repo name; OAuth Web client origin must equal the Pages origin.

---

## 8. Recognition engine (phone)

`RawInput(source, sender, body, receivedAt)` → `MessageFilter` (OTP, promo, failed, collect request, reminder → Ignored with reason) → `TransactionParser` (bank-specific templates for HDFC, ICICI, Union Bank, Karnataka Bank, HSBC, RBL, Kotak, SBI…, then generic parser; extracts amount, direction, last4, merchant, date, reference, method, bank) → `MerchantNormalizer` (alias dictionary: seed + user merchants) → categorise (user merchant rule > dictionary > keyword rules) → classify type (refund, transfer phrases, investment phrases, card-payment credit) → `ConfidenceScorer` (template 40 / generic 25, +20 amount, +10 explicit direction, +5 last4, +10 merchant, +category source score, +5 known sender; IMPORT counts as known sender) → `DuplicateDetector` (same reference+amount → merge; cross-channel SMS/notification/IMPORT within window and same account/merchant → merge; else suspected duplicate) → status by thresholds → insert + audit + raw message link → `afterCounted` (budget alerts, investment/recurring linking).
Learning: when the user changes a merchant's category ("always"), a user merchant rule is saved and optionally applied to past rows.

Statement import (phone CSV): `StatementParser.parseCsv` detects header columns (date, narration, debit/credit or amount + Dr/Cr), `toMessage()` builds `Rs.X debited from <bank> A/c XX<last4> on dd-MM-yy UPI. Info: <cleaned name> Ref <12-digit UPI ref>` and runs it through `ingest` with source IMPORT, so SMS already captured are merged.

Web import uses `statement.ts` (+ SheetJS for Excel, pdf.js for PDF with table detection and a running-balance fallback) and `importer.ts` (hash-dedupe, merge with existing same amount/direction/account within 2 days and non-conflicting reference, classify via synced merchants/keyword rules + phrase rules, status by 95/80).

---

## 9. AI agents

- Client: `Gemini` (Kotlin) / `gemini()` (TS). REST `v1beta/models/<model>:generateContent` with header `x-goog-api-key`. Default model `gemini-flash-latest`; on 500/503 retry once after 2 s, then fall back to `gemini-2.5-flash`, `gemini-flash-lite-latest`, `gemini-2.5-flash-lite`; 400/403 = bad key; 429 = free limit.
- Key storage: phone — Keystore-wrapped in prefs `kosh_ai`; web — `localStorage['kosh.geminiKey']` (warned on shared computers).
- Masker removes emails, UPI IDs, phone numbers, IFSC, PAN, OTP/PIN/password phrases, and long digit runs (keeps last 2 digits). 1,200-char cap.
- Agents: `readUnknown` (ignored messages with an amount, excluding OTP/promo/collect/failed) → JSON suggestions → **validated** (amount must literally appear in the message; type/category/date checked; confidence capped at 79) → saved as `NEEDS_REVIEW`; `ask` (aggregate context only: monthly totals, top categories, top merchants for 3 months, budgets, investment kinds); `monthlySummary` (AI if key, else local text) stored in `summaries`.

---

## 10. External accounts and configuration (current owner)

| Item | Value / where |
| --- | --- |
| GitHub repo | https://github.com/gourishankarbp-god/kosh (public; Pages via Actions) |
| Web app | https://gourishankarbp-god.github.io/kosh/ (demo: `?demo`) · privacy page `/kosh/privacy.html` |
| Google Cloud project | "Kosh" in the owner's account; Drive API enabled; Google Auth Platform: External, **In production**, scope `…/auth/drive.appdata`; Branding home page + privacy link above; authorised domain `gourishankarbp-god.github.io` |
| OAuth Android client | package `com.prash.kosh` + SHA-1 above (no ID needed in code) |
| OAuth Web client | origin `https://gourishankarbp-god.github.io`; its Client ID is pasted in web Settings (stored in `localStorage['kosh.clientId']`; can also be baked in via env `VITE_GOOGLE_CLIENT_ID`) |
| Gemini | free key from aistudio.google.com, pasted per device |
| Sync passphrase | known only to the owner; unrecoverable |
| Instruction manual / setup guide | Claude Docs artifacts in the owner's Claude account (copies of the content live in this file) |

---

## 11. Continuing with a different account

### Different GitHub account or repo name
1. Create the new repo, push this code (without `keystore/`).
2. If the repo name is not `kosh`, change `base` in `web/vite.config.ts` to `/<repo>/`.
3. Repo Settings → Pages → Source: GitHub Actions; run the workflow.
4. Google Cloud: add the new origin (`https://<user>.github.io`) to the Web client's JavaScript origins and update the Branding home page/privacy URLs and authorised domain.
5. Update links in `web/public/privacy.html`, `.github/workflows/pages.yml` comment, README and this file.

### Different Google Cloud project (same Google user)
Create clients again exactly as in §10. The Drive appDataFolder is **per OAuth project**: files written through the old project's clients are invisible to the new one. Either keep the old project, or on the phone turn sync off/on with the new project (phone re-uploads everything; set a passphrase again), then reconnect the web app.

### Different Google account (data owner)
Same as above, and data moves only through the phone: turn sync off, sign in with the new account in Sync & archive, choose a passphrase → the phone uploads its data. Archived months (older than 12 months) live only in the old Drive; fetch what you need first or keep the old account connected until then. Alternatively, restore from an encrypted backup file (More → Backup & restore).

### Different signing key
Android will not update an app signed with another key. Options: keep using the original `keystore/` (recommended), or make a backup on the phone, uninstall, install the newly signed APK, restore the backup, and register the new SHA-1 as another Android OAuth client.

### Different AI tool / developer
Everything needed is in this repo. Start with: build + tests (§4), read §5–§7, run `?demo` locally, then make changes keeping §7's checklist.

---

## 12. Known limitations and ideas (backlog)

- Web app has no passcode lock (phone has biometric lock). Recommend: lock screen deriving a key from a PIN to encrypt IndexedDB.
- No conflict UI: last-writer-wins per whole record (a field edited on both devices keeps only the newest record).
- Investments auto-linking from SMS exists only on the phone; web statement import categorises LIC/bond payments but does not create `investment_entries`.
- Web screenshot OCR downloads Tesseract data (~10 MB) from jsDelivr on first use; Content-Security-Policy in `index.html` allows `cdn.jsdelivr.net` and `tessdata.projectnaptha.com`.
- PDF statement parsing is heuristic; scanned PDFs need CSV/Excel instead.
- Recurring payments on web: no reminders (phone sends notifications).
- APK ~32 MB (ML Kit bundled model, R8 off). Enabling R8 needs keep rules for kotlinx-serialization and Room.
- Old SIP/PPF investment rows from 0.2 are still displayed if present but cannot be created.

---

## 13. Test inventory

| Suite | Covers |
| --- | --- |
| `RecognitionEngineTest`, `RealSamplesTest`, `RealSamplesNov19Test` | Parsing templates and real bank SMS of the owner's banks; filters; categories; confidence |
| `DuplicateDetectorTest` | Merge / suspected duplicate rules |
| `MetricsTest` | FY, greeting, ratios, burn rate, due dates, budgets |
| `StatementParserTest` | CSV column detection, Dr/Cr |
| `RepositoryTest`, `PlanOpsTest` (+`CashAndSplitTest`) | Ingest pipeline on Room, review routing, learning, audit, cash, splits, investments linking |
| `MigrationTest` | DB upgrades 1→2→3 and outbox triggers |
| `SyncEngineTest` | Two devices converge, LWW conflicts, deletes, wrong passphrase, encryption at rest, archive |
| `CrossPlatformTest` + `web/test/cross.test.ts` | Phone ↔ web through real files (driven by `scripts/cross-test.sh`) |
| `AiAgentsTest` | Masking, validation of AI output, aggregates-only prompts, local summary, statement merge |
| `UiSmokeTest`, `ScreenshotTour` | Compose screens render; 34 screenshots |
| `web/test/web.test.ts` | Crypto, two browsers + phone ops, archive drop, statements (CSV/PDF fallback), import merge/categorise, money/balances/budgets/dates, AI masking/validation |
