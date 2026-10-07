# 12. A total counts one currency

- **Status:** Accepted — implemented.
- **Decided:** 2026-09-27, while planning the selection's total (fork D).
- **Touches:** `:feature:ledger` (`LedgerViewModel`, `SummaryFigures`), `:feature:reports` (unchanged, already followed it).

## Context

Google Wallet posts a purchase made in US dollars with `US$`, and the parser records it as `USD`.
`Money` refuses to add two currencies, and the ledger added every row of a day and of a month into one figure.
One USD purchase confirmed beside a Canadian one therefore stopped the ledger from loading.

Converting is not available: it needs a rate, a rate needs the network, and this app has none.
Reports had already answered the question for its own figures: count Canadian dollars, and say beside the figures how many transactions were left out.
The ledger had no answer, and the selection total being planned needed one.

## Decision

**Every total in the app counts Canadian dollars and says how many rows in another currency it left out.**
The ledger's month headline and its figures, a day's total, a report and the selection's total all follow the one rule.

The note sits under the figures wherever there is room for it: under the month's figures, under a selection's, and in a report's footnotes.
A day header has no room for one and counts Canadian dollars only; the row in another currency still shows its own amount, with its currency tagged, right under it.

Rejected: one figure per currency, everywhere.
Nothing would be left out, but every total grows a second line, the figures under a balance can no longer add up to one number ([ADR-0008](0008-the-ledger-header-says-what-the-balance-is-made-of.md)), and Reports would still follow the other rule.

## Consequences

**A total can understate, and says so.**
This is the trade [ADR-0002](0002-totals-exclude-unreviewed-rows.md) already made for rows awaiting review: a number that is too low and says why on the same screen is recoverable.

**A month spent only in another currency reads 0,00 $** with the note under it, not a figure in that currency.
The zero is always Canadian, rather than taking the currency of whichever row came first.

**The home currency is Canadian dollars, fixed.**
A setting to choose it would be a new decision, and would have to move Reports' queries with it.

## Where this lives in the code

- `inHomeCurrency` in `feature/ledger/src/main/kotlin/.../LedgerViewModel.kt` — the rows a ledger total may add; every total on that screen goes through it.
- `BalanceSummary.otherCurrencyCount` and `SummaryFigures` — the count and its note.
- `ReportRepository` and `otherCurrencyCount` in `:feature:reports` — the same rule, in SQL.
- `` `a purchase in US dollars is left out of the totals and counted apart` `` in `LedgerViewModelTest`.
