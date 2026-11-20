# Kosh — personal finance for Prash

* **Android app** (`app/`): captures bank/UPI SMS and app alerts, screenshots (on-device OCR) and CSV statements; budgets, recurring payments, goals, LIC/insurance/bond/FD/RD payment tracking, cash, reports, AI assistant (Gemini, optional).
* **Web app** (`web/`): the same data on a computer at https://gourishankarbp-god.github.io/kosh/ — plus Excel and PDF statement import.
* **Sync**: encrypted files in a hidden Kosh folder in your own Google Drive (`drive.appdata` scope). AES-256-GCM, key from your passphrase (PBKDF2-SHA256, 210k rounds). Newest edit wins.
* **12-month window**: the phone keeps the last 12 months; older months move to Drive (encrypted) and can be opened on request. Totals stay everywhere.

Tests: `./gradlew testDebugUnitTest` (Android), `cd web && npx vitest run test/web.test.ts` (web), `scripts/cross-test.sh` (phone → web → phone through real sync files).
