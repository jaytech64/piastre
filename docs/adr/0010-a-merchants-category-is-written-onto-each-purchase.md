# 10. A merchant's category is written onto each purchase, not read through

- **Status:** Accepted — implemented.
- **Decided:** 2026-09-26, while planning categories.
- **Touches:** `:core:database` (`MerchantEntity`, `AccountEntity`, `CategoryDao`, `TransactionDao.insertOrUpdate`), `:core:data` (`CategoryRepository`, `ParsePipeline`, `TransactionEditor`, `ReviewRepository`), `:feature:ledger`.

## Context

Transactions gained a category.
The maintainer asked that giving one to a transaction offer to give it to every transaction at the same merchant, **past and/or future** — two choices, taken separately.

The foundation plan had said categories would attach to merchants.
That was set aside for a category on the transaction, because one merchant is not one kind of spending: a Costco run is groceries one week and a lawnmower the next.

A mortgage payment raised a second case.
Its interest is an expense at the lender and has a merchant; its principal is a transfer from the chequing account to the loan, which has no merchant but should read Hypothèque too.

That leaves the question this record answers: when a merchant "gives" a category, is it a value copied into each transaction, or a default the screen reads through whenever a transaction has none?

## Decision

**A merchant's category is copied into each transaction as it is recorded, and never read through at display time.**

- `merchant.categoryId` holds what the merchant gives from now on.
  `ParsePipeline` writes it into every Wallet row it records there, a row sent to review included.
  The form fills it in when a merchant is named, until you pick a category yourself.
- A transfer, having no merchant, is keyed on the account it goes **into**: `account.categoryId`.
  Where a transfer lands says what it was for — the card payment, the mortgage, savings.
- "Past" is a separate write: it gives the category to that merchant's other transactions of the same kind **that have none**, or to the other transfers into the same account.
  A transaction already filed elsewhere keeps its category.
- A Wallet re-post or parser fix keeps a row's category, and fills one in only when the row has none.
- Categorising other rows does **not** stamp `reviewedAtUtcMillis`, so a later parser fix can still repair them.
- No total reads the category.
  A transfer with a category still moves the ledger by zero.

Rejected: reading the merchant's category through, as `COALESCE(t.categoryId, m.categoryId)`.
It would make every change to a merchant silently rewrite history, which erases the past/future distinction the maintainer asked for.
It would also give an export two answers for one transaction's category.

Rejected: split transactions, as MyExpenses has them, for the mortgage.
They would change how every total reads a row (ADR-0002, 0006, 0007, 0008) and need export format v3.
They get their own plan if a mixed basket ever makes them worth it, and categories carry into them unchanged.

## Consequences

**Changing a merchant's category changes what comes next, never the ledger already there.**
To recategorise the past as well, you tick the first box — which fills only the transactions with none.
Moving a whole merchant from one category to another means doing the odd ones by hand, or deleting the old category.

**The category is plain data on each row.**
The export carries exactly what the ledger shows, and an import restores it without re-deriving anything.

**A corrected merchant in the review inbox brings its own category.**
The inbox never asks for a category, so the one it replaces came from the merchant the parser misread, never from you.

**A category report will have to decide whether a transfer counts.**
The principal carries Hypothèque as a label; whether the Hypothèque line of a report includes it is for that report's plan to settle, the way ADR-0007 settled what a report counts.

## Where this lives in the code

- `MerchantEntity.categoryId` and `AccountEntity.categoryId` in `core/database/src/main/kotlin/.../Entities.kt`, and `MIGRATION_10_11` in `DepensesDatabase.kt`.
- `CategoryDao.categoriseAtMerchant` and `categoriseTransfersInto`, and `TransactionDao.insertOrUpdate`, in `Daos.kt`.
- `CategoryRepository.spreadFrom` in `core/data/src/main/kotlin/.../CategoryRepository.kt`; its callers `TransactionEditor.edit` and `TransactionRepository.addManual`; `ParsePipeline.record`; `ReviewRepository.correct`.
- `CategoryPicker` and `CategoryOffer.kt` in `feature/ledger/src/main/kotlin/.../`.
- `CategoryReachTest` in `:core:data`, and `ExpenseFormCategoryTest` in `:feature:ledger`.
