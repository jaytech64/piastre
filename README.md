# Piastre

An Android app that turns Google Wallet payment notifications into a private, local ledger: where your money went, and what is left.

Everything stays on the device.
The app has no `INTERNET` permission and no network code, and the build fails if either ever appears — see [Privacy](#privacy) below.

## What it does

- **Captures what you pay with your phone.**
  Each Google Wallet payment notification becomes a ledger row: merchant, amount, card, time.
  A notification the parser cannot read with certainty waits in a review inbox instead of being guessed at.
- **Keeps the rest by hand.**
  Cash, income, transfers between accounts and recurring transactions are typed in, and a recurring one is written into the ledger on its own day.
- **Sorts it.**
  Categories, arranged in groups, can follow a merchant so that its next purchases arrive already categorised.
  A transaction can be split among several categories, or between spending and another account, as a mortgage payment divides into interest and capital.
- **Reads it back.**
  A balance for the month, filters by account, category, merchant, dates, amount and note, and reports by day, week or month with the merchants the money went to and a calendar of the month's spending.
- **Budgets**, by category or by group, judged against exactly what the reports count.
- **Lets the data go.**
  Export writes CSV and JSON, import merges a JSON export back in, and erasure removes everything.

It is written for Quebec, in English and French, and counts Canadian dollars; a purchase in another currency is kept and left out of totals.

## Requirements

- Android 12 or later.
- Google Wallet, used to pay with the phone.
  No other payment source is read yet.
- Notification access, which the app asks for on first launch.
  Android hands every notification on the device to an app holding it, so Piastre filters by package before opening anything: a notification from any app other than Google Wallet is dropped unread.
  The one thing kept is the time the listener last heard from Android, which is how the app knows capture still works.

## Getting it

Piastre is not on Google Play or F-Droid yet.
Until it is, build it from this repository — see [Building](#building).

## Two builds, and Premium

Piastre is built for two stores from this one codebase ([ADR-0022](docs/adr/0022-piastre-is-sold-on-play-and-given-on-f-droid.md)).

- **Google Play** sells Premium: a monthly subscription with a 30-day free trial, or a single purchase for life.
  Everything that is not Premium is free.
  Budgets are the first Premium feature.
- **F-Droid** has every feature, Premium included, for free.
  It sells nothing, carries no Google code, and only asks for a donation on [Liberapay](https://liberapay.com/jaytech64), which unlocks nothing.

The two builds are the same app.
Paying on Google Play supports the work; it buys nothing the F-Droid build does not already have.

## Privacy

The ledger is financial data about one person, and it is treated that way.

- **No network permission.**
  Not in the manifest and not in any dependency: `./gradlew check` reads the *merged* manifest of both builds and fails if `INTERNET` or `ACCESS_NETWORK_STATE` appears.
  Without the permission the data physically cannot leave the device, which is a stronger guarantee than a promise not to send it.
  The Play build removes the permissions Google's billing library would add; billing still works, because the Play Store app does the networking ([findings](docs/play-billing-without-network.md)).
- **No backup, no device transfer.**
  `allowBackup="false"` plus `data_extraction_rules.xml` excluding every domain.
  Export is the only way the ledger leaves the phone, and only when you ask for it.
- **The raw notification log is stripped after 90 days** ([ADR-0001](docs/adr/0001-raw-log-90-day-purge.md)).
- **Real spending never enters the repository.**
  Test fixtures are redacted captures: their structure and duplicate pattern are real, while merchants, card digits, dates and which amount belongs to which purchase are not.

## Building

Requirements:

- JDK 17.
  Gradle 8.14 does not run on JDK 25, which recent Android Studio releases bundle, so set **Settings → Build Tools → Gradle → Gradle JDK** to a JDK 17 install.
  The required version is declared in `gradle/gradle-daemon-jvm.properties`, so a mismatch fails with a readable message.
- Android SDK 36.

```
./gradlew assembleFossDebug    # the F-Droid build
./gradlew assemblePlayDebug    # the Google Play build
./gradlew check                # lint, static analysis, tests, privacy guards
./gradlew installFossDebug     # install the F-Droid build on a connected device
```

Both builds share one `applicationId` but not a signature, so moving from one to the other goes through export and import.

## Architecture

Module boundaries are Gradle modules rather than packages, because a package boundary is a convention and a module boundary is enforced.

| Module | Type | Holds |
|---|---|---|
| `:app` | Android app | Application, DI graph, navigation host. Assembly only. |
| `:core:model` | JVM | `Money`, `Transaction`, enums. Domain types with no framework. |
| `:capture:parsing` | JVM | Parser contract, registry, per-source parsers, currency normalisation. |
| `:capture:listener` | Android lib | The only module that touches the notification API. |
| `:core:database` | Android lib | Room entities, DAOs, migrations. |
| `:core:data` | Android lib | Repositories and settings. The only API features may see. |
| `:core:designsystem` | Android lib | Theme, and the one place that decides what money and dates look like. |
| `:feature:*` | Android lib | `ledger` · `review` · `reports` · `settings` · `premium`. Siblings never depend on each other. |
| `:premium:*` | Android lib | `play` · `foss`. What each store sells and says is owned; `:app` takes one per build. `play` is the only module that sees Google's billing library. |
| `:core:testing` | Android lib | Fixtures, fakes, in-memory database. |

`:capture:parsing` and `:core:model` have no Android dependency at all, so the riskiest code in the app is tested on the JVM in milliseconds — no emulator, no Robolectric, no device.
`:capture:parsing` is tested against a corpus of captured notifications, and that test is a contract rather than a smoke test: it asserts the exact number of transactions two weeks of notifications collapse to, with every rejection attributed to a named reason.

## Decisions

Decisions that would be expensive to reverse are recorded in [`docs/adr/`](docs/adr/), one file each, with what they cost as well as what they bought.
Engineering decisions that only make sense next to the code live in the KDoc of the class that implements them.

## Contributing

Bug reports and ideas are welcome as [issues](https://github.com/jaytech64/piastre/issues).
A bug in capture is most useful with the notification's wording, the language the phone was set to, and what the ledger showed — never with real card digits.

Pull requests are not accepted for now: the product is shaped by one maintainer, and every change is planned and proven on a device first.

Security problems go through [SECURITY.md](SECURITY.md), not a public issue.

## Licence

GNU General Public License, version 3 or later — see [LICENSE](LICENSE) — with an [additional permission](LICENSE-EXCEPTION.md) that lets the Google Play build link Google's billing library.

Every source file carries an `SPDX-License-Identifier: GPL-3.0-or-later` header, so the licence travels with the file rather than only with the repository.
