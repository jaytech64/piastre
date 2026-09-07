# Dépenses — Phase 0 capture spike

Throwaway app whose only job is to answer one question:
**does your card issuer actually produce a usable Google Wallet notification?**

Everything downstream — the parser design, the confidence thresholds, half the
production schema — is guesswork until this has run for a week.
Delete this directory once Phase 1 has a fixture corpus.

## What it does

Captures the **verbatim** content of notifications from allow-listed packages and appends
them to `files/captures.jsonl`, one JSON object per line.
The storage format is the export format, which is why there is no database here.

Every other app that posts a notification is recorded in `files/sightings.json` as
**package name and counts only** — never content.
That is how you can discover which package notifies you without this app writing
your messages to disk.

## Using it

1. Open the app. If the top card is red, tap **Grant notification access**.
2. Buy things with your phone for 5–7 days. Normal life.
3. Check the **Captures** tab. If Google Wallet notifications are landing, you have your answer.
4. If nothing appears after a purchase you know produced a notification, open the
   **Apps** tab, find the package that posted it, and flip its switch to start capturing its content.

## Reading the parser's verdict

The spike compiles the real `:parsing` module (via `includeBuild("../parsing")`), so each
capture card shows what the production parser makes of it — `TRANSACTION`, `IGNORED` with
a reason, or `REVIEW` with a reason — and the green card at the top shows the collapse:

    21 notifications  ->  8 transactions

**That number is the test.** If it ever exceeds the count of purchases actually made, a
duplicate got through; scroll for the row wrongly marked `TRANSACTION` and add it to the
fixture corpus as a regression.

## Getting the corpus out

In-app: **Export** writes a single JSON file wherever you choose.

Or straight off the device, which is easier when handing it to an agent:

```sh
adb exec-out run-as io.github.jaytech64.depenses.spike cat files/captures.jsonl
```

## Building

```sh
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Pinned to AGP 8.13.2 / compileSdk 36 on purpose: current AndroidX requires AGP 9 and
compileSdk 37, which is not worth chasing for a throwaway.

## Findings so far

- `com.google.android.apps.walletnfcrel` is installed on the target device and is seeded into the watch list.
- Device locale is **en-CA**, so captured Wallet strings will be English.
  French fixtures need the phone's language switched to français temporarily — the
  production parser has to handle both regardless of UI language.
- Samsung posts a **second, empty group-summary notification** alongside each real one
  (`isGroupSummary: true`, all text fields null).
  The production parser must skip these or every expense will be recorded twice.
- Samsung adds vendor `pde_*` extras.
  Harmless, and a good argument for keeping every extra verbatim rather than
  whitelisting the fields we currently think matter.

## Kill criterion

No usable payment notification after a week of real purchases means the MVP premise
is wrong, and the product needs re-planning before any more code is written.
