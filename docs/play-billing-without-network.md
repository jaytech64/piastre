# Play Billing without a network permission

Piastre holds no `INTERNET` permission, and Google's Play Billing Library merges one into every app that uses it.
A throwaway app tested whether billing still works once both merged permissions are removed with `tools:node="remove"`, as [ADR-0022](adr/0022-piastre-is-sold-on-play-and-given-on-f-droid.md) required.
It ran in two flavours: `asis`, with the library's manifest left alone, and `stripped`, with `INTERNET` and `ACCESS_NETWORK_STATE` removed.
It used the production `applicationId`, because Play only sells to the package it lists, so it ran on the emulator only.

The app itself was removed once these findings were recorded; it is in the history under `spike-billing/`.

## Found so far, on the emulator, 2026-10-11

Play Billing Library 9.1.0, `Pixel_10` AVD (Play Store image), no Google account signed in.

- The library merges `INTERNET` and `ACCESS_NETWORK_STATE`, both from `com.google.android.datatransport:transport-backend-cct:3.1.8`.
- The `stripped` merged manifest holds neither, and the installed app reports neither.
- The `stripped` app reaches the Play Store app: the Play Store logs the request, and answers `code 3` because no account is signed in.
- The `asis` app gets the same `code 3`, so the refusal comes from the missing account and not from the missing permissions.
- Without the permissions the library's telemetry logs `TransportRuntime: Error scheduling event … ACCESS_NETWORK_STATE` and carries on. Nothing crashed.

## Found on 2026-10-13, with products on Play and a licence tester signed in

All of it on the `stripped` build, which holds no network permission.

- `connect: code 0`.
- Both products are found: `premium_lifetime`, and `premium` with its base offer and `trial-30d`.
- The 30-day trial comes back as `P4W2D`, not `P30D`.
- The trial purchase and the lifetime purchase both succeed with Play's test card, and both acknowledge with `code 0`.
- After the app is restarted, both purchases are still reported as owned and acknowledged.
- A purchase carries `orderId`, `packageName`, `productId`, `purchaseTime`, `purchaseState`, `purchaseToken`, `quantity`, `acknowledged`, and `autoRenewing` for a subscription.
  Nothing in it says that a purchase is a trial or which offer it used.
- Play refused every purchase with "The item you were attempting to purchase could not be found" until the tester had accepted the internal-test invitation, and for about five minutes after.
  Being a licence tester was not enough.
- Play Console turns a typed price of 15 into 14,99 $; the Canada price has to be edited directly to keep 15,00 $.

- After the subscription is cancelled in the Play Store app, it is still reported as owned, with `renews=false`, until its period ends.
- `https://play.google.com/store/account/subscriptions?sku=premium&package=io.github.jaytech64.piastre` opens Play's own page for the subscription, with its cancel button.
- In airplane mode, from a fresh process: `connect: code 0`, and both purchases are still reported as owned, from the Play Store's cache.
  The lifetime product's details also come back; the subscription's fail with `code 12`, a network error.
  So entitlement can be read offline, and the buy screen cannot be relied on to load offline.
- A test subscription's trial lasts 3 minutes and its year 30 minutes; `purchaseTime` stays the time of the first purchase.

Every check the spike set out to make has passed.
