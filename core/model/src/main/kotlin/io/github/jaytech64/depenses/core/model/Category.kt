// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.model

import java.util.Locale

/**
 * A kind of spending or income you named, such as Épicerie or Hypothèque. It is
 * what a transaction is given; [groupId] only says where it is arranged, and is
 * null while it is in no group.
 *
 * [icon] is the name of the icon you picked for it, null until you do. It is a
 * name and not a picture because this module has no Android: whatever draws it
 * looks the name up, and draws the category's initials for one it does not know.
 */
data class Category(
    val id: Long,
    val name: String,
    val groupId: Long? = null,
    val icon: String? = null,
)

/** What categories are arranged under, such as Maison. A transaction is never given one. */
data class CategoryGroup(
    val id: Long,
    val name: String,
)

/**
 * A group with how many categories it holds and how many confirmed transactions
 * those carry. A null [group] stands for the categories in none.
 */
data class CategoryGroupUsage(
    val group: CategoryGroup?,
    val categoryCount: Int,
    val transactionCount: Int,
)

/**
 * A category with what uses it: its confirmed transactions, and the merchants and
 * accounts that give it automatically.
 */
data class CategoryUsage(
    val category: Category,
    val transactionCount: Int,
    val merchantCount: Int,
    val accountCount: Int,
)

/** A category name as typed, and the form two spellings of it agree on. */
data class CategoryName(
    val displayName: String,
    val normalisedName: String,
) {
    companion object {
        /**
         * Returns null for a name with nothing in it.
         *
         * Case and accents fold for matching only, so `epicerie` finds Épicerie
         * rather than creating a second one beside it, and the name you see is
         * still the one you typed. [MerchantNormaliser] is deliberately not reused:
         * it strips store numbers and corporate suffixes, and `Loisirs 2026` is a
         * category name, not a branch.
         */
        fun of(typed: String): CategoryName? {
            val display = typed.replace(WHITESPACE_RUN, " ").trim()
            if (display.isEmpty()) return null
            return CategoryName(display, display.foldedForMatching().uppercase(Locale.ROOT))
        }

        private val WHITESPACE_RUN = Regex("\\s+")
    }
}
