# 2. Totals exclude unreviewed rows

- **Status:** Accepted — implemented.
- **Decided:** 2026-09-07, during Phase 2 planning (`32caf07`).
- **Touches:** `:core:database` (`ReportDao`), `:feature:ledger`, `:feature:reports`.

## Context

A parser reading a notification can be confident, or it can be unsure — a missing amount, an unreadable merchant, a payload that looks like a payment and is not.
Rows it is unsure about land in the review inbox as `NEEDS_REVIEW` and wait for a human.

Every total in the app therefore has to answer a question the parser cannot: does a purchase the app is *guessing* about count toward what you spent this week?

Both answers are wrong in a different direction.
Including unreviewed rows means a total can move without you doing anything, and can be inflated by something that was never a purchase.
Excluding them means a total can quietly understate real spending, and a user who never opens the inbox is shown a number that is confidently too low.

## Decision

`ReportDao` keeps its `status = 'CONFIRMED'` filter on every query.
Every amount on screen is one you have agreed to.

## Consequences

The "N à réviser" pill is not decoration.
It is the only thing standing between an excluded row and a total that understates, so it ships with the ledger rather than later, and it is a link to the inbox rather than a badge.

This is the cheaper mistake of the two.
A number that is too low and says so somewhere on the same screen is recoverable; a number that is too high for a reason the user cannot see corrodes trust in the whole ledger.

It also makes the review inbox load-bearing rather than optional.
An inbox nobody empties is a slow leak in the totals, which is an argument for keeping the queue short — the parser should ask rarely, and only about things a human can actually settle.

## Where this lives in the code

- `ReportDao` in `core/database/src/main/kotlin/.../Daos.kt` — three queries, each filtering `status = 'CONFIRMED'`.
- `TransactionDao.withMerchantByStatus` — the ledger reads by status for the same reason.
- The pill and its plural strings in `:feature:ledger`.
