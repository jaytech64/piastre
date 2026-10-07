# 17. A transfer counts by the accounts it joins

- **Status:** Accepted — implemented; amended by [ADR-0019](0019-a-part-of-a-split-can-go-into-another-account.md).
  Supersedes [ADR-0011](0011-a-transfer-under-a-category-is-money-that-left.md); amends [ADR-0006](0006-a-filtered-total-answers-for-one-account.md), [ADR-0007](0007-a-report-counts-spending-not-the-balance.md), [ADR-0008](0008-the-ledger-header-says-what-the-balance-is-made-of.md) and [ADR-0009](0009-a-report-says-what-its-balance-is-made-of.md).
- **Decided:** 2026-10-04, while planning a report's category ranking.
- **Touches:** `:core:model` (`Transaction.signedAmount`), `:feature:ledger` (`LedgerViewModel`, `SummaryFigures`), `:core:database` (`ReportDao`), `:core:data` (`ReportRepository`), `:feature:reports`.

## Context

A transfer was worth zero to the ledger as a whole, whatever it joined, because counting a card payment would charge you twice for the purchases it settles.
That is right for a card payment and wrong for a mortgage: money moved from a card into the mortgage account has left as surely as a purchase has, and no figure anywhere said so.

[ADR-0011](0011-a-transfer-under-a-category-is-money-that-left.md) patched this under a category filter, where any categorised transfer counted as money out.
That left two answers for one transfer: nothing on the whole ledger, its full amount under its category.
It surfaced when a report was to rank category groups.
A group holding the mortgage would have exceeded the *Dépenses* figure printed directly above it, and the maintainer refused that.

## Decision

**A bank account is outside what the ledger spends from.**
Money moved into one has left, money moved out of one has come back, and anything else moves nothing.

| From ↓ · Into → | Card | Cash | Bank |
|---|---|---|---|
| **Card** | 0 | 0 | money out |
| **Cash** | 0 | 0 | money out |
| **Bank** | money in | money in | 0 |

- Money out is counted **inside *Dépenses*** and money in **inside *Revenus***, on the unfiltered ledger and on a report.
  There is no *Virements* figure there, and the two figures still add up to the balance.
- **One rule everywhere.**
  A category or a group does not change what a transfer is worth, so a categorised card payment counts for nothing under its category, and an uncategorised transfer into a bank account is money that left under *No category*.
- **An account filter still decides first**, as [ADR-0006](0006-a-filtered-total-answers-for-one-account.md) says: a transfer moves that account by which end it is on, whatever the two accounts are, and shows as *Virements*.
- A transfer with an account missing counts for nothing, since nobody said where the money went.
- A report's count under *Dépenses* reads *transactions*, not *achats*, because it counts everything the figure does.

Rejected: only card → bank, as first asked.
Money brought back from savings would never undo the money that left for it.

Rejected: a *Virements* figure on the unfiltered header.
*Dépenses* would stay purchases alone, and a category group could still exceed it.

Rejected: netting money that came back against *Dépenses*.
The figure could fall below the purchases under it, or below zero, and a category's bar with it.

## Consequences

**Past months re-total.**
Nothing stored changes, but the rule is read when a total is drawn, so every month holding a transfer into or out of a bank account shows a different balance from the day this ships.
Changing an account's kind re-totals its whole history the same way.

**The rule trusts what is called a bank account.**
It is right for a mortgage, a loan or savings.
It double-counts if pay is recorded as income into a bank account and then moved to a card, and if a bill is paid as an expense from a bank account that a transfer funded.
The ledger reports the accounts as they were set up rather than second-guessing them.

***Revenus* is no longer only what was earned**, and its month-to-month comparison moves when savings are drawn on.

**Everything built on *Dépenses* follows it**: a report's calendar and comparison, the ledger's day totals and its selection total.

**The rule is written twice**, in Kotlin for the ledger and in SQL for a report.
A test totals every pair of account kinds through both and holds them to the same cent.

## Revision — 2026-10-07

[ADR-0019](0019-a-part-of-a-split-can-go-into-another-account.md) lets a part of a split expense go into another account.
That share is a transfer between the transaction's account and the part's, and the table above decides what it is worth, unchanged.
The SQL half of the rule now reads `MOVEMENTS`, each unsplit transaction and each part, rather than `expense_transaction` alone.

## Where this lives in the code

- `Transaction.signedAmount` in `core/model/src/main/kotlin/.../Transaction.kt` — the rule, and `TransferTest` for every pair.
- `summaryOf` and `movedOn` in `feature/ledger/src/main/kotlin/.../LedgerViewModel.kt` — which figure a transfer lands in, with and without an account filter.
- `MONEY_OUT` and `MONEY_IN` in `core/database/src/main/kotlin/.../Daos.kt` — the same rule for `ReportDao`.
- `` `a report and the ledger agree to the cent on every pair of accounts` `` in `ReportRepositoryTest`.
- `` `a transfer between cards moves nothing under its category either` `` and `` `an uncategorised transfer into a bank account is money that left with no category` `` in `LedgerFilterTest`.
