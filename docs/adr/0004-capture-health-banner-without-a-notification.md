# 4. A banner for broken capture, and no notification

- **Status:** Accepted, revised twice — implemented in `35024ee` (Phase 2, commit 7); the collapse on `feat/room-at-large-font-sizes`.
- **Decided:** 2026-09-07, during Phase 2 planning (`32caf07`).
  Revised 2026-09-09 (`e18c3ae`), before any of it was built, and again 2026-09-23.
- **Touches:** `:core:model` (`CaptureHealth`), `:capture:listener`, `:feature:settings`, and the manifest that was not changed.

## Context

This app has one silent failure and it is fatal to the product.
Android unbinds notification listeners — after an update, after a force-stop, after the system decides to.
When it happens nothing breaks visibly: the ledger still opens, the totals are still there, and they are simply frozen.
A user discovers it weeks later, and by then the notifications are gone from the shade and the spending is unrecoverable.

Detecting it is harder than it sounds, because "no purchases recorded" is also what a quiet fortnight looks like.
The two are indistinguishable from the ledger alone.

## Decision

An in-app banner whenever notification access is revoked, or nothing at all has reached the listener in 48 hours.
It sits above every destination rather than on one screen, because what it reports makes *all* the numbers in the app wrong, not the ones on a single screen.

Telling deafness from a quiet week is done with a heartbeat: the listener stamps a timestamp on **every** delivery, whatever app it came from, written before the package filter runs.
Nothing else is stored — not the package, not a count, nothing read from the bundle.
Restricting the heartbeat to Wallet would have made a fortnight of cooking at home identical to an unbound listener, which is the exact confusion being resolved.

The rule itself is a pure function over `(accessGranted, captureEnabled, lastEventAt, now)` in `:core:model`, so all four states are testable without Android.

## Revision — the notification was dropped

The original decision added a local notification on the same trigger.
It cannot be built the way the plan assumed.

Something has to be awake at the 48-hour mark to post it, and the only two moments that wake this app — the listener binding, and the user opening it — are exactly the two that cannot happen while capture is deaf.
Posting at next launch would fire on the screen that is already showing the banner.

Making it real needs `AlarmManager` and a boot receiver, which means adding `POST_NOTIFICATIONS` and `RECEIVE_BOOT_COMPLETED` to the manifest of an app whose entire pitch is that it asks for almost nothing.

**Decided: not worth the permissions.**
The manifest keeps exactly the surface it had — the app declares no permissions of its own, and the listener's `BIND_NOTIFICATION_LISTENER_SERVICE` is signature-level and held by the system.
The banner is the whole of this decision.

## Revision — the banner can shrink, not go away

- **Decided:** 2026-09-23, while planning for large font sizes (`.lavish/large-font-banner-and-nav.html`).

At 200 % fonts, with an account filter on, the banner and the ledger header between them left room for about one row of the list.
The first idea was to make the banner collapsible.

A banner you can hide is a notification you can swipe away, and this is the only alarm the app has.
So it collapses to a strip that keeps the words *Capture interrompue* and the **Réactiver** button, and gives up only the explanation.
It stays collapsed for the rest of the session, never on disk: every launch raises the alarm in full again.
It is keyed to the kind of failure, so silence turning into revoked access opens it back up.

Rejected: a full dismiss, and a collapse remembered across launches — both let a broken capture live out of sight for weeks, which is the failure this record exists to catch.

## Consequences

The alarm is only seen by a user who opens the app.
That is a real gap and it is accepted: someone who has not opened it in a month is not being served by a notification either, and the cost of closing the gap is two permissions and a boot receiver.

The heartbeat is a write on every notification the device shows, and DataStore rewrites its whole file per edit, so it is throttled to once per five minutes — coarse enough to be cheap, fine enough to be honest against a 48-hour rule.

The grant moment needed care.
There is deliberately no "granted at" timestamp: before the first delivery `lastEventAt` is null and the state is `Listening(null)`, which reads as "waiting for the first notification" rather than raising an alarm at a user who has just done the right thing.

## Where this lives in the code

- `core/model/src/main/kotlin/.../CaptureHealth.kt` — the rule, the four states, `SILENT_AFTER = 48h`.
- `capture/listener/src/main/kotlin/.../DepensesListenerService.kt` — `noteAlive()`, called before the package filter.
- `capture/listener/src/main/kotlin/.../CaptureHealthRepository.kt` — the live verdict.
- `feature/settings/src/main/kotlin/.../CaptureHealthBanner.kt` — the banner and its collapsed strip, above the nav host in `DepensesApp`.
- `SettingsRepository.noteListenerEvent` — the throttle.
