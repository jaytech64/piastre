# 23. A budget counts what a report counts, from the month it was set

- **Status:** Accepted — implemented.
- **Decided:** 2026-10-11, while planning Premium (decisions 7 to 9); the page itself on 2026-10-15 (decisions 13a to 13j).
- **Touches:** `:core:model` (`Budget.kt`), `:core:database` (`budget`, schema 20), `:core:data` (`BudgetRepository`, export and import), `:feature:reports` (the Budgets row, page and dialog, the ranking's bars).

## Context

Budgets are the first Premium feature.
A budget is a promise about one month's spending on a category or a group, and two questions decide whether a person can trust it: what counts against it, and what happens to a month already past when the amount changes.

Reports already answer the first question for the category ranking, and five ADRs shaped that answer.
A budget that counted differently would show one figure for Épicerie in the ranking and another on the Budgets page, for the same month.

## Decision

**A budget counts exactly what the category ranking counts, read from the same query.**
`BudgetRepository` reads `ReportRepository.categorySpending` and adds nothing of its own: money out and never income ([ADR-0007](0007-a-report-counts-spending-not-the-balance.md)), a split under each part's category ([ADR-0019](0019-a-part-of-a-split-can-go-into-another-account.md)), nothing waiting for review ([ADR-0002](0002-totals-exclude-unreviewed-rows.md)), nothing dated after today ([ADR-0015](0015-a-transaction-counts-from-its-day.md)), Canadian dollars only with the rest counted aside ([ADR-0012](0012-a-total-counts-one-currency.md)).
A group's spending is its categories' as they are grouped now, so moving a category moves its history ([ADR-0016](0016-a-categorys-group-is-read-through.md)).

**A budget is monthly, and a change applies from the month it is made.**
Each change is a row from its month on; a month is judged against the latest change on or before it.
So a past month keeps the amount it was judged against, and only the current month can be changed.
Stopping a budget is a change too: it ends the budget from that month and keeps it before.
Deleting a category or a group takes its budgets with it, history included.

A category and its group can both be budgeted; each is judged against its own amount, and nothing checks that the categories add up to the group.
There is no rollover, and no alert when a budget is passed.

**Budgets are Premium, and the data never is.**
Without Premium the Budgets row in Reports leads to the Premium page and the ranking's bars disappear, but every budget is kept, export writes them, import reads them, and erasure removes them.

## Consequences

The `budget` table names its target with a kind and an id rather than two nullable ids, because SQLite treats every null as different and a unique index over two nullable columns would let the same month be budgeted twice.

Export carries budgets within format version 4: a reader that skips them loses the budgets and nothing else.
Import never overwrites a month already budgeted on the phone.

## Where this lives in the code

- `core/model/.../Budget.kt` — `budgetsIn`, `leftPerDay`.
- `core/database/.../Entities.kt` — `BudgetEntity`; `PiastreDatabase.kt` — `MIGRATION_19_20`.
- `core/data/.../BudgetRepository.kt` — the counting rule.
- `feature/reports/.../BudgetsScreen.kt`, `BudgetDialog.kt`, `BudgetBits.kt`.
