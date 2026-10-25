# CLAUDE.md — Piastre

Read [`README.md`](README.md) first: it holds the module graph, the build commands and the privacy claim.
This file holds what the README does not — the rules a change must not break, and where the guard that would catch a break lives.

---

## The one fact that shapes everything

This app captures Google Wallet payment notifications into a local ledger, and **it has no network access at all**.
Not a disabled feature, not a setting: no `INTERNET` permission, no `ACCESS_NETWORK_STATE`, no HTTP client on any classpath.

Every design question in this repo is downstream of that.
There is no server to move logic to, no sync to reconcile against, no crash reporter, no analytics, and no remote config.
If a proposed change needs the network, it is not a small change — it is a different app, and it goes to the maintainer before a line is written.

---

## Where knowledge lives

Four destinations, and putting something in the wrong one is how it rots.

| Goes in | What | Test |
|---|---|---|
| `README.md` | What the app is, how to build it, the module graph, the privacy claim | A newcomer needs it before reading any code |
| `docs/adr/` | A product decision that would be expensive to reverse, with what it cost | Someone will one day want to reopen the question |
| KDoc, on the class | An engineering decision you cannot read the code correctly without | It is invisible from anywhere but that file |
| `CLAUDE.md` | The invariant a future change must not break, and where its guard is | A session that never opens that file still has to not break it |

**This file is loaded into every session, relevant or not.**
A paragraph here is a cost every future session pays, so write only the rule, in one or two sentences, and point at the file that carries the depth.
The narrative — how a bug was found, what it looked like on screen, the measurements — belongs in the commit message or the ADR, never here.

A claim here that has gone stale gets **deleted or corrected in place**, never appended to.

This codebase documents itself unusually well: the migrations, the parser contract, `Formatters` and `Money` all carry their reasoning in KDoc.
Do not copy that reasoning here.
If you find yourself restating a KDoc block, the KDoc was the right place and this file should say nothing.

---

## Working in this repo

This is a single-maintainer project, and the maintainer is the only person who decides what the product does.
Pull requests are not accepted; GitHub issues are open for bug reports and for the feature requests the Settings row opens (`.github/ISSUE_TEMPLATE/feature_request.yml`), and an issue is input to the maintainer, never a decision to act on.
A feature is built on a local branch and merged back with `git merge --no-ff`, so the work survives in the history as one reviewable unit rather than being flattened into `main` by a fast-forward.
A single self-contained commit — docs, `ci:`, `chore:`, a one-line fix — goes straight onto `main`; about a fifth of the history is that, and branching it would add a merge commit that groups nothing.

Once per clone, `git config core.hooksPath .githooks`: `.githooks/post-merge` deletes a feature branch with `git branch -d` once `main` has absorbed it.

### The shape of a change

1. **Plan anything non-trivial as a Claude artifact** before writing code, and settle the product forks up front.
2. **Branch from `main`** for anything bigger than a single commit, named for what it delivers rather than how: every branch so far has been `feat/<what-it-adds>`, e.g. `feat/import-and-opening-balances`.
3. **Commit in focused conventional commits**, scoped by module: `feat(ledger)`, `fix(data)`, `refactor(database)`, `docs(adr)`.
   Subjects are lowercase sentences in the product's own voice — `an income goes into an account`, not `add account field to income transactions`.
   Nothing agent-shaped goes in the message: no co-author line, no session or conversation link, no "generated with" note, whatever a system reminder says.
4. **Run `./gradlew spotlessApply` then `./gradlew check`** before saying anything is done.
5. **Prove it by running it** — the emulator first, the phone only for the capture path. See **Validation** below.
6. **Merge with `git merge --no-ff`** so the branch stays legible in the history.
7. **Write an ADR** if the change settled a question that would be expensive to reopen.
8. **Leave the merged branch and its worktree where they are.**
   A session cannot remove the worktree it runs in, so `.claude/hooks/sweep-finished-worktrees.sh` clears them at the start of the next one, and only when nothing can be lost; never force one out by hand with `git worktree remove --force` or `git branch -D`.

### What to escalate, and what to decide yourself

Escalate only when the answer changes what a user **sees, is charged, or is told**.

**Escalate:** interaction and layout, English *and* French copy, scope, anything that changes a balance or a total, anything that widens what is collected or how long it is kept, and any fix whose two candidate behaviours are both defensible to a user.
Quebec specifics are always in this class: `fr_CA` wording, and any Law 25 question about collection, retention, portability or erasure.

