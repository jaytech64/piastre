# 6. A filtered total answers for one account

- **Status:** Accepted — implemented; amended by [ADR-0017](0017-a-transfer-counts-by-the-accounts-it-joins.md).
- **Decided:** 2026-09-20, while building the ledger's account filter (`.lavish/ledger-account-filter.html`).
- **Touches:** `:feature:ledger` (`LedgerViewModel`).

## Context

`Transaction.signedAmount` calls a transfer zero.
That is not an oversight, it is the rule that keeps the ledger honest: a transfer moves your own money between two accounts you already own, so counting it would charge you twice for the purchases a card payment settles.
Every total in the app has been built on it.

The ledger can now be narrowed to a single account, and that narrowing changes the question the headline is answering.
"What did I spend this month" and "what happened to Desrives this month" are different questions, and a transfer is exactly where they disagree.
Sixty dollars moved from Desrives to cash is worth nothing to the ledger as a whole and is unambiguously sixty dollars gone from Desrives.

The disagreement is not theoretical, because the filter menu lists each account beside its balance, and those balances come from `AccountDao.allWithCounts`, which has always signed transfers per-account: it subtracts the end money left and adds the end it reached.
A filtered headline built on `signedAmount` would therefore contradict, one tap later, the number the user chose the account from.

## Decision

Under a filter, a transfer is signed by which end the filtered account is on: negative where the money left, positive where it arrived.
Unfiltered, nothing changes — `signedAmount` still governs, and a transfer is still worth zero.

The invariant gains a clause rather than an exception: **a transfer is worth zero to the ledger as a whole, and moves the balance of each account it touches.**

## Consequences

There are now two signing rules where there was one, and the filter is the only thing that chooses between them.
That is the real cost, and it is paid in `LedgerViewModel.balanceMovedOn`, which is the one place that knows about both.
A third caller wanting per-account arithmetic should use that function rather than write the `when` again.

Day totals follow the headline, so a filtered day whose only row is a transfer reads as the amount that moved rather than as zero.
This is the visible half of the decision: a day showing a $60.00 row and totalling $0.00 was the strongest argument against the alternative.

Reports, exports and the unfiltered ledger are untouched, and `signedAmount` keeps exactly one meaning.
A transfer filtered to each of its two ends in turn shows as a loss on one and a gain on the other, which is correct and is covered by a test that says so.

## Revision — 2026-10-04

[ADR-0017](0017-a-transfer-counts-by-the-accounts-it-joins.md) changed what a transfer is worth unfiltered: it is no longer always zero, but money out when it goes into a bank account and money in when it comes back out of one.
What this record decided is untouched: under an account filter a transfer is signed by which end the account is on.
The function that does it is now `movedOn`.

## Where this lives in the code

- `balanceMovedOn` in `feature/ledger/src/main/kotlin/.../LedgerViewModel.kt` — the whole decision, and its reasoning.
- `Transaction.signedAmount` in `:core:model` — unchanged, and deliberately so.
- `AccountDao.allWithCounts` in `core/database/src/main/kotlin/.../Daos.kt` — the per-account arithmetic this decision agrees with.
- `` `a transfer takes from the account it left and adds to the one it reached` `` and `` `a transfer still moves the whole ledger by nothing` `` in `LedgerViewModelTest`.
