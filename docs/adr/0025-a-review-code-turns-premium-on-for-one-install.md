# 25. A review code turns Premium on for one install

- **Status:** Accepted — implemented.
- **Decided:** 2026-10-28, while filling in Play Console's App access declaration.
- **Touches:** `:core:model` (`Premium.ReviewCode`), `:core:data` (`PremiumSource.redeem`), `:premium:play` (`ReviewCodeUnlock`), `:feature:premium` (the title's long-press and its dialog), `:feature:settings` (the Premium row).

## Context

Play Console's App access declaration will not save unless the developer attests that the instructions give reviewers full access, paid features included.
Google's reviewers cannot buy, cannot start a free trial and cannot create an account, so on the Play build budgets were out of their reach.
Target audience and Data safety stay blocked until App access is saved.

Two other ways out were rejected.
Declaring that nothing is restricted would be false.
Making the build Google reviews free would review a different app from the one sold.

The app has no network access, so the check can only be local, and the source is public.

## Decision

**A code typed into the Play build turns Premium on for that install.**
It is entered by long-pressing the title of Settings › Premium, which opens a dialog; the page a paying user sees does not change, and nobody is shown a path to Premium that is not Play.
While it is on, the Premium page says so in words of its own, « Premium est actif sur cet appareil grâce à un code d'examen. », and the Settings row reads « Actif, avec un code d'examen ».
There is no way to turn it off; a reviewer's device is reinstalled, and the debug build's `ForcePremiumReceiver` checks every state.

**The code is 16 random Crockford base32 characters, and only its SHA-256 is in the app.**
The code lives in `~/.config/piastre/review-code`, beside the upload key, and goes into Play Console's App access instructions and nowhere else.
Eighty random bits cannot be guessed back from a published hash, so a slow key-derivation function would add nothing.

**The unlock lasts until a release ships a new code.**
It is stored as the hash the code matched, and counts only while that is the build's hash, so rotating the code revokes every unlock made with the old one.

**Anything Play says was bought wins over the code.**
The code turns on what Play left off, including when Play cannot be asked at all, as on a device without a Play account.

The F-Droid build needs none of this: it has everything already, and `PremiumSource.redeem` there takes no code.

## Consequences

The code guards nothing but itself.
The source is GPL and the F-Droid build is free ([ADR-0022](0022-piastre-is-sold-on-play-and-given-on-f-droid.md)), so anyone who wants Premium without paying already has a way; a leaked code costs no more than that, and the next release rotates it.

Play's reviewers depend on the instructions in Play Console being current: a rotated code must be pasted there before the release that ships its hash is submitted.

## Where this lives in the code

- `premium/play/.../ReviewCodeUnlock.kt` — the hash, the check, the stored unlock.
- `premium/play/.../PlayOwnership.kt` — `withReviewCode`, how it meets Play's answer.
- `feature/premium/.../ReviewCodeDialog.kt` and `PremiumScreen.kt` — where it is entered.
- [`docs/releasing.md`](../releasing.md) — rotating the code.
