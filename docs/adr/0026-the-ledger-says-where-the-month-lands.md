# 26. The ledger says where the month lands

- **Status:** Accepted — implemented; amends [ADR-0015](0015-a-transaction-counts-from-its-day.md).
- **Decided:** 2026-11-11, after a tester read the header as a bug.
- **Touches:** `:feature:ledger` (`LedgerViewModel`, `LedgerScreen`), `:core:data` (`RecurringRepository.rowsAfter`).

## Context

The ledger's headline was labelled *This month's balance* / *Solde du mois* and counted nothing dated after today ([ADR-0015](0015-a-transaction-counts-from-its-day.md)).
With one pay received yesterday and the next one waiting on the 23rd, the header said income $950.00 under a line above today saying +$950.00 more, and a tester read the missing $1,900.00 as a bug.
The label promised the whole month, and the figure was the month so far.

The first idea was to count what is upcoming in the header while its line is open, and stop counting it once the line is closed.
It would have made the same label carry two numbers depending on a disclosure control far from it, and that control starts closed each time the app starts, so the header would change between launches without anyone touching it.
The line above today also holds rows from later months, so adding its total to the month's balance would have been wrong as soon as anything past the month's last day was waiting.

## Decision

**The headline says it is the balance so far**: *Balance so far this month* / *Solde du mois à ce jour*, and *So far this month* / *Ce mois-ci à ce jour* in front of a filter count.
Its figures are unchanged.

**Under the figures, set apart by a divider, a line says where the month lands**: *Expected by 30 November* / *Prévu au 30 novembre*, then the balance once every day still to come this month has come.
It is the headline's rows plus every row dated after today and up to the month's last day, counted as the headline is: by the filter, in Canadian dollars, transfers by the accounts they join.

**A recurring transaction counts on every day it falls on until the month's end**, not only its next day.
The line above today still lists only each schedule's next day, so early in the month the expected balance can count a weekly or biweekly row that line does not show; the figure says *by the 30th*, and only counting every day keeps that true.

**It is shown only when something is still to come this month**, where it would otherwise repeat the headline, and **never under a filter with dates**, whose dates are the period rather than the month ([ADR-0014](0014-a-dated-filters-headline-is-the-balance-of-its-dates.md)).

Rejected: counting the upcoming line in the header while it is open, for the reasons above.
Rejected: an upcoming amount beside each of Income and Expenses.
Two numbers in each cell is the first thing to break at 200 % in French, where the header is already tall ([ADR-0008](0008-the-ledger-header-says-what-the-balance-is-made-of.md)).
Rejected: counting only what the line above today lists.
It would undercount every schedule that comes back within the month, and the date in the label would be wrong.

## Consequences

**ADR-0015's rule now has one exception, and it is labelled.**
A row dated after today still counts in no figure until its day — headline, day, selection, report, account balance — except this one line, which says by its label that it is a forecast.
Income and expenses stay what has happened, so the figures under the headline still add up to it.

**The expected balance and the line above today can disagree**, and are meant to: the line counts everything waiting, December's rent included, and the expected balance only this month, every day of a schedule included.

**Rows in another currency are left out without a count**, as the line above today already does; the headline's own line saying how many it left out still stands under the figures.

## Where this lives in the code

- `expectedByMonthEnd` in `feature/ledger/src/main/kotlin/.../LedgerViewModel.kt`, which `LedgerUiState.expectedByMonthEnd` carries.
- `RecurringRepository.rowsAfter`, every row a schedule will write between two days, built by the same `occurrenceOn` that writes them.
- `ExpectedBalance` in `LedgerScreen.kt`.
- `LedgerExpectedTest` in `:feature:ledger`.
