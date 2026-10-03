// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.feature.ledger

import io.github.jaytech64.depenses.core.model.Category
import io.github.jaytech64.depenses.core.model.CategoryGroup
import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.Collator
import java.util.Locale

class CategorySectionTest {
    private val collator = Collator.getInstance(Locale.CANADA_FRENCH)

    @Test
    fun `groups and their categories read A to Z in French, accents among their letters, the ungrouped last`() {
        val home = CategoryGroup(1, "Maison")
        val food = CategoryGroup(2, "Alimentation")
        val car = CategoryGroup(3, "Auto")
        val sections =
            categorySections(
                categories =
                    listOf(
                        Category(10, "Restaurants", food.id),
                        Category(11, "Électricité", home.id),
                        Category(12, "Assurance", home.id),
                        Category(13, "Épicerie", food.id),
                        Category(14, "Logement"),
                    ),
                groups = listOf(home, food, car),
                collator = collator,
            )

        assertEquals(
            listOf(
                "Alimentation" to listOf("Épicerie", "Restaurants"),
                "Maison" to listOf("Assurance", "Électricité"),
                null to listOf("Logement"),
            ),
            sections.map { section -> section.group?.name to section.categories.map { it.name } },
        )
    }

    @Test
    fun `with no groups, every category is in one list`() {
        val sections = categorySections(listOf(Category(1, "Épicerie"), Category(2, "Bar")), emptyList(), collator)

        assertEquals(
            listOf(null to listOf("Bar", "Épicerie")),
            sections.map { s ->
                s.group to
                    s.categories.map { it.name }
            },
        )
    }
}
