// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.core.data

import io.github.jaytech64.piastre.core.database.CategoryDao
import io.github.jaytech64.piastre.core.database.CategoryGroupDao
import io.github.jaytech64.piastre.core.database.CategoryGroupEntity
import io.github.jaytech64.piastre.core.database.CategoryWithCounts
import io.github.jaytech64.piastre.core.model.CategoryGroup
import io.github.jaytech64.piastre.core.model.CategoryGroupUsage
import io.github.jaytech64.piastre.core.model.CategoryName
import io.github.jaytech64.piastre.core.model.CategoryUsage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The groups categories are arranged under, and which category sits in which.
 *
 * Nothing here touches a transaction: a transaction is given a category, and its
 * group is read through wherever it is shown, so moving a category or deleting a
 * group rearranges history without rewriting any of it.
 */
@Singleton
class CategoryGroupRepository
    @Inject
    constructor(
        private val groups: CategoryGroupDao,
        private val categories: CategoryDao,
    ) {
        /** Every group, A to Z by its folded name, for a field that picks one. */
        fun all(): Flow<List<CategoryGroup>> = groups.all().map { rows -> rows.map(CategoryGroupEntity::toDomain) }

        /**
         * Every group with what it holds, and last the categories in none, while
         * there are any. A group's transactions are its categories' added up, so the
         * two figures can never disagree.
         */
        fun usage(): Flow<List<CategoryGroupUsage>> =
            combine(all(), categories.allWithCounts()) { groups, rows ->
                val byGroup = rows.map(CategoryWithCounts::toDomain).groupBy { it.category.groupId }
                val ungrouped = byGroup[null].orEmpty()
                groups.map { usageOf(it, byGroup[it.id].orEmpty()) } +
                    listOfNotNull(usageOf(null, ungrouped).takeIf { ungrouped.isNotEmpty() })
            }

        /** The categories in [groupId], or in none when it is null, for a group's page. */
        fun categoriesIn(groupId: Long?): Flow<List<CategoryUsage>> =
            categories.allWithCounts().map { rows ->
                rows.map(CategoryWithCounts::toDomain).filter { it.category.groupId == groupId }
            }

        /**
         * The group a typed name belongs to, created when there is none yet; null
         * for a name with nothing in it. A group's name folds as a category's does.
         */
        suspend fun named(typed: String): Long? {
            val name = CategoryName.of(typed) ?: return null
            groups.byNormalisedName(name.normalisedName)?.let { return it.id }
            groups.insert(CategoryGroupEntity(name = name.displayName, normalisedName = name.normalisedName))
            return groups.byNormalisedName(name.normalisedName)?.id
        }

        /** False, changing nothing, when the name is blank or another group already answers to it. */
        suspend fun rename(
            id: Long,
            typed: String,
        ): Boolean {
            val name = CategoryName.of(typed) ?: return false
            return groups.rename(id, name.displayName, name.normalisedName) == 1
        }

        /** Its categories are left in none, and every transaction keeps its category. */
        suspend fun delete(id: Long) = groups.delete(id)

        /** Null takes the category out of any group. */
        suspend fun move(
            categoryId: Long,
            groupId: Long?,
        ) = categories.moveToGroup(categoryId, groupId)

        /**
         * Null takes the category's icon away, and its rows go back to its initials.
         * It sits beside [move] because the two are alike: how a category is shown,
         * read through it, and nothing about what was filed under it.
         */
        suspend fun setIcon(
            categoryId: Long,
            icon: String?,
        ) = categories.setIcon(categoryId, icon)

        /** For an import: puts the category in [groupId] only while it is in none (ADR-0005). */
        internal suspend fun groupIfNone(
            categoryId: Long,
            groupId: Long,
        ) = categories.groupIfNone(categoryId, groupId)
    }

private fun CategoryGroupEntity.toDomain() = CategoryGroup(id = id, name = name)

private fun usageOf(
    group: CategoryGroup?,
    usages: List<CategoryUsage>,
) = CategoryGroupUsage(group, categoryCount = usages.size, transactionCount = usages.sumOf { it.transactionCount })
