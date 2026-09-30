# 13. A recurring transaction is written on its day, never projected

- **Status:** Accepted, revised twice — implemented.
- **Decided:** 2026-09-27, while planning recurring transactions (`.lavish/recurring-transactions.html`, forks A, B, D and F).
- **Touches:** `:core:model` (`Recurrence`), `:core:database` (`recurring_transaction`, schema 12), `:core:data` (`RecurringPosting`, `RecurringRepository`, export and import), `:feature:ledger`, `:feature:settings`, `:app`, `:capture:listener`.

## Context

Rent, pay and the monthly bills arrive by Interac, by direct deposit, or on a card whose charge Wallet never notifies.
Each was typed again every period, or forgotten, and the ledger was quietly short until it was.
One merchant can also send two kinds of bill — Internet and cellphone from the same provider — so a merchant's category could not file them apart.

Two things constrain any answer.
Nothing can wake this app on a given day: WorkManager declares a network permission the build refuses to ship (the `CaptureCatchUp` KDoc), and an alarm that survives a reboot needs the boot permission [ADR-0004](0004-capture-health-banner-without-a-notification.md) declined.
And every figure on screen counts only what has happened ([ADR-0002](0002-totals-exclude-unreviewed-rows.md), [ADR-0008](0008-the-ledger-header-says-what-the-balance-is-made-of.md)).

## Decision

**A recurring transaction writes an ordinary transaction into the ledger for each day it falls on, once that day has come, dated on that day.**
Before its day, it is nowhere: not in a total, a report, a filter or the list of rows.
After, it is a row like any other, with its own amount and category, which can be edited or deleted on its own.

**Days are caught up, not fired.**
Whenever the process starts — the app opening, or Android binding the listener — at each midnight while it lives, and when the listener rebinds, every day that has come is written.
A bill due on the 25th and first seen on the 27th is still a bill of the 25th.

**It counts at once.**
It is an amount you set up, so it is confirmed, like a row you typed; a bill that came in different is corrected on its row.

**A schedule's category is written onto each row it writes**, as a merchant's is ([ADR-0010](0010-a-merchants-category-is-written-onto-each-purchase.md)), and the merchant's does not override it — which is what files two bills from one provider apart.
Changing a schedule changes the rows still to come, never those already written.
Stopping one keeps every row it wrote.

**Only a typed transaction can repeat.**
A captured one will be captured again next time, and a schedule for it would count it twice.

Rejected: showing the days to come in the ledger, dimmed and counted nowhere.
It answers "what is about to leave", and it is the first thing on screen that would not add up with the figures above it.
Rejected: waiting for a captured purchase and giving it the schedule's category instead of writing a row.
It suits a bill Wallet does report, but it is a guess by nearness in date and amount, which the parser rules exist to refuse; it would be its own plan.

## Revision — the first day is a recurring row, and a schedule that is over goes

- **Decided:** 2026-09-29, after using it on the phone.

The transaction a schedule is made from had stayed a typed row, with its time, so the ledger said *recurring* under every day but the first.
It now becomes the schedule's first row as soon as the schedule is made: a recurring row keyed on the schedule and its day, like the rest.
Schema 13 does the same to the first rows of schedules made before it.

A schedule with no day left to write stayed in the list in Settings for ever.
It is now removed by the catch-up pass that finds it over, as *Stop repeating* would remove it: every row it wrote stays, and still reads as recurring.
"Over" means nothing left to write, so an end date that falls before the next due day ends it even while that end date is still to come.

## Revision — the next day is shown above today

- **Decided:** 2026-09-30, with [ADR-0015](0015-a-transaction-counts-from-its-day.md).

The ledger now shows each schedule's next day above today, counted in nothing, which reverses the first rejection above.
What that rejection feared was a line that doesn't add up with the figures over it.
The line is collapsed and set apart from the list, and says it's upcoming, so it isn't read as part of them.

Nothing else here changes: rows are still written only on their day.
A schedule that starts after today now waits for its first day too, rather than writing it on save.

## Consequences

**A row appears only once the app or its listener has run on or after its day.**
It is always dated right, so the ledger is right whenever it is read, which is the only time it can be read.
No notification says it was written: that would need the permissions this record, and ADR-0004, decline.

**A bill whose amount varies counts at the amount set until corrected.**
A waiting state for such bills is a possible follow-up, and would need its own rule beside ADR-0002.

**A start in the past writes a row for every day since**, after saying how many and asking.
Rows already typed by hand for those days are not detected, and would be doubled if agreed to.

**A day is written once.**
The row and the cursor past its day are written in one database transaction, and each row is keyed on its schedule and day in the transaction table's unique index, so two passes racing over a day write it once, and a row you delete is never written again.

## Where this lives in the code

- `Recurrence` in `:core:model` — the days a schedule falls on, counted from its first day so a bill on the 31st keeps coming back to the 31st.
- `RecurringTransactionEntity` and `MIGRATION_11_12` in `:core:database` — the schedule and its `nextDueEpochDay` cursor.
- `RecurringPosting` in `:core:data` — the catch-up pass, and `occurrenceOn`, the row a day writes; called from `DepensesApplication` and `DepensesListenerService`.
- `RecurringRepository.repeat`, which refuses a captured transaction or one already on a schedule, and `change`, which never touches a written row.
- `RecurringRepositoryTest`, and `ExpenseFormRepeatTest` in `:feature:ledger`.
