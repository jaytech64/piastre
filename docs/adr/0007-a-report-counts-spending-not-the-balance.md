# 7. A report counts spending, not the balance

- **Status:** Accepted — implemented; amended by [ADR-0009](0009-a-report-says-what-its-balance-is-made-of.md), which puts the balance at the head of the screen while the breakdown stays spending, and by [ADR-0017](0017-a-transfer-counts-by-the-accounts-it-joins.md), which counts a transfer into a bank account as spending.
- **Decided:** 2026-09-20, while planning Phase 3 (`.lavish/phase-3-reports.html`).
- **Touches:** `:core:database` (`ReportDao`), `:core:data` (`ReportRepository`), `:feature:reports`.

## Context

`ReportDao` was written in Phase 2, before the ledger knew about income, accounts or transfers.
Its three queries have filtered `kind = 'EXPENSE'` since the day they were written, and nothing had ever read them.

By the time the reports screen was built, the ledger had grown a headline of its own: a month balance that adds income, subtracts expenses, and counts a transfer as zero ([ADR-0006](0006-a-filtered-total-answers-for-one-account.md)).
So Phase 3 had to answer a question that did not exist when the queries were written: **does a report answer the same question the ledger answers?**

It cannot avoid the question, because both screens put a number for September on the phone, one tap apart, and the two are different by every paycheque of the month.

## Decision

A report counts expenses alone.
Income is not added, a transfer is not counted, and the headline is labelled *dépensé* — **spent**, not *balance*.

The queries are unchanged; the decision is to keep them and to say out loud what they mean.

## Consequences

**Two numbers for the same month, and that is deliberate.**
The ledger says what happened to your money; a report says where your money went.
The word under the headline is the only thing separating them on screen, which is why it is not decorative and must not be dropped in a layout pass.

**The three figures on the screen agree by construction.**
The headline, the merchant ranking and the calendar heat are all built from the same rows.
Had the headline been net, the ranking below it would have been a ranking of a different set of transactions than the total above it — and a week's colour would swing on a payday rather than on spending.

**The comparison stays interpretable.**
"▲ 12 % vs la semaine dernière" means something only while the quantity has one sign.
A net figure can flip sign between two weeks, and a percentage change across a sign flip is not a fact about anything.

**Income is not lost, only absent here.**
It is in the ledger, in every account balance, and in the export.
A screen that reported it would be a second screen, not a column on this one.

**What this costs.**
Someone who reads both numbers without reading the word between them will think one of them is a bug.
That is the real price, it was accepted knowingly, and this record is half of the mitigation.

## Revision — 2026-10-05

The ranking is no longer of merchants.
A report ranks the five heaviest category groups, with a category in no group ranking beside them, and a group opens in place onto its categories (`.lavish/report-top-categories.html`).
It is still built from the rows the figure above it counts, so the three figures still agree by construction; what went out with no category is said in a line under the ranking rather than left as a gap.

## Where this lives in the code

- `ReportDao` in `core/database/src/main/kotlin/.../Daos.kt` — four queries, each filtering `status = 'CONFIRMED' AND kind = 'EXPENSE'`.
- `ReportRepository` in `core/data/src/main/kotlin/.../ReportRepository.kt` — the door features read them through.
- `SpendingCard` in `feature/reports/src/main/kotlin/.../PeriodHeader.kt` — the word *dépensé*, and a comment saying why it is load-bearing.
- `` `a paycheque is not spending, and neither is moving your own money` `` in `ReportsViewModelTest`, and the same assertion one layer down in `ReportRepositoryTest`.
- [ADR-0006](0006-a-filtered-total-answers-for-one-account.md) — the ledger's side of the same question.
