// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.feature.ledger

import io.github.jaytech64.piastre.core.model.Category
import io.github.jaytech64.piastre.core.model.CategoryGroup
import java.text.Collator

/** A group's categories as a list draws them; a null [group] holds the categories in none. */
internal data class CategorySection(
    val group: CategoryGroup?,
    val categories: List<Category>,
)

/**
 * [categories] under their [groups], both A to Z in [collator]'s order — Épicerie
 * among the E's — and the categories in no group last. A group with no category
 * in [categories] has no section, since a list of categories has nothing to show
 * under it.
 */
internal fun categorySections(
    categories: List<Category>,
    groups: List<CategoryGroup>,
    collator: Collator,
): List<CategorySection> {
    val byGroup = categories.sortedWith(compareBy(collator) { it.name }).groupBy { it.groupId }
    val grouped =
        groups
            .sortedWith(compareBy(collator) { it.name })
            .mapNotNull { group -> byGroup[group.id]?.let { CategorySection(group, it) } }
    val known = groups.mapTo(mutableSetOf()) { it.id }
    val ungrouped = byGroup.filterKeys { it == null || it !in known }.values.flatten()
    return grouped + listOfNotNull(CategorySection(null, ungrouped).takeIf { ungrouped.isNotEmpty() })
}
