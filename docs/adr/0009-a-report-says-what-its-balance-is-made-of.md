# 9. A report says what its balance is made of

- **Status:** Accepted — implemented.
  Amends [ADR-0007](0007-a-report-counts-spending-not-the-balance.md); amended by [ADR-0017](0017-a-transfer-counts-by-the-accounts-it-joins.md), under which a transfer is no longer always worth zero to a report and the count reads *transactions*.
- **Decided:** 2026-09-25, while planning the reports header (`.lavish/reports-balance-header.html`).
- **Touches:** `:core:database` (`ReportDao`), `:core:data` (`ReportRepository`), `:core:designsystem` (`BalanceFigures`), `:feature:reports`.

## Context

[ADR-0007](0007-a-report-counts-spending-not-the-balance.md) made a report count spending alone, with the word *dépensé* under its headline to separate it from the ledger's balance.
It named the cost itself: someone who reads the two numbers without reading the word between them will think one of them is a bug.
It also said that a screen reporting income "would be a second screen, not a column on this one".

[ADR-0008](0008-the-ledger-header-says-what-the-balance-is-made-of.md) then gave the ledger a header showing the balance with *Revenus* and *Dépenses* under it.
Once that header existed, the maintainer wanted it on the reports screen too, without losing the report's purchase count and its comparison with the previous period.

The first proposal was a carousel with three pages: the balance (the default), spending, and a new earnings page.
It was set aside for four reasons.
The default page would not be the figure the ranking and the calendar break down.
An earnings page has nothing to say on most days and weeks.
Pages behind a swipe get missed.
And pages of different heights either jump or pad at large font sizes.

## Decision

The reports header becomes the ledger's: **Solde** on top, then **Revenus** and **Dépenses** under it, all shown at once.

- The balance is income less spending over the period: confirmed rows in the report currency, with a transfer worth zero.
  For a calendar month it is the ledger's unfiltered *Solde du mois*.
- The purchase count and the comparison with the previous period sit **under Dépenses**, so they are visibly about spending.
- Income is compared with the previous period **on months only**, in a neutral colour.
- The ranking and the calendar are unchanged: they still break down spending, which is now the labelled figure directly above them rather than the headline.
- A period with income and no spending shows both figures, with *Rien de dépensé* under Dépenses.
  Only a period where nothing happened shows *Rien dans cette période* alone.
- The footnote about other currencies counts income as well as purchases, and says *transactions*.

Rejected: the carousel, for the reasons above.
Rejected: an earnings page, or a lens that switches the ranking and the calendar to income.
The lens only earns its place once income rows have payers worth ranking.

## Consequences

**The two screens agree on the numbers they share.**
For a month, the report's Solde, Revenus and Dépenses are the three figures the unfiltered ledger header shows.
The cost ADR-0007 accepted — two numbers for one month, told apart by a single word — is gone.

**The headline no longer describes the breakdown below it.**
This is what ADR-0007 guarded against.
The mitigation is placement: Dépenses is a labelled figure with its count and comparison, directly above the ranking it adds up to.

**Income's comparison has a calendar artefact.**
On pay every two weeks, about two months a year hold three paycheques, and they read about ▲ 50 % followed by about ▼ 33 %, with no change in pay.
It is a true statement about the money that arrived, which is why it is shown, and it is drawn in neutral grey so that it reads as neither good nor bad news.
Weeks and days are not compared at all, because there almost every other period would read ▲ ∞ or ▼ 100 %.

**The card is taller by one row of figures.**
At 200 % font the figures fall back to one line each, and the ranking's heading still shows on a Pixel 10 with the capture banner open.

**ADR-0007's reasoning about the breakdown still stands.**
The ranking, the calendar and the comparison under Dépenses count expenses alone, for the reasons that record gives.
What changed is only which figure heads the screen.

## Where this lives in the code

- `ReportDao.incomeForRange` and `otherCurrencyCount` in `core/database/src/main/kotlin/.../Daos.kt`.
- `ReportRepository.income` in `core/data/src/main/kotlin/.../ReportRepository.kt`.
- `BalanceFigures` in `core/designsystem/src/main/kotlin/.../BalanceFigures.kt` — the layout shared with the ledger header, and its large-font fallback.
- `ReportsUiState.balance` and `incomeComparison` in `feature/reports/src/main/kotlin/.../ReportsViewModel.kt`, and `BalanceCard` in `PeriodHeader.kt`.
- `` `a period's balance is what came in less what was spent` ``, `` `a card payment moves a report's balance by nothing` ``, `` `a payday with no spending is not an empty period` `` and `` `income is compared month to month and never week to week` `` in `ReportsViewModelTest`.
