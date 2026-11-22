# 28. A captured purchase is dated by its first post

- **Status:** Accepted — implemented.
- **Decided:** 2026-11-22, after a purchase in the ledger showed the time of Wallet's last update.
- **Touches:** `:core:database` (`TransactionDao.insertOrUpdate`, `MIGRATION_22_23`, `RawNotificationEventDao.postTimesBehind`), `:core:data` (`TransactionRepository.captureHistory`), `:feature:ledger` (`CaptureHistorySheet`).

## Context

Wallet can publish the same purchase again with a fresh post time, and each re-post overwrote the row's time.
In the redacted captures, 3 of 17 purchases were re-posted 9, 19 and 35 minutes after the first notification.
The ledger therefore showed when Wallet last updated a purchase, and a re-post after midnight moved the purchase to the next day, or the next month.

The earlier times were never lost.
The raw log keeps each post's time after its 90-day content purge ([ADR-0001](0001-raw-log-90-day-purge.md)), because that time is what collapses a replay.

## Decision

**A captured purchase takes the time, zone and day of its earliest post.**
A later post updates what the parser owns and leaves the moment alone, and an earlier post delivered late by a listener rebind moves the purchase back to it.

**Existing purchases are repaired on upgrade, unless their time was set by hand.**
A row moves when its time is still exactly one of its own posts.
An edit that leaves the time alone saves it to the millisecond, so a purchase given a note or a category is repaired too, and a time picked by hand matches no post and stays.

**The form of a captured purchase opens its capture history**, from a history icon at the top right: *Capture history* / *Historique de capture*.
It lists the first capture and each update with its date and time, in the zone the purchase is dated in.
Once the raw log is cleared it says the history is no longer available, and a typed or imported purchase, which never had one, does not offer it.

Rejected: changing only the displayed time.
A row could then read 23:50 under the following day.

Rejected: listing your own edits in the history.
The app keeps only the last one, and keeping them all would be a new kind of stored data.

## Consequences

**A total can change on upgrade.**
A purchase whose re-post crossed midnight or a month's end returns to its first day, and the figures of both periods follow.

**The history lists distinct post times, not every delivery.**
Wallet often rewrites a notification about 3 seconds later under the same post time, which the raw log treats as the same post.
"Preparing your receipt" is left out, because nothing in it links to a purchase.

**A purchase restored from an export has no history**, because the raw log is never exported ([ADR-0003](0003-export-writes-csv-and-json.md)).

## Where this lives in the code

- `TransactionDao.insertOrUpdate` and `RawNotificationEventDao.postTimesBehind` in `core/database/.../Daos.kt`.
- `MIGRATION_22_23` in `core/database/.../PiastreDatabase.kt`.
- `TransactionRepository.captureHistory` in `:core:data`.
- `CaptureHistorySheet` and `FormTitle` in `:feature:ledger`.
- `CaptureHistoryTest` in `:core:data`, and `PiastreDatabaseTest` and `PiastreDatabaseMigrationTest` in `:core:database`.
