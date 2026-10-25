# 24. A merchant gives each kind its own category

- **Status:** Accepted — implemented.
- **Decided:** 2026-10-25, fixing a merchant's suggestion that filled in its income's category on a new expense.
- **Touches:** `:core:database` (`MerchantEntity`, `MIGRATION_20_21`), `:core:data` (`CategoryRepository`, `MerchantRepository`, `ParsePipeline`, `ReviewRepository`, the export and import), `:feature:ledger`.
- **Amends:** [ADR-0010](0010-a-merchants-category-is-written-onto-each-purchase.md).

## Context

ADR-0010 gave a merchant one `categoryId`, and the form offers to make a category "every future purchase" or "every future payment" there.
It never said which kind that category was for, so one slot served both.

The same place can be both a pharmacy you buy from and the employer that pays you.
Ticking "Every future payment from Horizon Santé" on an income of Paye made Paye what the merchant gave everything.
A new expense there filled in Paye as soon as the merchant was named, and so did every Google Wallet purchase captured there, which is worse, since nothing on screen asks for that one.

"Past" was already per kind: giving a category to a merchant's other transactions reaches only those of the same kind.
Only "from now on" ignored it.

## Decision

**A merchant gives an expense one category and an income another, and each is set from its own kind.**

- `merchant.expenseCategoryId` and `merchant.incomeCategoryId` replace `merchant.categoryId`.
  The form reads and writes the slot of the kind being entered.
  Capture, which records only expenses, reads the expense slot.
  The review inbox reads the slot of the row's kind.
- An expense at a merchant that only gives its incomes a category arrives with none.
  It does not fall back to the income's, nor to the expense's own last category.
- **On upgrade, a merchant's one category goes to the kind its rows show it was set for.**
  It goes to income when only incomes at that merchant carry it, and to expense otherwise — when only expenses carry it, both kinds do, or nothing does.
  A captured row is no evidence, because its category was copied from the merchant in the first place.
  No row's own category changes (ADR-0010).
- Export format 5 writes both categories.
  An older file has one, and import sends it where the migration would, judging from the file's own rows.
  A version-4 reader refuses a version-5 file, rather than giving the expense category to incomes too.

Rejected: putting every existing category in the expense slot.
It is simpler, but it leaves the bug on every phone that set a merchant's category from an income — Wallet captures included — until it is noticed and set again by hand.

Rejected: one slot that remembers which kind set it.
It is smaller, but setting an income's category silently drops the expense's, which is the same bug with the kinds swapped.

## Consequences

**A merchant that is both a shop and a payer has to be told twice.**
Its expense category and its income category are separate choices, each offered on its own form.

**The upgrade guesses, and the guess is visible.**
When a merchant's rows do not settle it, the category goes to expenses.
A category set from an income, at a merchant whose expenses carry it by hand too, lands on expenses and has to be set again on the next income.

**Settings counts a merchant once per category it gives.**
A category's merchant count counts a merchant that gives it in either slot.

## Where this lives in the code

- `MerchantEntity.expenseCategoryId`, `incomeCategoryId` and `categoryFor` in `core/database/src/main/kotlin/.../Entities.kt`, and `MIGRATION_20_21` in `PiastreDatabase.kt`.
- `Merchant.categoryFor` in `:core:model`; `MerchantDao.setCategory` and `MerchantRepository.categoryOf` in `MerchantRepository.kt`.
- `ExportedMerchant.categoryFor` and `LEDGER_FORMAT_VERSION` in `LedgerDocument.kt`; `LedgerImport.giveDefaults`.
- `CategoryOffer.place` in `feature/ledger/src/main/kotlin/.../CategoryOffer.kt`.
- `PiastreDatabaseMigrationTest`, `CategoryReachTest`, `LedgerImportTest` and `ExpenseFormCategoryTest`.
