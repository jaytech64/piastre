# 16. A category's group is read through, never written onto the row

- **Status:** Accepted — implemented.
  Its clause on transfers under a group filter is replaced by [ADR-0017](0017-a-transfer-counts-by-the-accounts-it-joins.md): a group still adds up to its categories, and a transfer counts in both by the accounts it joins.
- **Decided:** 2026-10-02, while planning category groups (forks A to I).
- **Touches:** `:core:database` (`CategoryGroupEntity`, `CategoryEntity.groupId`, `CategoryGroupDao`, `MIGRATION_13_14`), `:core:data` (`CategoryGroupRepository`, `LedgerExport`, `LedgerImport`), `:feature:settings`, `:feature:ledger`.

## Context

The list of categories outgrew the Settings screen it shared, and the maintainer asked to arrange them as one parent over many children: *Maison › Hypothèque*, *Maison › Électricité*.
A transaction is given a child; the parent only organises them.

[ADR-0010](0010-a-merchants-category-is-written-onto-each-purchase.md) settled that a merchant's category is copied onto each purchase and never read through, because changing the merchant must not rewrite history.
That leaves the same question one level up: when a category moves from *Maison* to *Auto*, do the purchases already filed under it move too?

## Decision

**A transaction carries its category alone, and the category's group is read through wherever it is shown.**

- The parent is a *group* (« groupe »), in a table of its own, `category_group`.
  Only a category points at it, through a nullable `category.groupId`, so a transaction cannot be given a group: the schema says what the product says.
- Moving a category to another group moves its whole history with it, at once and with nothing rewritten.
  A group is how you arrange categories, not a fact about any purchase, so this is the behaviour wanted — the opposite of ADR-0010's case, where the merchant's category is a fact recorded at the time.
- A category may be in no group.
  The upgrade leaves every existing one there, since nobody has said where any of them belongs, and deleting a group puts its categories back there; no transaction loses its category either way.
- A category's name stays unique across every group, so a row title, a filter chip and the CSV can show the bare name without ambiguity.
- Under a group filter, a transfer counts as money that left, as it does under one of its categories ([ADR-0011](0011-a-transfer-under-a-category-is-money-that-left.md)), so a group's total is always its categories' added up.
- An import puts a category in the file's group only while it is in none here, so merging never moves what you filed ([ADR-0005](0005-import-merges-and-never-replaces.md)).

Rejected: a self-referencing category with a parent column.
It would let a transaction point at a parent, and every query that lists categories would have to remember to leave parents out.

Rejected: copying the group onto each transaction, as ADR-0010 copies the category.
Rearranging categories would then leave history behind under the old arrangement, and the export would carry two answers for one row's group.

## Consequences

The ledger's row title, its filter chip for a single category and the form's choice are unchanged; groups appear as headings in the form's and the filter's lists, and as the pages of Settings › Categories.

The CSV gains a `group` column, last, so every earlier column keeps its position.
The JSON gains `categoryGroups` and a `groupId` on each category, inside format version 2; a file written before them imports as it always did.

Reports still do not read categories, so they read no group either.

## Revision — 2026-10-05

Reports now read categories, and so they read groups: a report ranks groups and opens one onto its categories.
The group is joined when the report is read, in `ReportDao.categoryTotals`, so a category moved to another group takes its place in every past report with it — the decision above, applied to one more screen.
`` `a category moved to another group takes what went to it along` `` in `ReportRepositoryTest` holds it.

## Where this lives in the code

- `CategoryGroupRepository` in `core/data/src/main/kotlin/.../CategoryGroupRepository.kt` — the groups, and which category sits in which.
- `MIGRATION_13_14` in `core/database/src/main/kotlin/.../DepensesDatabase.kt`, and `` `migrating to schema 14 leaves every category in no group` `` in `DepensesDatabaseMigrationTest`.
- `balanceMovedOn` in `feature/ledger/src/main/kotlin/.../LedgerViewModel.kt`, and `` `a group shows every category in it, and adds up to them` `` in `LedgerFilterTest`.
