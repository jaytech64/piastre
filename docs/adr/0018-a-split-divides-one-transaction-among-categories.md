# 18. A split divides one transaction among categories

- **Status:** Accepted — implemented.
- **Decided:** 2026-10-06, while planning split transactions (forks A to I).
- **Touches:** `:core:model` (`TransactionPart`, `Transaction.parts`), `:core:database` (`TransactionPartEntity`, `TransactionPartDao`, `MIGRATION_16_17`, `ReportDao.categoryTotals`, `CategoryDao`), `:core:data` (`settleCategory`, `TransactionRepository`, `TransactionEditor`, `LedgerExport`, `LedgerImport`), `:feature:ledger`.

## Context

Wallet reports one purchase at Costco, and a transaction carries one category.
Half of that purchase was groceries and the rest was for the house, so the category report was wrong by whichever half lost.

The maintainer asked for split transactions as MyExpenses has them.
There a split is a parent transaction with child transactions beside it in the same table, under a reserved *Split transaction* category.

Every balance in this app reads `expense_transaction`: the ledger's headline and day totals, an account's balance, a report's spending, its calendar and its comparison.
Children stored beside their parent would have to be left out of each of those, one query at a time, and one forgotten would count a purchase twice.

## Decision

**A split is still one transaction. Its row keeps the whole amount, and its parts, in a table of their own, only say what the amount was for.**

- A part holds an amount, a category and an optional note.
  The account, the day, the merchant and the kind are the transaction's.
- Two or more parts must add up to the transaction's amount to the minor unit, and Save waits until they do.
  A single part divides nothing: the transaction then takes that part's category and is not split.
- While a transaction has parts its own `categoryId` is null.
  A part may have no category, which is a share nobody has labelled yet, counted under *No category* like any other.
- Any transaction can be split: typed or captured, expense, income or transfer.
  Splitting a captured row is a ruling like any other edit, so it stamps `reviewedAtUtcMillis` and a Wallet re-post or a parser fix leaves it alone.
- Every figure counts a split whole, as before, except the ones that read a category:
  - a report's spending by category and by group counts each part under its own category, so the rows still add up to the period's spending;
  - a ledger narrowed to a category or a group shows a split at the amount of its matching parts, with the whole it is a share of under it, so the ledger a report opens adds up to what the report said;
  - a category's use — its count in Settings, on the filter page, and its place in the most-used order — counts a split once for each category it touches.
- A write that gives rows a category in bulk passes over a split: the merchant's and the account's reach, a selection categorised by hand, and a re-post taking its merchant's category.
  The categorise sheet says how many selected splits will stay as they are.
- A split teaches its merchant nothing, is not offered to repeat, and is not kept as a template.
- The CSV gives a split one line per part, each with the transaction's day, merchant and account, so the amount column still adds up to the ledger.
  The JSON carries the parts under their transaction and becomes format version 3.

Rejected: child rows in `expense_transaction`, as MyExpenses stores them.
The reason is in the context: every total would need to know about them.

Rejected: a reserved *Split* category on the row.
It would appear in the category list, the filters and the export as if it were a kind of spending.

Rejected: saving what is left as a part with no category, without asking.
It never blocks a save, but a typo in one part would hide in *No category*.

Left for later: a part that is a transfer to another account, which the maintainer wants for a mortgage payment — interest spent, capital moved.
It needs a nullable destination account on the part and a third term in `AccountDao.allWithCounts`, and changes no whole-ledger figure, since interest and capital into a bank account both already count as money out ([ADR-0017](0017-a-transfer-counts-by-the-accounts-it-joins.md)).
Also left for later: a split on a recurring transaction or a template, and a tax helper on a part.

## Consequences

A report's category rows no longer add up to the period's *count* of transactions, since a split is counted under each category it touches.
Their amounts still add up to the cent.

Until a part can be a transfer, a mortgage payment split into interest and capital reads correctly in the category report, and moves the mortgage account's balance by the whole payment.

`categoryId IS NULL` on a row no longer means on its own that nobody has said what it was for.
Anything new that fills in missing categories has to pass over a row with parts, as `NOT_SPLIT` in `Daos.kt` does for the writes that exist.

A version-2 reader skips keys it does not know, so it would have imported a split as an uncategorised purchase without a word.
Format version 3 makes it refuse; this app reads both.
An import still never changes a transaction it already holds ([ADR-0005](0005-import-merges-and-never-replaces.md)), so parts arrive only with a transaction that is new here.

A dismissed captured row keeps its parts, as it keeps everything else, and they are in no figure because the row is not.

## Where this lives in the code

- `TransactionPart`, `divide` and `leftOf` in `core/model/src/main/kotlin/.../TransactionPart.kt`, and `TransactionPartTest`.
- `settleCategory` in `core/data/src/main/kotlin/.../SettledCategory.kt` — the one place that decides whether a category goes on the row or among parts.
- `MIGRATION_16_17` in `core/database/src/main/kotlin/.../DepensesDatabase.kt`, and `` `migrating to schema 17 keeps the ledger and splits nothing` `` in `DepensesDatabaseMigrationTest`.
- `ReportDao.categoryTotals`, `CATEGORISED` and `NOT_SPLIT` in `core/database/src/main/kotlin/.../Daos.kt`; `` `a split goes to each category by its parts, and the categories still add up to the period` `` in `ReportRepositoryTest`.
- `LedgerFilter.shown` in `feature/ledger/src/main/kotlin/.../LedgerViewModel.kt`, and `LedgerSplitTest`.
- `SplitParts` and `SplitSection` in `feature/ledger`, and `ExpenseFormSplitTest`.
