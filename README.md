# Dépenses

An Android expense tracker that turns Google Wallet payment notifications into a private, local ledger.

Everything stays on the device.
The app has no `INTERNET` permission and no network code, and the build fails if either ever appears — see [Privacy](#privacy) below.

## Status

Phase 3 — reports.
Google Wallet payment notifications are captured, parsed and recorded, and the app has been run against real spending on a device.
The ledger with editing, deletion and filters by account, category, merchant, dates, amount and note, the review inbox, manual entry, accounts and transfers, capture-health onboarding, export, import and erasure are in place.

Reports now read the ledger back: a day, week or month you step through, its balance with what came in and what was spent, the spending against the period before it, the merchants it went to, and a calendar of the month shaded by each day's spending.
The ranking and the calendar count spending alone — never income, never a transfer — as [ADR-0007](docs/adr/0007-a-report-counts-spending-not-the-balance.md) and [ADR-0009](docs/adr/0009-a-report-says-what-its-balance-is-made-of.md) explain.

The Wallet parser is tested against a corpus captured over two weeks on a real device, and the raw notification log lets a parser fix repair history rather than only improve the future.

Transactions take a category, and giving one to a purchase can also give it to the merchant's others and to everything bought there from now on — or, for a transfer, to everything moved into the same account ([ADR-0010](docs/adr/0010-a-merchants-category-is-written-onto-each-purchase.md)).
Categories are arranged in groups on a page of their own in Settings, and the ledger can be filtered by a whole group ([ADR-0016](docs/adr/0016-a-categorys-group-is-read-through.md)).

One transaction can be split among several categories: it stays one row counted whole in every balance, and its parts say what the amount was for wherever a category is read ([ADR-0018](docs/adr/0018-a-split-divides-one-transaction-among-categories.md)).

A transaction typed by hand can repeat — every week, every two weeks, every month or every year — and each time it falls due it is written into the ledger, dated on its own day, as an ordinary row ([ADR-0013](docs/adr/0013-a-recurring-transaction-is-written-on-its-day.md)).
Nothing wakes the app to do it: due days are caught up whenever the app or its listener runs.

Budgets and any payment source other than Wallet are out of scope so far.

## Requirements

- JDK 17.
  Gradle 8.14 does not run on JDK 25, which is what Android Studio now bundles, so set **Settings → Build Tools → Gradle → Gradle JDK** to a 17 install.
  `brew install openjdk@17` provides one.
  The required version is declared in `gradle/gradle-daemon-jvm.properties`, so a mismatch fails with a readable message rather than a bare version number.
- Android SDK 36.
- Git hooks, once per clone: `git config core.hooksPath .githooks`.
  `.githooks/post-merge` deletes a feature branch once `main` has absorbed it, since the merge commit is the record and the ref has no job left.
  It prunes only from `main`, only branches git reports as merged, and only with `git branch -d`, which refuses anything still carrying unique commits.

## Building

```
./gradlew assembleDebug     # build
./gradlew check             # lint, static analysis, tests, privacy guard
./gradlew spotlessApply     # format
./gradlew installDebug      # install on a connected device
```

## Module graph

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
| `:feature:*` | Android lib | `ledger` · `review` · `reports` · `settings`. Siblings never depend on each other. |
| `:core:testing` | Android lib | Fixtures, fakes, in-memory database. |

`:capture:parsing` and `:core:model` have no Android dependency at all, so the riskiest code in the app is tested on the JVM in milliseconds — no emulator, no Robolectric, no device.

## Privacy

The ledger is financial data about one person, and it is treated that way.

- **No network permission.**
  Not in the manifest and not in any dependency: `./gradlew check` reads the *merged* manifest and fails the build if `INTERNET` or `ACCESS_NETWORK_STATE` appears.
  Without the permission the data physically cannot leave the device, which is a stronger guarantee than a promise not to send it.
- **No backup, no device transfer.**
  `allowBackup="false"` plus `data_extraction_rules.xml` excluding every domain.
- **Real spending never enters the repository.**
  Test fixtures are redacted captures: the structure, timing and duplicate pattern are real, the merchants and card digits are not.

## Decisions

Decisions that would be expensive to reverse are recorded in [`docs/adr/`](docs/adr/), one file each, with what they cost as well as what they bought.

Engineering decisions that only make sense next to the code — parsing inline with a catch-up pass, the raw log's uniqueness rule, the `extrasJson` allowlist — live in the KDoc of the class that implements them instead.

## Testing

Unit tests run on the JVM.

```
./gradlew check
```

`:capture:parsing` is tested against the captured fixture corpus, and the corpus test is a contract rather than a smoke test: it asserts the exact number of transactions a week of notifications collapses to, with every rejection attributed to a named reason.

## Licence

GNU General Public License v3.0 — see [LICENSE](LICENSE).

Every source file carries an `SPDX-License-Identifier: GPL-3.0-or-later` header, so the licence travels with the file rather than only with the repository.
