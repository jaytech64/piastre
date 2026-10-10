# 21. Dépenses became Piastre, and the ledger crossed by hand

- **Status:** Accepted — implemented. Dépenses stays installed on the phone until Piastre has captured a real payment.
- **Decided:** 2026-10-09, while renaming the app.
- **Touches:** every module (packages, namespaces), `:app` (`applicationId`, `PiastreApplication`), `:core:database` (`PiastreDatabase`, `piastre.db`), `:capture:listener` (`PiastreListenerService`), `:core:data` (`ExportWriter`), `build-logic/`, `spike/`.

## Context

The app was called Dépenses, its repository `expense-tracker`, and its code lived under `io.github.jaytech64.depenses`.
None of the three names matched the others, and the app had outgrown all of them: it shows where the money went and what is left, not only what was spent.

The maintainer chose Piastre.
It is the dictionary spelling of the word a Quebecer says as *piasse*, it exists in English, and it is spelled the same in both languages.

A name on a launcher is a string.
The `applicationId` is not: Android files an app's data under it, and treats a build with another id as a different app.
The phone holds the only copy of the ledger, so the question was whether the id should follow the name.

## Decision

Everything takes the new name, the `applicationId` included.

- The app is **Piastre** in English and in French, on the launcher, in Android's notification access list and in the onboarding.
- The code lives under `io.github.jaytech64.piastre`, the database is `PiastreDatabase` in `piastre.db`, the listener is `PiastreListenerService`, and an export is named `piastre-<date>`.
- The `applicationId` is `io.github.jaytech64.piastre`, so Piastre installed **beside** Dépenses as a new, empty app, and nothing was updated in place.
- **The ledger crossed as its database file.** The three SQLite files were copied out of Dépenses with `adb run-as`, folded into one by a WAL checkpoint on the computer, and written into Piastre as `piastre.db` before Piastre was first opened.
- The French ledger tab keeps the label « Dépenses », and *Dépenses* stays the name of the spending figure. Neither was the app's name.

Rejected: keeping the old `applicationId` under the new name.
It was the recommendation, because an update in place cannot lose anything.
The maintainer preferred one name everywhere to an old id that would outlive its reason, and accepted a move by hand to get it.

Rejected: moving the ledger by JSON export and import.
It is the app's own path, and it was rehearsed.
It left the raw notification log behind, as [ADR-0005](0005-import-merges-and-never-replaces.md) says it must, and it also lost the review time of 145 transactions and left a second cash account beside the imported one.

## Consequences

**The move was checked three times before it was trusted.**
On the emulator with test data, then on the emulator with a copy of the phone's ledger, then on the phone, the database Piastre held was compared with the one Dépenses held, row by row across all eleven tables, and was identical each time.
On the emulator the JSON export of the two apps was also compared, and matched in every list.
Each copy taken from the phone was checked against checksums computed on the phone before and after, so it was one consistent snapshot.

**It only worked because both builds are debug builds.**
`adb run-as` reads and writes the private directory of a debuggable app and of nothing else.
A release build, should one ever exist, has no such door, and a move like this one would have to go through import.

**Android kept nothing that was filed under the old id.**
Notification access was granted again, to a listener Android had never seen.
The settings file did not cross, so onboarding ran once more and the per-app language had to be set again.

**The id is now the expensive thing to change.**
The Kotlin namespace can be renamed in an afternoon.
The `applicationId` cannot be changed without doing all of this again, and the next time there may be no debug build to do it with.

**Old names remain where they are history.**
Exports written before the rename are called `depenses-<date>` and still import, because import never reads the file name.
Commit messages and earlier ADRs say Dépenses where they meant the app; only their pointers to code were updated.

## Where this lives in the code

- `app/build.gradle.kts` — `applicationId` and `namespace`.
- `core/database/src/main/kotlin/.../PiastreDatabase.kt` — `NAME`, the file the ledger is kept in.
- `capture/listener/src/main/AndroidManifest.xml` — the listener's component name, which is what notification access is granted to.
- `core/data/src/main/kotlin/.../ExportWriter.kt` — `suggestedFileName`, and `` `an export is named for the app and the day it was written` `` in `LedgerExportTest`.
