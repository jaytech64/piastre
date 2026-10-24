# 22. Piastre is sold on Play and given on F-Droid

- **Status:** Accepted — the two builds and the F-Droid Support row are implemented; Play Billing is not yet.
- **Decided:** 2026-10-11, while planning Premium; the billing spike ran on 2026-10-13.
- **Touches:** `:app` (the `store` flavour, the F-Droid guard), `:core:data` (`PremiumSource`), `:premium:play`, `:premium:foss`, `:feature:settings` (the Support row).

## Context

Piastre gains a paid tier, Premium: every feature shipped so far stays free, and Premium is the features built from here on.

The app is GPL and has no network access.
Two audiences install it from two stores.
Play users expect to pay through Play.
F-Droid users chose that store to be rid of Google, tend to build software themselves, and may lawfully rebuild Piastre without any lock.
A lock there would cost goodwill and invite an unlocked fork distributed under someone else's name.

Selling on Play needs Google's Play Billing Library, which is proprietary and which F-Droid refuses.
Version 9.1.0 also merges `INTERNET` and `ACCESS_NETWORK_STATE` into the manifest, through Google's telemetry transport.

## Decision

**One codebase, two builds, told apart by a `store` flavour on `:app` alone.**

- The `play` build sells Premium through Play Billing: a monthly subscription with a 30-day trial, and a lifetime purchase.
- The `foss` build has no tiers: every feature is on, nothing is sold, and one Settings row links to a donation page that unlocks nothing.
- The Play build never mentions F-Droid or donations, which Play's payments policy bars; the README says openly that the F-Droid build is free.

Each flavour brings one module, `:premium:play` or `:premium:foss`, which binds `PremiumSource` in `:core:data`.
No feature can tell the builds apart, and the billing library is on one classpath by construction.

**Both builds keep the no-network claim.**
The billing spike stripped both merged permissions with `tools:node="remove"` and Play Billing still connected, sold a test subscription and a test lifetime purchase, acknowledged them, and reported them owned after a restart, after a cancel and in airplane mode.
The library's telemetry logs a warning without the permission and carries on.

**Both builds keep one `applicationId`.**
Android files the ledger under it ([ADR-0021](0021-depenses-became-piastre-and-the-ledger-crossed-by-hand.md)).
Their signatures differ, so moving from one store to the other is export, uninstall, install and import.

Rejected: offline licence keys for the F-Droid build.
They need a seller of record that hands out keys signed in advance, they cannot be revoked without a network, and the GPL lets anyone remove the check, so they would charge the audience most likely to resent it for a lock that protects nothing.

## Revision — the subscription renews every month

- **Decided:** 2026-10-24, before the yearly plan was ever sold.

The subscription was first planned as yearly, and it is now monthly instead, still with a 30-day trial.
Play cannot change a base plan's period, so the `premium` subscription carries a `monthly` base plan in place of `yearly`; ownership is read by product, never by base plan.
The trial reminder still comes a week before the trial ends, which stays inside Quebec's 2 to 10 days.

## Consequences

The F-Droid build caps what Play can earn: anyone willing to install from F-Droid pays nothing.
That is the accepted price of keeping that audience, not a flaw to fix later.

`verifyFoss*HasNoGoogleStoreCode` fails the build if a Google store library reaches the F-Droid classpath, or if its merged manifest asks for `com.android.vending.BILLING`, `POST_NOTIFICATIONS` or `RECEIVE_BOOT_COMPLETED`.
`verify*HasNoNetworkPermission` keeps running on every variant, Play included.

Every build task now names its store: `installPlayDebug`, `installFossDebug`.

## Where this lives in the code

- `app/build.gradle.kts` — the `store` flavour, both guards.
- `core/data/.../PremiumSource.kt` — what a store lets the app ask.
- `premium/play/`, `premium/foss/` — one binding each.
- `feature/settings/.../SettingsScreen.kt` — `SupportRow`.
- `spike-billing/README.md` — the spike's results, and how to run it again.
