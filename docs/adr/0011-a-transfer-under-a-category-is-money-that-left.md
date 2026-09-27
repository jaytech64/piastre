# 11. A transfer under a category is money that left for it

- **Status:** Accepted — implemented.
- **Decided:** 2026-09-26, while planning the ledger's category and merchant filters (`.lavish/ledger-filters.html`, fork D).
- **Touches:** `:feature:ledger` (`LedgerViewModel`).

## Context

The ledger can now be narrowed to a category as well as to an account.
[ADR-0010](0010-a-merchants-category-is-written-onto-each-purchase.md) gives a transfer the category of the account it goes into, so the monthly payment from Desrives to the mortgage account is a *Hypothèque* transfer.

A transfer is worth zero to the ledger as a whole (`Transaction.signedAmount`), and [ADR-0006](0006-a-filtered-total-answers-for-one-account.md) only changes that under an account filter.
Filtered to *Hypothèque* with no account, the rule would show two $1,200.00 rows under a headline of $0.00.
That is the "$60 row totalling $0" ADR-0006 already called the strongest argument against keeping zero under a filter, and the question the filter is asked — what did the mortgage cost me this month — would get no answer.

## Decision

Under a category filter with no account filter, a transfer counts as money out: its amount, negative, under *Transfers* in the header, so the three figures still add up to the balance ([ADR-0008](0008-the-ledger-header-says-what-the-balance-is-made-of.md)).

An account filter still decides first: with both an account and a category, a transfer is signed by which end the account is on, exactly as ADR-0006 says.

*No category* is not a place money goes, so under it an uncategorised transfer keeps the whole ledger's zero.
Most uncategorised transfers are card payments, and counting one would charge the purchases it settles a second time.

## Consequences

There are now three signing rules — whole ledger, one account, one category — and the filter chooses between them.
They all live in `balanceMovedOn`, which remains the one place that knows about any of them.

Day totals follow the headline, so a *Hypothèque* day reads as the payment that left rather than as zero.

A category given to transfers is now a claim about where money went, not only a label.
Giving a category to the account a card is paid into would make those card payments count as money out under that category, beside the purchases they settle; that is the user's categorisation speaking, and the filter reports it rather than second-guessing it.

The unfiltered ledger, the account filter, reports and exports are untouched.

## Where this lives in the code

- `balanceMovedOn` in `feature/ledger/src/main/kotlin/.../LedgerViewModel.kt` — the three rules and their reasoning.
- `` `a transfer under a category is money that left for it` ``, `` `a transfer under an account and a category is signed by the account's end` `` and `` `an uncategorised transfer still moves nothing under no category` `` in `LedgerFilterTest`.
