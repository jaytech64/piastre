// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.parsing

/**
 * Reads a monetary amount out of localised notification text.
 *
 * This exists as its own unit because the fr-CA form is a minefield:
 *
 *     en-CA   $1,234.56     symbol first, "," groups, "." decimal
 *     fr-CA   1 234,56 $    symbol last,  "," decimal, and the two spaces are
 *                           NOT U+0020 — CLDR uses U+202F (narrow no-break) or
 *                           U+00A0 depending on ICU version.
 *
 * A regex written against a plain space silently fails on a real device, and a
 * naive `toDouble()` on the first digit run records 1234,00 $ instead of
 * 1234,56 $ — a class of bug that stays invisible for months.
 */
object MoneyText {
    /** Every space-like separator ICU may emit, plus the ordinary one. */
    private val SPACE_LIKE =
        charArrayOf(
            '\u0020', // SPACE
            '\u00A0', // NO-BREAK SPACE            — fr-CA grouping, older ICU
            '\u2009', // THIN SPACE
            '\u202F', // NARROW NO-BREAK SPACE     — fr-CA currency spacer, CDLR 42+
            '\u2007', // FIGURE SPACE
        )

    private const val MINOR_UNITS = 100L
    private const val FRACTION_DIGITS = 2

    private fun Char.isSpaceLike(): Boolean = this in SPACE_LIKE

    /** Collapses every space variant to a single ordinary space. */
    fun normaliseSpaces(raw: String): String = raw.map { if (it.isSpaceLike()) ' ' else it }.joinToString("")

    /**
     * Parses a bare amount such as `1,234.56`, `1 234,56` or `7,45` into minor
     * units, without caring which locale produced it.
     *
     * The separator immediately followed by exactly two digits is the decimal
     * separator; every other separator is grouping. That single rule covers both
     * conventions without having to know the locale in advance — which matters,
     * because the notification's language does not always match the device's.
     *
     * Returns null rather than guessing when the shape is not a 2-decimal amount.
     */
    fun parseAmountMinor(raw: String): Long? {
        val cleaned = raw.filterNot { it.isSpaceLike() }
        if (cleaned.isEmpty()) return null
        if (!cleaned.all { it.isDigit() || it == '.' || it == ',' }) return null

        val lastSeparator = cleaned.lastIndexOfAny(charArrayOf('.', ','))
        if (lastSeparator == -1) {
            // A whole amount with no cents, e.g. "20". Accept it as 20,00.
            return cleaned.toLongOrNull()?.let { it * MINOR_UNITS }
        }

        val fraction = cleaned.substring(lastSeparator + 1)
        if (fraction.length != FRACTION_DIGITS || !fraction.all { it.isDigit() }) return null

        val whole = cleaned.substring(0, lastSeparator).filter { it.isDigit() }
        if (whole.isEmpty()) return null

        val wholeValue = whole.toLongOrNull() ?: return null
        return wholeValue * MINOR_UNITS + fraction.toLong()
    }
}