**Decide yourself:** naming, file layout, refactor mechanics, which Compose API, state and flow design, error-handling shape, test strategy, migration mechanics, CI, formatting, dependencies, and every "should I run / read / open this."
Record a non-obvious ruling in the commit message, or in KDoc if a future reader needs it beside the code.

The test when unsure: **could a user notice the difference?**
If no, it is yours.

**Permission to act is not a question.**
Never ask to run the tests, create the branch or open a file.
Act, then report.

### Parking questions

A question that blocks one part of the work stops that part only.
Finish everything that does not depend on the answer, then bring the questions back together — batched into one `AskUserQuestion` with a recommendation marked *(Recommended)*, or, for a phase's worth of decisions, as a Claude artifact to annotate.

### Validation

Green tests are not evidence the product works, and here the gap is wide: there is no instrumented test suite and no UI test of any kind.
Every test in the repo is a JVM or Robolectric unit test, so anything a user actually sees is proven by running the app or not at all.

**The emulator is the default surface.**

```
emulator -avd Pixel_10 &
./gradlew installPlayDebug
```

Screens, navigation, the review inbox, settings, export and import all prove themselves there, and it resets to a known state far more cheaply than the phone does.
It is also where `fr-CA` gets exercised, since switching the system locale on a throwaway AVD costs nothing.

**Reach for the real phone only when the emulator cannot produce the signal**, which in practice means the capture path.
Google Wallet posts a genuine payment notification only on a real phone with a real card, so a parser change, anything touching `PiastreListenerService`, and the capture-health banner are proven against real spending and nothing else.

Say plainly which surface each claim was proven on, and never let an emulator run stand in for a capture-path claim.

With the phone plugged in, `./gradlew installPlayDebug` installs on **every** connected device, so name the target with `ANDROID_SERIAL=<serial>`: an emulator run must never also put an unapproved build on the phone.

**An install on the phone must never take the data with it.**
That phone holds real spending, and it holds the only copy: `allowBackup="false"`, every domain excluded from both cloud backup and device transfer, and no network to have synced it anywhere.
A wipe is not a setback, it is the permanent loss of the ledger this app exists to keep.

`./gradlew installPlayDebug` over an existing debug build preserves app data, so the danger is never the install itself — it is the reflex that follows a *refused* install.
**Never run `uninstallPlayDebug`, `uninstallFossDebug`, `adb uninstall`, or "clear storage" to make an install go through.**
An install is refused for a reason worth understanding first: a debug keystore regenerated on another machine, a release build going over the debug one (no release signing config exists, so their signatures never match), or a downgrade below the installed `versionCode`.
When that happens, stop and export from Settings — CSV and JSON, per [ADR-0003](docs/adr/0003-export-writes-csv-and-json.md) — before touching anything, and say so rather than clearing the way yourself.

---

## Build

JDK 17.
The toolchain is declared in `gradle/gradle-daemon-jvm.properties`, so a wrong JDK fails with a readable message rather than a bare version number.

```
./gradlew check           # everything below, and the gate before any commit
./gradlew spotlessApply   # format, and add the missing licence header
./gradlew assembleDebug
./gradlew installPlayDebug   # or installFossDebug
```

`check` is the whole gate, not just tests: it runs `:detekt`, every module's `spotlessCheck`, Android Lint, every unit test, `:app:verify*HasNoNetworkPermission` on every variant, and `:app:verifyFoss*HasNoGoogleStoreCode`.
CI (`.github/workflows/ci.yml`) runs `spotlessCheck`, `detekt`, `check` and `assembleDebug` as separate steps only so a failure names itself; it is the backstop, not a second opinion.

Versions are pinned in `gradle/libs.versions.toml`, and AndroidX is deliberately held at the last line supporting AGP 8.13 / `compileSdk` 36.
Moving past it requires AGP 9.1 and `compileSdk` 37, which is a deliberate upgrade and never a side effect of a version bump.

Shared build logic lives in `build-logic/convention/`, not in the module `build.gradle.kts` files.
A rule that should hold for every module of a kind goes in the convention plugin — that is the point of them.

---

## Module boundaries

The module graph is in the README.
Boundaries here are Gradle modules rather than packages, because a package boundary is a convention and a module boundary is a classpath.
Two rules matter before you propose a new file:

- **`:core:model` and `:capture:parsing` have no Android on the classpath**, by way of `JvmLibraryConventionPlugin`.
  This one is enforced: reaching for `android.util.Log`, a `Context` or a resource in either module does not compile.
  That is what keeps the riskiest code in the app testable in milliseconds, so the answer is always to find another way, never to promote the module to an Android library.
