# 15. A transaction counts from its day

- **Status:** Accepted — implemented.
- **Decided:** 2026-09-30, while planning upcoming transactions (`.lavish/upcoming-transactions.html`, forks A to F).
- **Touches:** `:feature:ledger` (`LedgerViewModel`, `UpcomingLines`, `TransactionRow`), `:core:data` (`ReportRepository`, `AccountRepository`, `RecurringRepository`, `LocalDateTicker`), `:core:database` (`AccountDao.allWithCounts`), `:feature:reports`, `:feature:settings`, `:app`.

## Context

The form takes any date, so a transaction can be typed for a day still to come: next month's car insurance, a bill due on the 15th.
The ledger listed it newest first, at the top, as if it had happened, and counted it in this month's headline once its month came, on the rule that what the list shows is what the headline above it counts.
Reports counted it in the current period, and an account's balance included it.

A recurring transaction set to start next month made it worse: saving it wrote its first row straight away, on that future day.

Showing only the recurring transactions in Settings would have hidden a one-off transaction dated ahead of today, which isn't recurring and has no place there.

## Decision

**A transaction dated after today counts in nothing until its day: not the ledger's headline, a day's total, the selection, a report, or an account's balance.**
"After today" is the row's own day against today's, as the day ticker gives it, so at midnight a row starts to count without anything being written.

**It waits in one line above today, set apart from the list.**
The line says how many there are and what they come to, net; opened, it lists each with its own day.
It opens collapsed each time the app starts, and stays open for the session once opened.
A tap opens a line, and a long press does nothing, so a selection never holds something that counts nowhere.
The ledger's filters narrow it like the rest.

**The line also holds each recurring transaction's next day**, as the row it will write: one line per schedule, never every day to come.
It exists only on screen and is written on its day as before ([ADR-0013](0013-a-recurring-transaction-is-written-on-its-day.md)).

**A recurring transaction that starts after today writes nothing until then**, its first day included: saving makes only the schedule, with that day as its next.
A schedule never has a row dated ahead of today, and a change to it before its first day reaches that day too.

The rule that what the list shows is what the headline counts still holds: rows ahead of today are no longer in the list.

Rejected: listing only recurring transactions, in Settings, and nothing in the ledger.
A one-off transaction dated ahead of today would have had to become a recurrence of one to be seen.
Rejected: leaving reports and balances as they were.
This month's report would then count what the ledger's headline for the same month leaves out.
Rejected: the count alone on the collapsed line.
What the upcoming transactions come to is the useful number, and it sits outside the balance card, so it isn't read as part of it.

## Consequences

**A figure never includes a day still to come.**
A report for the current period covers up to today, and a balance is what the account holds today.

**The phone's date decides.**
A phone whose clock is wrong moves rows in or out of the line, as it already moves the day headings.

**The line's total follows the ledger's other rules**: a transfer is zero to the whole ledger and signed by the filtered account's end, and only Canadian dollars are added.
There is no room on the line to say how many rows in another currency it left out.

## Where this lives in the code

- `LedgerViewModel` — the split at today, and each schedule's next row joining what is dated after it.
- `upcomingGroup` in `feature/ledger/src/main/kotlin/.../UpcomingLines.kt` — the line and what opens under it; `UpcomingGroup` keeps it open for the session.
- `RecurringRepository.nextRows` — the row a schedule will write, built by the same `occurrenceOn` that writes it; `RecurringRepository.repeat` for a schedule that starts after today.
- `ReportRepository` (`lastDayThrough`) and `AccountDao.allWithCounts` (`throughDay`) — every figure stops at today.
- `LedgerUpcomingTest` in `:feature:ledger`, which walks the clock across midnight.
