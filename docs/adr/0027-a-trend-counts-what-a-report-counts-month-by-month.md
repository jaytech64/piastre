# 27. A trend counts what a report counts, month by month

- **Status:** Accepted — implemented.
- **Decided:** 2026-11-16, while planning Premium idea 3 (forks A to K); what a comparison opens on on 2026-11-21, after running it on a real ledger.
- **Touches:** `:core:model` (`DaySpan`), `:core:database` (`ReportDao.monthlyTotals`, `firstConfirmedDay`), `:core:data` (`ReportRepository.monthlySpending`, `firstDay`), `:feature:reports` (the Trends row, page and view model).

## Context

Trends is the second Premium feature: one category, one group, everything, or what has no category, over twelve months beside the year before, and any two spans of days side by side.
It puts on screen a figure for every month that a report and a budget already put on screen for one month at a time.
If the two were counted apart, Groceries in October would read one amount in the October report and another on the October bar, one tap apart.

A year of months also raises two questions that a single period never did.
Most months a new subscriber sees come before the ledger's first row.
And the month you are living in is never complete.

## Decision

**A month of a trend is what the ranking of that month counts for the same thing.**
`ReportDao.monthlyTotals` reads `MOVEMENTS` and `MONEY_OUT`, as every report figure does, and groups by calendar month in SQL.
So spending is money out and never income ([ADR-0007](0007-a-report-counts-spending-not-the-balance.md)), a transfer counts by the accounts it joins ([ADR-0017](0017-a-transfer-counts-by-the-accounts-it-joins.md)), a split counts by its parts ([ADR-0019](0019-a-part-of-a-split-can-go-into-another-account.md)), nothing waiting for review counts ([ADR-0002](0002-totals-exclude-unreviewed-rows.md)), nothing dated after today counts ([ADR-0015](0015-a-transaction-counts-from-its-day.md)), and only Canadian dollars count, with the rest said in a footnote ([ADR-0012](0012-a-total-counts-one-currency.md)).
A group's months are its categories' as they are grouped now, so moving a category moves its months too ([ADR-0016](0016-a-categorys-group-is-read-through.md)).
The query filters on what it is asked about rather than adding up per-category rows, so a split across two categories of one group counts as one of that group's transactions.
A comparison of two spans reads `categorySpending` once per span and adds nothing of its own.

**A month before the ledger's first confirmed row is *not yet*, never zero.**
It has no bar, it is said in words over the chart, and it stays out of the average.
A month after that row with nothing spent is zero, and is drawn as zero.

**The running month is counted so far and drawn as an outline.**
It stays out of the average, which is of complete months only, and it is not compared with the whole of the same month a year before.
Either would make a figure fall every day until the 1st.

**A comparison opens on the same days of each month.**
This month so far stands against the same days of last month, or of the same month a year earlier, up to the last day of a shorter month.
Spans that are the same days of their own months compare their totals, whole months of different lengths included, because a bill paid once a month falls in both.

**Any other two spans of different lengths are compared per day.**
Both totals are shown as they are, and the change compares what went out per day, with a line under the table saying so.

**A figure that did not move says so.**
Exactly the same amount reads *No change* / *Aucun écart*, and a move under a whole percent reads `▲ < 1 %`, both in a neutral colour, on every screen that compares.

**A budget shows on the months it judged.**
Each month's tick is the amount `budgetsIn` gives for that month, so a change shows as a step from the month it was made ([ADR-0023](0023-a-budget-counts-what-a-report-counts-from-the-month-it-was-set.md)).

**Nothing is stored.**
What is chosen on the page lives in saved state, so there is no migration, no export change and no erasure change.

Rejected: counting income and the balance as subjects too.
Income swings with the months that hold three paydays ([ADR-0009](0009-a-report-says-what-its-balance-is-made-of.md)), a balance can change sign, and a percentage change across a sign change means nothing.
Rejected: drawing the months before the ledger as zero, which would divide three months of spending by eleven.
Rejected: opening on the whole of last month against this month so far, per day.
On a real ledger, a bill of the same amount in both months read ▲ 93 % in the middle of the month.
Rejected: a chart library, which would be a dependency for one screen; the bars are drawn on a Compose `Canvas`.

## Consequences

**A trend and a report cannot disagree about a month.**
`` `a month of a trend is what the report of that month counts, whatever it is on` `` in `ReportRepositoryTest` holds the two queries together for each kind of subject.
A change to what a report counts has to reach `monthlyTotals` too, which it does as long as both keep reading `MOVEMENTS` and `MONEY_OUT`.

**A new subscriber's first view is mostly empty.**
Until a ledger is a year old, the twelve months show *not yet* over the months it does not reach, and the year before shows nothing.
Comparing two spans is the part that is useful from the first day.

**Spans you pick of different lengths still read per day.**
That keeps a half month from looking like a fall nobody made, and it makes a bill paid once a month look higher in the shorter span, which the line under the table is there to explain.

## Where this lives in the code

- `ReportDao.monthlyTotals` and `firstConfirmedDay` in `core/database/src/main/kotlin/.../Daos.kt`.
- `ReportRepository.monthlySpending`, `firstDay` and `SpentOn` in `core/data/src/main/kotlin/.../ReportRepository.kt`.
- `trendYear`, `compared` and `sameDaysOfTheirMonths` in `feature/reports/src/main/kotlin/.../Trends.kt`, `comparisonOf` in `Comparison.kt`, and `movement` in `ComparisonText.kt`.
- `TrendsViewModel`, `TrendsScreen`, `TrendChart` and `TrendsCompareTab` in `feature/reports/src/main/kotlin/.../`.
- `TrendsViewModelTest`, and the monthly tests in `ReportRepositoryTest`.
