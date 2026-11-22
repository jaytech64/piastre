# 29. A merchant report counts what a report counts, merchant by merchant

- **Status:** Accepted — implemented.
- **Decided:** 2026-11-22, while planning Premium idea 4 (forks A to G).
- **Touches:** `:core:database` (`MOVEMENTS` carries the merchant, `ReportDao.merchantTotals`), `:core:data` (`ReportRepository.merchantSpending`), `:feature:reports` (the Merchants row, page and view model), `:feature:ledger` (a ledger opened on a merchant), `:feature:premium` (the benefit).

## Context

The merchant report is the third Premium feature: every merchant money went out at over a span, with how many visits and what a visit cost on average.
It puts on screen, by merchant, a figure that the category ranking already puts on screen by category, one tap away.
If the two counted apart, a month's merchants would add up to something other than what the report says the month spent.

A merchant also raises questions a category never did.
A merchant can pay you as well as sell to you ([ADR-0024](0024-a-merchant-gives-each-kind-its-own-category.md)), a transfer names no merchant, and an average needs a count of something.

## Decision

**A merchant's figure is what a report counts, grouped by merchant.**
`ReportDao.merchantTotals` reads `MOVEMENTS` and `MONEY_OUT`, as every report figure does.
So spending is money out and never income, a refund recorded as income at a merchant included ([ADR-0007](0007-a-report-counts-spending-not-the-balance.md)); a split counts by its parts, so cash back at the till is not spent there ([ADR-0019](0019-a-part-of-a-split-can-go-into-another-account.md)); nothing waiting for review counts ([ADR-0002](0002-totals-exclude-unreviewed-rows.md)); nothing dated after today counts ([ADR-0015](0015-a-transaction-counts-from-its-day.md)); and only Canadian dollars count, with the rest said in a footnote ([ADR-0012](0012-a-total-counts-one-currency.md)).
A merchant's name is joined, never copied, so renaming a merchant renames its history.

**What went out with no merchant is said under the list, never ranked.**
That is a typed purchase left without one, and every transfer entered as its own row that counts as spending, such as money moved into a savings account.
The merchants and that line add up to the report's spending for the same days, as the category ranking and its line for what has no category do.

A share of a split that goes into a bank account counts at the split's merchant, as the capital of a mortgage payment counts at the lender: the report counts it as spending, and it was paid there.
Only a transfer entered as its own row names no merchant.

**A visit is one transaction.**
Two purchases at one merchant on one day are two visits, and a split is one visit whatever its parts.
The average is the merchant's total over its visits, rounded half away from zero to the cent, in minor units throughout.

**The span is a calendar month or a calendar year.**
The page opens on the month the report was on, steps back without limit and forward only to the span you are living in.
A day or a week is not offered: a merchant visited once a fortnight has no average over either.

**Every merchant is listed**, ranked by total, by visits or by average purchase, ties falling to the total and then the name.
A merchant opens the ledger on itself over the span.

**Nothing is stored.**
What is chosen on the page lives in saved state, so there is no migration, no export change and no erasure change.

Rejected: netting refunds against purchases, which would make a merchant's figure a balance, able to change sign, beside a ranking of spending.
Rejected: a visit as a day with any purchase, which hides a second trip on the same day.
Rejected: a median purchase, sturdier against one large purchase but harder to say in four words on a phone.
Rejected: ranking "No merchant" as a row, which would put a savings transfer at the top of a list of shops.

## Consequences

**A merchant report and a report cannot disagree about a span.**
`` `the merchants and what has none add up to what the report of the same days spent` `` in `MerchantSpendingTest` holds them together, total and count.
A change to what a report counts reaches `merchantTotals` as long as it keeps reading `MOVEMENTS` and `MONEY_OUT`.

**A savings habit shows as money with no merchant.**
On a ledger that moves money into a bank account every month, the line under the list can be larger than the top merchant.
It is the truth of what the report counts, and the line says what it is.

**A merchant split across chains is two merchants.**
The ranking is only as good as merchant resolution; two spellings that resolve to two merchants rank apart until they are merged.

## Where this lives in the code

- `MOVEMENTS`, `MerchantTotal` and `ReportDao.merchantTotals` in `core/database/src/main/kotlin/.../Daos.kt`.
- `ReportRepository.merchantSpending` and `MerchantSpending` in `core/data/src/main/kotlin/.../ReportRepository.kt`.
- `MerchantSpan`, `merchantRanking` and `averageOver` in `feature/reports/src/main/kotlin/.../Merchants.kt`; `MerchantsViewModel`, `MerchantsScreen`, and `MerchantsRow` in `ReportsScreen.kt`.
- `OPENED_ON_MERCHANT` in `feature/ledger/src/main/kotlin/.../LedgerFilters.kt`.
- `MerchantSpendingTest` and `MerchantsViewModelTest`.
