# Instructions for AI agents working on Kosh

Read `docs/HANDOVER.md` first — it is the full specification, architecture, sync protocol and setup record.

Rules:
- Two clients share one data format: Android (`app/`) and web (`web/`). Any change to tables, columns, business rules (balances, transfers, FY Apr–Mar, statuses) or the sync format must be made on **both** sides; follow the checklist in HANDOVER §7 and run `scripts/cross-test.sh`.
- Money is integer paise; time zone is Asia/Kolkata; only `POSTED` transactions count.
- Never store or request bank passwords, UPI PINs, OTPs or full account/card/policy numbers (last 4 only). Anything sent to Gemini must go through the masker or be aggregates. AI-derived transactions go to Review, never straight to POSTED.
- Never commit `keystore/`, `local.properties`, API keys or passphrases.
- Keep work battery-friendly: no polling; WorkManager constraints as documented.
- Tests: `./gradlew testDebugUnitTest`, `cd web && npx vitest run test/web.test.ts`. Bump `versionCode`/`versionName` for every APK given to the owner. Pushing `web/**` to `main` redeploys GitHub Pages.
- The owner is non-technical about tooling: explain setup steps in plain words, one step at a time.
