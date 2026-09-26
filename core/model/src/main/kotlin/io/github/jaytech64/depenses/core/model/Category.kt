// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.model

import java.util.Locale

/** A kind of spending or income you named, such as Épicerie or Hypothèque. */
data class Category(
    val id: Long,
    val name: String,
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
