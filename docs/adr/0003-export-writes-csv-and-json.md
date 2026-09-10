# 3. Export writes both CSV and JSON

- **Status:** Accepted — implemented in `c2b9dd1` (Phase 2, commit 8).
- **Decided:** 2026-09-07, during Phase 2 planning (`32caf07`).
  Scope settled 2026-09-09 during the build.
- **Touches:** `:core:data` (`LedgerExport`, `ExportWriter`), `:feature:settings`.

## Context

Law 25 gives the user a right to portability, and an app with no network permission cannot satisfy it by syncing anywhere.
The only way data leaves this device is a file the user asks for.

One format cannot serve both readers.
A spreadsheet is what the data is actually *for* — a person looking at their own spending — and it loses structure: merchants collapse to a name, aliases vanish, nothing round-trips.
A structured dump preserves everything and is unreadable to the person whose data it is.

Both are cheap.
Neither adds a dependency: `kotlinx.serialization` is already in the build, and the CSV is written by hand because a CSV library would be a dependency bought to avoid twenty lines.

## Decision

Two formats, written through the Storage Access Framework so that no storage permission is needed.

**CSV** is semicolon-delimited with a UTF-8 byte-order mark.
Both choices are forced by the target: on a French system the comma is the decimal mark, so a comma-delimited file splits `16,09` into two columns, and Excel without a BOM reads UTF-8 as a legacy codepage and mangles every accent.
Amounts are formatted by hand from minor units rather than by a locale, so the file does not change shape with the device's settings.

**JSON** is a lossless dump of transactions, merchants and aliases, carrying a `version` field and each transaction's `sourcePackage`/`sourceKey` identity, so that a future import can restore it and collapse duplicates the same way capture does.

**Scope settled during the build:** neither format includes the raw notification log.
The ledger, the merchants and the aliases are the data describing you.
The raw log is working evidence — it is the half you can delete without losing history, and exporting it would put verbatim lock-screen text into a file you might mail to yourself.

The matching **import** is not in this phase.
The format is designed for it now so that adding it later needs no migration.

## Consequences

Each format needs its own launcher, because the SAF contract fixes its MIME type at construction.
That is visible in the settings screen as two buttons rather than one with a chooser, which is also the clearer UI.

The JSON carries a `version`.
An import written later must handle version 1 or refuse it explicitly; silently guessing at an unknown shape is how a restore corrupts a ledger.

A file, once written, is outside every guarantee this app makes.
It sits in Downloads, it is readable by anything with storage access, and it is the one place the "everything stays on the device" promise ends by design rather than by accident.

## Where this lives in the code

- `core/data/src/main/kotlin/.../LedgerExport.kt` — both writers, the BOM, the separator, the hand-rolled decimal.
- `core/data/src/main/kotlin/.../ExportWriter.kt` — the SAF write and the suggested file name.
- `:feature:settings` `DataSection.kt` — the two launchers and the outcome line.
- `LedgerExportTest` asserts, among other things, that no raw-log field reaches either format.
