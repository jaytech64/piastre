# 1. Raw log: 90-day purge, unparsed rows kept

- **Status:** Accepted, revised once — implemented in Phase 2.
- **Decided:** 2026-09-07, during Phase 2 planning (`32caf07`).
- **Touches:** `:core:database` (`raw_notification_event`), `:core:data`.

## Context

Every notification the listener claims is written to `raw_notification_event` before anything tries to understand it, content included: `title`, `text`, `bigText`, `subText` and an allowlisted `extrasJson`.

Storing the payload first is what makes a parser fix retroactive.
[0004](0004-capture-health-banner-without-a-notification.md) is about not asking for permissions; this is about not keeping what was already given.
The re-parse sweep that repairs history reads exactly these columns, so the raw log is not archival clutter — it is the mechanism.

It is also the most sensitive table in the app.
Its rows hold verbatim lock-screen text about a real person's spending, on a device in Quebec, under Law 25's minimisation and retention obligations.
"Keep it forever because a parser might improve" is a defensible engineering instinct and an indefensible privacy position.

## Decision

A retention sweep nulls `title`, `text`, `bigText`, `subText` and `extrasJson` on rows older than 90 days.

The row itself survives with its `id`, `notificationKey`, `postedAtUtcMillis` and `parseState`.
That skeleton is what keeps the uniqueness rule honest: a replay of a two-year-old notification must still collapse rather than reappear as a new purchase.

Rows whose `parseState` is `UNPARSED` or `PENDING` are **exempt** and kept indefinitely.

## Consequences

A parser fix repairs the last 90 days of history, not all of it.
That is the deliberate trade, and it costs less than it appears to: the rows most likely to need a parser that does not exist yet are precisely the ones the exemption never touches.

The exemption has a cost of its own.
`UNPARSED` means "a parser looked at this and could not read it", so an unreadable payload is retained verbatim and indefinitely — the opposite of what minimisation asks for, justified only by the fact that it is the raw material for the parser that will one day read it.
If that set ever grows beyond a handful of rows, this record should be revisited rather than quietly tolerated.

A purged row still appears in the review inbox.
The inbox's join is a left join for this reason: the ruling stays possible, without the evidence.

## Revision — an unreadable payload is stripped too

- **Decided:** 2026-11-04, after an outside audit set the exemption beside the privacy policy.

The policy published with the first release says, without exception, that a notification's text is deleted 90 days after it arrived.
The `UNPARSED` exemption made that untrue, so it is gone: a payload no parser could read is stripped at 90 days like any other.

A new parser now reaches only the last 90 days of what nobody could read, which is the same reach a parser fix has always had over what was read.
What the exemption protected turned out to be close to nothing in practice — the Wallet parser never produces `UNPARSED`, and every Wallet notification that is not a payment is `IGNORED` and was already stripped — while a permanent exception to a published promise is exactly what Law 25's minimisation asks this app not to keep.

`PENDING` stays exempt.
A pending row has not been read at all, so stripping it would lose a purchase rather than evidence, and the sweep runs only after the pass that drains pending rows has finished, so in practice it never meets one older than 90 days.

## Where this lives in the code

- `core/data/src/main/kotlin/.../RawLogRetention.kt` — the 90 days, and the exempt states.
- `RawNotificationEventDao.redactOlderThan` — the sweep itself.
- `CaptureCatchUp.schedule()` runs it on app launch and listener bind, **last**, so a parser fix always gets to repair history before the payload it would have read is stripped.

Two implementation notes worth having here rather than only in KDoc.

`extrasJson` is set to `{}` rather than null, because the column is not nullable and making it so would mean rebuilding the table for no gain.
Emptiness is not the marker.

The marker is a new column, `redactedAtUtcMillis` (schema 6).
It exists because "old and empty" and "redacted" are different facts — a notification can genuinely arrive with no text — and the re-parse sweep must tell them apart.
Without it, re-reading a stripped row would find no payment, and the tidy-up that follows a re-parse would delete the transaction that row produced back when the text was still there: one parser bump silently erasing every purchase older than 90 days.
`parsedBelowVersion` therefore excludes redacted rows, and a test holds that line.
