# 19. A part of a split can go into another account

- **Status:** Accepted — implemented.
  Amends [ADR-0017](0017-a-transfer-counts-by-the-accounts-it-joins.md) and [ADR-0018](0018-a-split-divides-one-transaction-among-categories.md).
- **Decided:** 2026-10-07, while planning the follow-up ADR-0018 left for later (forks A to D).
- **Touches:** `:core:model` (`TransactionPart`, `Transaction.movements`, `Transaction.apart`), `:core:database` (`TransactionPartEntity`, `MIGRATION_17_18`, `AccountDao.allWithCounts`, `ReportDao`), `:core:data` (`settleCategory`, `LedgerExport`, `LedgerImport`), `:feature:ledger`.

## Context

A mortgage payment is one debit and two things: interest that is spent, and capital that moves into the mortgage account.
A split could say which categories the payment was for.
It could not say that one share landed in another account, so the mortgage's balance was wrong every month, by the whole payment when it was recorded as a transfer and by the capital when it was recorded as an expense.

[ADR-0018](0018-a-split-divides-one-transaction-among-categories.md) left this for later with a sketch: a destination account on the part, a third term in the account's balance, and no whole-ledger figure changing.
The last claim holds for a mortgage and not in general.
Money moved from a card into a bank account has left, so the capital counts as the interest does ([ADR-0017](0017-a-transfer-counts-by-the-accounts-it-joins.md)).
Cash back at the till is a share of a card purchase that went into the cash account, and money moved from a card to cash has only changed pockets.

## Decision

**A part may name an account it goes into. That share is then a transfer from the transaction's account into that one, and a split is worth what its parts would be, entered apart.**

- Only a part of an **expense** can go into another account.
  A transfer already has its destination, and a share of income landing elsewhere is income into two accounts, which is another feature.
- The whole ledger counts the share by the two accounts it joins, as it counts any transfer: money out into a bank account, money in back out of one, and nothing between cards and cash or between two bank accounts.
  Costco at 187,42 $ with 40,00 $ of cash back counts 147,42 $ spent.
  A mortgage payment counts whole, as before.
- The account the part goes into gains the share.
  The transaction's own account still gives up the whole amount.
- The ledger narrowed to the account a part goes into shows the split at that share, with the whole it is a share of under it, counted under *Virements*.
  Narrowed to the account it was paid from, the split is whole, with the share that moved on under *Virements* and the rest under *Dépenses*.
  Either ledger adds up to what that account's balance moved by ([ADR-0006](0006-a-filtered-total-answers-for-one-account.md)).
- The part keeps a category.
  Picking its account gives it that account's category when it has none, as a transfer takes one ([ADR-0010](0010-a-merchants-category-is-written-onto-each-purchase.md)), and giving the account a category later does not reach into it.
- On the form a second button, *Ajouter un virement*, adds a part that leads with an account field labelled as the transfer form's is.
  Save waits until the part has an account, the transaction has its own, and the two differ.
- A lone part cannot be a transfer.
  Ending the split leaves an ordinary expense, and an amount that wholly moved on is a transfer, which the form already has.
- The unfiltered row still shows the whole payment, above a day that may count less, as a card payment's row shows its amount above a day that counts it as nothing.
- The CSV line of such a part reads as a transfer, naming the account in the column a transfer uses.
  The JSON carries the account on the part and becomes format version 4.

Rejected: counting a split whole whatever account a part goes into.
No total would change its rule, but cash back would be counted as spent at the till and again when the cash is spent, which is the double count ADR-0017 exists to prevent.

Rejected: offering only bank accounts as a destination, so the two rules never disagree.
An account's kind can be changed later, and past months re-total when it is, so the question would only have been hidden.

Rejected: an account field on every part.
Every split form would be one field longer per part for something most splits never use.

Left for later: a template or a schedule that holds parts, which a monthly mortgage payment wants; a share of income landing in another account; one purchase paid from two accounts.

## Consequences

**"A split is counted whole" is no longer true of every split.**
It is true while no part moved between two accounts that ADR-0017 counts as nothing, which is every split made before this.
`Transaction.signedAmount` is the sum of `movements`, and a figure that keeps money in apart from money out adds up the movements rather than each row's net.

**Every report query reads rows and parts.**
`MOVEMENTS` in `Daos.kt` is one sub-select of each unsplit transaction and each part, and `MONEY_OUT` and `MONEY_IN` are asked of that.
The rule is still written twice, in Kotlin and in SQL, and `ReportRepositoryTest` holds the two to the same cent for every pair of account kinds, split or not.

**An account a part goes into is in use**, counts that split, and cannot be removed.

**Nothing recorded before is converted.**
No migration can know which share of a payment was capital, so schema 18 adds the column empty and a payment entered another way stays as it is.

A version-3 reader skips keys it does not know, so it would have imported the capital as an ordinary part and left the mortgage short without a word.
Format version 4 makes it refuse; this app reads 2, 3 and 4.
An import refuses a part into an account the file does not list, into the transaction's own, or on anything but an expense.

## Where this lives in the code

- `TransactionPart.toAccountId` and `canMoveOnFrom` in `core/model/src/main/kotlin/.../TransactionPart.kt`, and `TransactionPartTest`.
- `Transaction.movements` and `apart` in `core/model/src/main/kotlin/.../Transaction.kt`; `` `a split with a share moved on is worth what the expense and the transfer are, entered apart` `` in `TransferTest`.
- `settleCategory` in `core/data/src/main/kotlin/.../SettledCategory.kt` — what a save refuses.
- `MIGRATION_17_18` in `core/database/src/main/kotlin/.../PiastreDatabase.kt`; `AccountDao.allWithCounts`, `deleteIfUnused` and `MOVEMENTS` in `Daos.kt`.
- `` `a report and the ledger agree to the cent on a share moved on, for every pair of accounts` `` in `ReportRepositoryTest`.
- `LedgerFilter.partsCounted` and `rowsCounted` in `feature/ledger/src/main/kotlin/.../LedgerViewModel.kt`; `` `each account's ledger adds up to what its balance moved by` `` in `LedgerSplitTest`.
- `SplitParts.addTransfer` and `SplitSection` in `feature/ledger`, and `ExpenseFormSplitTest`.
- `checkParts` in `core/data/src/main/kotlin/.../ImportPartChecks.kt`, and `LEDGER_FORMAT_VERSION` in `LedgerDocument.kt`.