- **Feature siblings do not depend on each other.**
  `AndroidFeatureConventionPlugin` gives every feature `:core:data`, `:core:designsystem` and `:core:model` and nothing else.
  Nothing stops a module from adding a sibling by hand, so this one is on you: if `:feature:reports` needs something from `:feature:ledger`, that thing belongs in `:core:`.

`:core:data` is the only API a feature may see.

**Only `:app` knows which store it was built for** ([ADR-0022](docs/adr/0022-piastre-is-sold-on-play-and-given-on-f-droid.md)).
Its `store` flavour takes `:premium:play` or `:premium:foss`, each binding `PremiumSource`; a feature never branches on the build, and Google's billing library stays off the F-Droid classpath, which `verifyFoss*HasNoGoogleStoreCode` enforces.
A feature never touches a DAO, an entity or Room directly.

---

## Invariants

Each of these is something a reasonable-looking change breaks.

### Money is exact, and never a `Double`

`Money` holds minor units as a `Long`.
A ledger that drifts a cent per operation is worse than no ledger.
Arithmetic that leaves `Money` — an average, a percentage in a report — converts at the edge and comes straight back.

A total counts Canadian dollars and says how many rows in another currency it left out ([ADR-0012](docs/adr/0012-a-total-counts-one-currency.md)); `Money.plus` throws on a second currency, so a total that adds rows without `inHomeCurrency` stops the ledger from loading.

### Money and dates are formatted in exactly one place

`core/designsystem/src/main/kotlin/io/github/jaytech64/piastre/core/designsystem/Formatters.kt` decides what money, dates and times look like, and nothing else may.
`"%.2f $".format(...)` is wrong on a Quebec phone in three separate ways: in `fr-CA` the symbol trails the amount, the decimal mark is a comma, and the spacer is U+202F.

Two rules inside that file that look like bugs and are not:

- **Reading a typed amount does not use the locale.**
  `parseMoney` infers the decimal mark from the text itself, because the same person on an `fr-CA` phone types `12,50` and `12.50` interchangeably, and locale parsing silently reads the second as 12.
- **The home currency carries a bare `$`, and every other currency keeps the locale's disambiguated symbol.**
  Java's default gives `1 234,56 $ CA` for CAD in `fr-CA`, tagging the country against dollars nobody on a Quebec phone is comparing to.
  `7,45 $ US` keeps its tag, and that asymmetry is the point.

### The parser refuses to guess

`NEEDS_REVIEW` is not an error state — it is the parser declining, which is the behaviour that keeps the ledger trustworthy.
Never widen a parser to make a review item disappear.
Add a fixture, decide whether the parser can be certain, and if it cannot, leave it for the human.

**Totals exclude `NEEDS_REVIEW` rows** ([ADR-0002](docs/adr/0002-totals-exclude-unreviewed-rows.md)).
A transfer counts by the accounts it joins: into a bank account it is money out, back out of one it is money in, and between cards and cash it is zero — counting a card payment would charge you twice for what it settles ([ADR-0017](docs/adr/0017-a-transfer-counts-by-the-accounts-it-joins.md), `Transaction.signedAmount`).
A category changes none of that, and the rule is written a second time in SQL for reports (`MONEY_OUT`, `MONEY_IN` in `Daos.kt`), so change both or neither.
A total scoped to one account is different: a transfer moves the balance of each account it touches, signed by which end that account is on ([ADR-0006](docs/adr/0006-a-filtered-total-answers-for-one-account.md), `LedgerViewModel.movedOn`).

A report's ranking, calendar and spending comparison count what went out and no income ([ADR-0007](docs/adr/0007-a-report-counts-spending-not-the-balance.md)); its headline is the ledger's balance for the period, with the count and comparison kept under *Dépenses* so they read as spending ([ADR-0009](docs/adr/0009-a-report-says-what-its-balance-is-made-of.md)).

The ledger header's figures always add up to the balance above them: income and expenses, and under an account filter that account's transfers as a figure of their own, never folded into either ([ADR-0008](docs/adr/0008-the-ledger-header-says-what-the-balance-is-made-of.md), `BalanceSummary`).
The headline is this month's, except under a filter with dates, where it is every row shown ([ADR-0014](docs/adr/0014-a-dated-filters-headline-is-the-balance-of-its-dates.md), `LedgerViewModel.headlineRows`).
A budget counts what the category ranking counts, through `ReportRepository.categorySpending` and never a query of its own, and a change applies from its month on, so a past month keeps its amount ([ADR-0023](docs/adr/0023-a-budget-counts-what-a-report-counts-from-the-month-it-was-set.md)).
A row dated after today counts in no figure anywhere until its day — headline, day, selection, report, account balance — and waits in the line above today ([ADR-0015](docs/adr/0015-a-transaction-counts-from-its-day.md)); a new total must stop at today too.

