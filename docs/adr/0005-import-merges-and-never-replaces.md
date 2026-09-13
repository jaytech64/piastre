# 5. Import merges into the ledger and never replaces it

- **Status:** Accepted — implemented.
- **Decided:** 2026-09-12, while planning the import of a year of history from another app.
- **Touches:** `:core:data` (`LedgerImport`, `ImportReader`, `LedgerDocument`), `:core:database` (schema 9), `:feature:settings`.

## Context

ADR-0003 shaped the JSON export so that an import could be written later, and left the import out of that phase.
Two needs brought it forward at once: restoring your own export, which Law 25 portability implies, and bringing in history kept in another app.
The second is served by a converter outside the app that writes this app's own format, so the app only ever learns to read what it writes.

A restore can mean two different things.
It can replace the ledger with the file, or it can add the file to the ledger.
Replacing is simpler to explain, and it destroys whatever the phone captured after the file was written.

## Decision

An import adds to the ledger, and never removes or overwrites anything already in it.

- **Identity decides duplicates.** A transaction whose `sourcePackage` and `sourceKey` are already in the ledger is left as it is. Importing the same file twice changes nothing, and a purchase you have edited since is not reverted by an older copy of itself.
- **All or nothing.** The whole file is read, checked and summarised before anything is written, then written in one database transaction. A refused file, or a failure halfway, leaves the ledger as it was.
- **Only version 2.** Any other version is refused, as ADR-0003 requires. Fields added within version 2 are optional on the way in.
- **Accounts are found, not duplicated.** A Wallet card is matched on its issuer label and last four digits. Any other account is matched by name, ignoring case but not accents. An account found nowhere is created. When two of your accounts answer to the same name, the whole file is refused, because picking one would be a guess.
- **Merchants join the ones already here** by normalised name, and keep the name they already have.
- **Your opening balance wins.** An import fills in an opening balance nobody gave and keeps one you set, and the summary names every account where the file disagrees.
- **You confirm first.** Settings shows what the file would add and what it would leave alone before anything is written.

## Consequences

An import cannot delete.
A transaction removed after an export comes back if that export is imported again, and a restore cannot make the ledger match a file exactly.
That is the price of never losing what the phone recorded in the meantime.

There is no undo.
The confirmation says so and points at export as the way back.

Two rows describing one purchase under different identities are not detected.
An entry kept in another app and the Wallet notification for the same purchase look unrelated to the importer, so a conversion from another app has to cut its history at the day capture started rather than leave the app to guess.

A restored row points at no raw notification, because the raw log is never exported, and a row still waiting for review comes back without its review reason.

## Where this lives in the code

- `core/data/src/main/kotlin/.../LedgerImport.kt` — reading, planning, and the single transaction.
- `core/data/src/main/kotlin/.../ImportChecks.kt` and `ImportMapping.kt` — the version gate, every consistency check, and how the file's rows become entities.
- `core/data/src/main/kotlin/.../ImportReader.kt` — the Storage Access Framework read.
- `:feature:settings` `DataSection.kt` — the picker, the summary dialog, and the outcome line.
- `LedgerImportTest` round-trips an export between two databases and covers every refusal.
