# 14. A dated filter's headline is the balance of its dates

- **Status:** Accepted, revised once — implemented.
- **Decided:** 2026-09-29, while planning the amount, note and date filters (fork F2).
- **Touches:** `:feature:ledger` (`LedgerViewModel`, `LedgerScreen`).

## Context

The ledger's headline has always been this month's balance.
A filter by account, category or merchant narrows it, and it stays this month's: *Visa · this month*.

A filter by dates breaks that.
With *Jan 1 – Mar 31* on, the list below shows three months of rows while this month's balance of them reads 0,00 $.
The headline would contradict the screen it heads.

## Decision

**Under a filter with dates, the headline is the balance of every row shown; without dates, it stays this month's.**
The label says which: *This month · 2 filters*, *Since Sep 1 · 2 filters*, *Sep 1 – 30*.

The figures under it follow, so they still add up to it ([ADR-0008](0008-the-ledger-header-says-what-the-balance-is-made-of.md)), and every rule a total already follows — rows awaiting review left out, transfers signed by the filtered account's end, other currencies counted apart — carries over unchanged.

Rejected: always this month.
No change to the header, but the contradiction above.

Rejected: every row shown, whenever any filter is on.
One rule instead of two, but a Visa filter's headline would jump from this month to every month the card was ever used.

## Consequences

**The headline answers two kinds of question.**
The label is what tells them apart, so it always names the period, and never only the filters.

**An open end runs to the edge of the ledger.**
*Since Sep 1* counts a purchase dated ahead of today, the way this month's headline already does.

## Revision — the open end stops at today

- **Decided:** 2026-09-30, with [ADR-0015](0015-a-transaction-counts-from-its-day.md).

The consequence above, that *Since Sep 1* counts a purchase dated ahead of today, no longer holds.
A row dated after today is kept out of the list and waits above today, so the headline, which is still every row shown, no longer counts it.
The decision itself is unchanged.

## Where this lives in the code

- `headlineRows` in `feature/ledger/src/main/kotlin/.../LedgerViewModel.kt` — the rows the headline adds up.
- `balanceLabel` in `LedgerScreen.kt` — the period the label names.
- `` `under a date filter the headline counts every day shown, not this month` `` and `` `without dates a filtered headline is still this month's` `` in `LedgerFilterTest`.
