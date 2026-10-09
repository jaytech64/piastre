# 20. A category's icon is stored by name and read through

- **Status:** Accepted — implemented.
- **Decided:** 2026-10-08, while planning category icons.
- **Touches:** `:core:database` (`CategoryEntity.icon`, `MIGRATION_18_19`), `:core:data` (`CategoryRepository`, `LedgerExport`, `LedgerImport`), `:core:designsystem` (`CATEGORY_ICONS`, `Mark`, `CategoryDialog`), `:feature:ledger` (`Transaction.mark`), `:feature:settings`, `tools/`.

## Context

A ledger row started with a circle holding its category's initials.
The maintainer asked for an icon there instead, so a row reads at a glance, and asked whether common icons could be brought in in bulk.

The app has no network, so every icon it can show has to be in the build.
What a category remembers about its icon then has to survive two things: the next build, and an export read back on another phone.

## Decision

**A category carries the name of its icon, and a row reads it through its category.**

- The icons are Material Symbols (Rounded, filled, weight 400), bundled as vector drawables.
  `tools/import-category-icons.sh` fetches the ones listed in `tools/category-icons.txt` on a development machine, and the drawables are committed; the app fetches nothing.
- `category.icon` holds the Material Symbols name, such as `shopping_cart`, never a resource id.
  An id changes from one build to the next; a name means the same thing in a database, in an export and in a later build.
- A name is never renamed once shipped, and a name the build does not know draws the category's initials and is kept as stored.
  A file from a newer build therefore loses nothing by passing through an older one.
- The icon is read through, like the category's name and its group ([ADR-0016](0016-a-categorys-group-is-read-through.md)): changing a category's icon changes every row it has.
  It is how a category looks, not a fact about any purchase.
- Nothing is guessed.
  The upgrade gives no category an icon, and one without an icon keeps its initials until one is picked.
- A split in two shows both categories, the circle cut on its diagonal with the first part in the upper left; a half whose category has no icon shows that category's first letter.
  A split in more parts shows one icon, `call_split`, which a category cannot be given.
- An import gives a category the file's icon only while it has none here, so merging never replaces one you picked ([ADR-0005](0005-import-merges-and-never-replaces.md)).

Rejected: `material-icons-extended`.
It is frozen upstream and adds some two thousand icons to every debug build for the eighty used.

Rejected: an emoji typed from the keyboard.
It needs no assets, but it is drawn by the phone's own font, so the same category would look different from one Android version to the next and unlike every other icon in the app.

Rejected: merchants' own logos.
There is no offline source for them, and they are trademarks.

## Consequences

The picker's grid has no label per icon, so TalkBack cannot tell one from another there.
Eighty names in two languages was judged too much copy for one pick; naming them is the change to make if that proves wrong.

The export gains an optional `icon` on each category, inside format version 4.
A reader that skips it shows initials, and no amount in the file changes meaning, so the version did not move.

The icon shows on ledger rows and in Settings › Categories.
The form's category list, the filter chips and the report's ranking still show names alone.

## Where this lives in the code

- `CATEGORY_ICONS` in `core/designsystem/src/main/kotlin/.../CategoryIcons.kt` — the names, and `CategoryIconsTest` holding the table to the drawables.
- `Mark` and `MarkCircle` in `core/designsystem/src/main/kotlin/.../Mark.kt` — what a circle can show, and how it is drawn.
- `Transaction.mark` in `feature/ledger/src/main/kotlin/.../RowNames.kt`, and `LedgerMarkTest`.
- `MIGRATION_18_19` in `core/database/src/main/kotlin/.../PiastreDatabase.kt`, and `` `migrating to schema 19 keeps every category, and none of them has an icon` `` in `PiastreDatabaseMigrationTest`.
- `` `an icon comes back with an import, but never over one you picked` `` in `LedgerImportTest`.