### A category is written onto the row, never read through

A merchant's category — or, for a transfer, the category of the account it goes into — is copied into each transaction as it is recorded; never `COALESCE` it in at read time, or changing a merchant silently rewrites history ([ADR-0010](docs/adr/0010-a-merchants-category-is-written-onto-each-purchase.md)).
Categorising rows in bulk must not stamp `reviewedAtUtcMillis`, which would lock a parser fix out of them (`CategoryDao.categoriseAtMerchant`).
A category's group is the opposite: read through, never copied onto the row, so moving a category moves its history ([ADR-0016](docs/adr/0016-a-categorys-group-is-read-through.md)).
Its icon is read through too, and stored as its Material Symbols name, never a resource id: a name in `CATEGORY_ICONS` is never renamed, or every category carrying it falls back to its initials ([ADR-0020](docs/adr/0020-a-categorys-icon-is-stored-by-name-and-read-through.md)).

### A split is one transaction, worth what its parts are worth

A split's row keeps the whole amount and its parts in `transaction_part` divide it among categories ([ADR-0018](docs/adr/0018-a-split-divides-one-transaction-among-categories.md), `settleCategory`).
A part of an expense may go into another account, and that share is a transfer counted by the accounts it joins, so a total adds up a split's parts as the rows they would be entered apart, never its row's amount ([ADR-0019](docs/adr/0019-a-part-of-a-split-can-go-into-another-account.md)): `Transaction.movements` for the ledger, `MOVEMENTS` in `Daos.kt` for reports, changed together.
The account such a part goes into gains the share, in `AccountDao.allWithCounts`, and a new per-account figure must count it too.
Its row's `categoryId` is null because its parts carry them, so any write that fills in missing categories must pass over a row with parts (`NOT_SPLIT` in `Daos.kt`), or the split is counted under that category twice.

### A recurring transaction is written, never projected

A day a schedule falls on becomes a row once it has come, and nothing counts it before — its next day is only previewed above today; only a typed transaction can repeat, or a captured one would be counted twice ([ADR-0013](docs/adr/0013-a-recurring-transaction-is-written-on-its-day.md), `RecurringRepository.repeat`).
Due days are caught up at process start, midnight and listener bind (`RecurringPosting`); never reach for WorkManager or an alarm to fire them, which would bring in a permission this app refuses.

`source = RECURRING` says a row has a day and no hour, not that a schedule wrote it: a typed row can be marked so and has no `recurringId`, and a schedule's row given an hour is `MANUAL` and keeps its own, so never read one from the other.

### A parser fix repairs history

Bump `NotificationParser.version` on **any** behaviour change.
The upgrade path re-parses every raw event recorded by a lower version, so a fix reaches the purchases already in the ledger instead of only the next ones.
Forgetting the bump is silent: the fix ships and history stays wrong.

`ParserRegistry.fingerprint` is what decides a sweep is needed, and it is sorted by id so that reordering the parser list — a mechanical edit — does not read as a change.

### The listener's package allowlist is data minimisation, not an optimisation

Android hands `PiastreListenerService` every notification on the device: mail subjects, message previews, everything.
`registry.watchedPackages` filters **before the bundle is opened**, so a notification no parser claims leaves no trace at all — not even a discarded row.
Never move that filter later in the method for tidiness, and never read a field before it.

`noteAlive()` is the deliberate exception: it runs ahead of the filter and stores a bare timestamp, nothing else.
Restricting it to Wallet would make a fortnight of cooking at home indistinguishable from a listener Android quietly unbound — which is the exact failure [ADR-0004](docs/adr/0004-capture-health-banner-without-a-notification.md) exists to catch.

### Migrations are historical records

`core/database/schemas/` holds one committed JSON per version, so a migration is reviewable rather than a leap of faith.
`PiastreDatabaseMigrationTest` validates the individual steps and migrates a first-release database all the way to the current schema.

- **A migration builds the schema of its own version and no other.**
  `MIGRATION_6_7` still creates the `card` table that schema 8 goes on to replace with `account`.
  That is correct and must stay: editing it to match today's schema breaks every device upgrading from 6.
- **Never wire a migration to a list that can grow.**
  `MIGRATION_3_4` writes the Wallet package out as a literal rather than reading `ParserRegistry`, so a parser added next year cannot reach back and change what an old upgrade meant.
- **A migration assumes nothing about the data.**
  The 1→2 sweep deletes duplicates before creating the unique index, because creating it over existing duplicates fails outright and leaves the app unable to open its own data.
