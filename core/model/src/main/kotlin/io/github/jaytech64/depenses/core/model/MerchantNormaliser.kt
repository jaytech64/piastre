// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.model

import java.text.Normalizer
import java.util.Locale

/** A raw merchant string reduced to something two sightings can agree on. */
data class MerchantName(
    val displayName: String,
    val normalisedName: String,
)

/**
 * Turns the payment network's merchant string into a merchant.
 *
 * "Top 5 merchants" is meaningless if one chain appears once per storefront, so
 * numbered branches collapse: `MARCHE ST JOSEPH  1234` and `MARCHE ST JOSEPH
 * 3011` are the same merchant, and the ranking answers "where does my money go"
 * rather than "which door did I walk through". The branch is not lost —
 * `merchantRaw` keeps it on every transaction, so a per-location view later needs
 * no migration.
 *
 * Every rule below except [AGGREGATOR_PREFIXES] fires on a string that actually
 * appears in the captured corpus.
 */
object MerchantNormaliser {
    fun normalise(raw: String): MerchantName? {
        val folded = raw.replace('\u2019', '\'').replace('\u02BC', '\'')
        val stripped =
            folded
                .let(::stripAggregatorPrefix)
                .let(::stripStoreNumber)
                .let(::stripCorporateSuffix)
                .replace(WHITESPACE_RUN, " ")
                .trim()

        // A merchant whose whole name looked like a store number keeps its name.
        val display = stripped.ifEmpty { folded.replace(WHITESPACE_RUN, " ").trim() }
        if (display.isEmpty()) return null

        return MerchantName(displayName = display, normalisedName = foldForMatching(display))
    }

    /**
     * Store numbers are stripped before whitespace is collapsed, and that order is
     * load-bearing.
     *
     * `EPICERIE NORD  0042` separates the number with a double space. Collapse
     * first and it becomes `EPICERIE NORD 0042`, at which point the only rule that
     * still matches would also eat the `24` in a legitimate `DEPANNEUR 24`. The
     * double space is the evidence that the trailing digits are a branch, so it has
     * to be spent before it is destroyed.
     */
    private fun stripStoreNumber(name: String): String = STORE_NUMBER.replace(name, "")

    private fun stripAggregatorPrefix(name: String): String = AGGREGATOR_PREFIXES.replace(name, "")

    private fun stripCorporateSuffix(name: String): String = CORPORATE_SUFFIX.replace(name, "")

    /**
     * Accents fold for matching only, never for display: `MARCHE` and `MARCHÉ` are
     * one merchant, and the name you see is still the one that was captured.
     */
    private fun foldForMatching(name: String): String =
        Normalizer
            .normalize(name, Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")
            .uppercase(Locale.ROOT)

    private val WHITESPACE_RUN = Regex("\\s+")
    private val COMBINING_MARKS = Regex("\\p{Mn}+")

    /** `#10593`, `# 17`, `- 1187`, and a double space before digits. */
    private val STORE_NUMBER = Regex("(?:\\s*#\\s*\\d+|\\s+-\\s*\\d+|\\s{2,}\\d{2,})\\s*$")

    private val CORPORATE_SUFFIX =
        Regex("\\s+(?:INC|LTD|LTEE|LT\u00c9E|ENR|SENC|CORP)\\.?\\s*$", RegexOption.IGNORE_CASE)

    /**
     * Not observed in 45 real captures — kept because the cost is one alternation
     * and a card processor can start prefixing at any time.
     */
    private val AGGREGATOR_PREFIXES = Regex("^(?:SQ|TST|PAYPAL|SP)\\s?\\*\\s*", RegexOption.IGNORE_CASE)
}
