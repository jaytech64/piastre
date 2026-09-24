# 8. The ledger header says what the balance is made of

- **Status:** Accepted — implemented.
- **Decided:** 2026-09-23, while planning the ledger header (`.lavish/ledger-header-income-and-spending.html`).
- **Touches:** `:feature:ledger` (`LedgerViewModel`, `MonthFigures`).

## Context

The ledger's headline is a month balance: income minus expenses, with a transfer worth zero to the whole ledger and signed by which end a filtered account is on ([ADR-0006](0006-a-filtered-total-answers-for-one-account.md)).
A −312 $ month could be a quiet month with no paycheque or a heavy month with one, and nothing on screen said which.

Showing income and expenses under the balance raised a question that only exists under a filter.
Unfiltered, a transfer is zero, so income − expenses is the balance exactly.
Filtered to a credit card, the month's most important row is the transfer that pays it off: it moves the card's balance, but it is neither income nor an expense.
Two figures counted by kind would then sit one line under a balance they do not add up to — the contradiction ADR-0006 was written to prevent.

## Decision

The header shows **Revenus** and **Dépenses**, counted by kind, unsigned, under the balance.
Under a filter, a month whose transfers moved the account gets a third figure, **Virements**, signed because a net transfer can go either way.

Rejected: calling the figures *Entrées* and *Sorties* and folding transfers into them.
It always reconciles, but it renames the whole-ledger figures to something less true, and *Sorties* on a filtered chequing account would count the card payment as if it were spending.

Rejected: counting by kind and letting the figures not add up.

## Consequences

**The figures always add up to the balance.**
`MonthSummary.balance` is derived as income − expenses + transfers rather than summed on its own, so the headline cannot disagree with the figures under it.

**The whole ledger's *Dépenses* is a report's *dépensé*.**
Both count confirmed expenses in the calendar month ([ADR-0007](0007-a-report-counts-spending-not-the-balance.md)), so the two screens now agree on the one number they share.

**Income is shown even when it is zero.**
Most months have no income logged, and *Revenus 0,00 $* is the honest answer to "why is my balance negative"; hiding it would also change the header's shape on the first paycheque.

**The header is taller.**
At large font sizes the figures take one line each instead of sharing columns.
With the capture banner showing, an account filter on and the font at 200 %, the header leaves room for about one row of the list on a Pixel 10.

## Where this lives in the code

- `MonthSummary` and `summaryOf` in `feature/ledger/src/main/kotlin/.../LedgerViewModel.kt` — the arithmetic.
- `MonthFigures` in `feature/ledger/src/main/kotlin/.../MonthFigures.kt` — which figures show, their signs, and how they fall back at large font sizes.
- `` `income and expenses are counted apart and add up to the balance` `` and `` `a card payment shows as a transfer on the card, not as income` `` in `LedgerViewModelTest`.