- **Backfill nulls, not guesses.**
  Zero is a claim, and `NULL` is the truth that nobody has said yet.
- **Android 12 is the floor** (`minSdk 31`), and its SQLite cannot drop a column — dropping one means rebuilding the table, as `MIGRATION_7_8` does.
- **Never add `fallbackToDestructiveMigration`.**
  A missing migration must fail loudly on open, never erase the ledger to get the app running again — the phone holds the only copy of it, as **Validation** explains.

### Data the user gave back

- **The raw log is stripped after 90 days**, rows kept, content removed ([ADR-0001](docs/adr/0001-raw-log-90-day-purge.md)).
  A row whose content is still present is history a parser fix can still repair, so never backfill a redaction time.
- **Export writes both CSV and JSON** ([ADR-0003](docs/adr/0003-export-writes-csv-and-json.md)); JSON is the format import reads.
- **Import merges and never replaces** ([ADR-0005](docs/adr/0005-import-merges-and-never-replaces.md)).
  The app only ever learns to read what it writes; converting from another app's format happens outside the app.

### The `applicationId` is where the ledger lives

Android files the ledger under `io.github.jaytech64.piastre`, so a build with another `applicationId` installs as an empty app beside the real one and carries nothing over ([ADR-0021](docs/adr/0021-depenses-became-piastre-and-the-ledger-crossed-by-hand.md)).
The Kotlin namespace can be renamed freely; the id cannot.

### Every file carries its licence

GPL-3.0-or-later, as an `SPDX-License-Identifier` header above the `package` line.
Spotless enforces it, so a file added without one fails the build rather than quietly shipping unlicensed.
`./gradlew spotlessApply` adds it.

---

## Localisation

English and French, `en` and `fr`, both shipped (`resourceConfigurations` in `app/build.gradle.kts`).
The app targets Quebec, so **`fr_CA` is the case to get right and `en` is the fallback.**

Strings live in the module that draws them, never in `:app`.
A user-visible string added in one language and not the other is an incomplete change — and the French wording is the maintainer's call, not yours.
Google Wallet is « Google Portefeuille » in French, the name the app carries on a Quebec phone; « Wallet » never appears in a French string.

At 200 % a French word squeezed into too little width breaks mid-word, so a row of buttons goes through `ButtonsRow` and a choice among a few through `ChoiceRow`, never a bare `Row` or `SingleChoiceSegmentedButtonRow` (`:core:designsystem`).
A new screen is checked at `fr-CA` 200 % on the emulator before it is done.

`:core:model` has no strings of its own, and `:core:designsystem` carries only the words its own components own — the account dialog, and the names of the account kinds that `accountLabel` falls back to.
`Formatters` has none at all, which is why `dayHeader` takes its "today" and "yesterday" wording as a parameter: a formatter is not a component, and those words belong to the screen that draws them.

---

## Testing

Unit tests only, on the JVM.

The two module kinds do not share a test framework, so check which one you are in before writing an assertion:

| Module kind | Framework | Imports |
|---|---|---|
| `:capture:parsing`, `:core:model` (pure JVM) | `kotlin-test` on the JUnit 5 platform | `kotlin.test.Test`, `kotlin.test.assertEquals` |
| every other module with tests | JUnit 4 under Robolectric | `org.junit.Test`, `org.junit.Assert.assertEquals` |

- **Test names are sentences about behaviour**, in backticks, from the user's side: `` `a row waiting for review moves no balance` ``.
  Not `testAccountBalance`.
- **`CorpusTest` is a contract, not a smoke test.**
  It asserts the exact counts a week of real captured notifications collapses to — 21 → 8, 24 → 10 — with every rejection attributed to a named reason.
  If a parser change moves one of those numbers, the change is wrong until you can say which real notification now reads differently and why that is better.
- **The corpus is redacted real capture.**
  Structure, the gaps between notifications and the duplicate pattern are real; merchants, card digits, dates and which amount went with which purchase are not.
  A new capture is redacted the same way before it is committed (`capture/parsing/src/testFixtures/resources/fixtures/README.md`): the repository is public, and its history keeps whatever lands in it.
  **Real spending never enters the repository.**
- **`inMemoryDatabase()` in `:core:testing` is a real SQLite**, running queries and invalidations on the caller's thread.
  Room's default executor is a thread pool a virtual clock cannot advance, which makes a flow test pass or time out depending on machine load.
  Never swap it back to the default to "fix" a slow test.
- Repository and view-model tests exercise the real DAO through that database, because a fake DAO cannot catch a wrong index, a reserved word, or an upsert resolving on the wrong key.
