# Dépenses

An Android expense tracker that turns Google Wallet payment notifications into a private, local ledger.

Everything stays on the device.
The app has no `INTERNET` permission and no network code, and the build fails if either ever appears — see [Privacy](#privacy) below.

## Status

Phase 1 — skeleton.
The module graph, build logic, database, theme and navigation are in place and the app installs and runs.
Capture itself is Phase 2; the ledger is empty by design until then.

The Wallet parser is already written and tested against a corpus captured over two weeks on a real device.

## Requirements

- JDK 17.
  Gradle 8.14 does not run on JDK 25, which is what Android Studio now bundles, so set **Settings → Build Tools → Gradle → Gradle JDK** to a 17 install.
  `brew install openjdk@17` provides one.
  The required version is declared in `gradle/gradle-daemon-jvm.properties`, so a mismatch fails with a readable message rather than a bare version number.
- Android SDK 36.

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

## Testing

Unit tests run on the JVM.

```
./gradlew check
```

`:capture:parsing` is tested against the captured fixture corpus, and the corpus test is a contract rather than a smoke test: it asserts the exact number of transactions a week of notifications collapses to, with every rejection attributed to a named reason.

## Licence

GNU General Public License v3.0 — see [LICENSE](LICENSE).

Every source file carries an `SPDX-License-Identifier: GPL-3.0-or-later` header, so the licence travels with the file rather than only with the repository.
