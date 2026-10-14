# Piastre — Play Billing spike

Throwaway app whose only job is to answer one question:
**does Play Billing work when the app holds no network permission?**

Delete this directory once the answer is recorded in the Premium plan.

## What it is

One screen with five buttons and a log, built in two flavours from the same code:

- `asis` keeps whatever the billing library merges into the manifest.
- `stripped` removes `INTERNET` and `ACCESS_NETWORK_STATE` with `tools:node="remove"`.

It uses the production `applicationId`, because Play only sells to the package it lists.
**Emulator only, never the phone**: it installs over the real debug app, and the phone holds the only copy of the ledger.

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

## What is needed to finish it

1. A Google account signed in on the emulator.
2. The app created in Play Console under `io.github.jaytech64.piastre`, with a build on the internal testing track.
3. That Google account added as a licence tester.
4. Two products: a subscription `premium` with a yearly base plan and a 30-day free-trial offer, and a one-time product `premium_lifetime`.

## Running it

```sh
ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:assembleStrippedDebug
adb -s emulator-5554 install -r app/build/outputs/apk/stripped/debug/app-stripped-debug.apk
adb -s emulator-5554 logcat -s BillingSpike:I
```

Press the buttons top to bottom and keep the log.

| Button | Passes when |
|---|---|
| Connect | `connect: code 0` |
| Query products | both products are `found`, and the subscription lists an offer with a free phase |
| Buy subscription (trial) | Play's sheet opens, then `purchases updated: code 0` and `acknowledge … code 0` |
| Buy lifetime | the same |
| Query purchases | each purchase is listed as owned and acknowledged |

Then cancel the subscription in the Play Store app and press **Query purchases** again: `renews=false` is the pass.

The `json keys` line under each purchase shows everything the app is told about it.
It settles whether the app can see that a purchase is a trial, which the trial reminder depends on.

## Putting the real app back

From the repository root:

```sh
ANDROID_SERIAL=emulator-5554 ANDROID_HOME=$HOME/Android/Sdk ./gradlew installDebug
```

The spike never opens the ledger, and the debug signature is the same, so the emulator's data is untouched.
