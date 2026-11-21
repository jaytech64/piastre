// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.feature.reports

import io.github.jaytech64.piastre.core.model.Money

/**
 * How a figure compares with the same figure for an earlier span.
 *
 * States rather than a number, because most of them have no percentage to
 * report, and printing one anyway would be a fabrication: « ▲ 0 % » says a
 * figure rose by nothing.
 */
sealed interface Comparison {
    /** The figure was zero both times; there is nothing to compare. */
    data object NothingEither : Comparison

    /** The figure was zero the earlier time, so the change is not a percentage of anything. */
    data object NothingBefore : Comparison

    /** The figure is exactly what it was. */
    data object Unchanged : Comparison

    /** The figure moved by less than a whole percent, which is too little to judge and too much to call the same. */
    data class Slight(
        val up: Boolean,
    ) : Comparison

    /** Never zero. Negative is less than the earlier time, which for spending is the good direction. */
    data class Change(
        val percent: Int,
    ) : Comparison
}

/**
 * [now] against [before] as a whole percent, in `Long` arithmetic the whole way.
 *
 * When the two spans are of different lengths, [nowDays] and [beforeDays] make
 * it a comparison of what each came to per day, so that half a month against a
 * whole one does not read as a fall nobody made. Equal lengths cancel out, and
 * the change is then of the totals themselves.
 *
 * [Money] is exact minor units and never becomes a `Double`; a percentage is a
 * ratio, so it converts at the edge and comes straight back as an `Int`.
 */
internal fun comparisonOf(
    now: Money,
    before: Money,
    nowDays: Long = 1,
    beforeDays: Long = 1,
): Comparison {
    require(nowDays > 0 && beforeDays > 0) { "A span compared per day has at least one day: $nowDays, $beforeDays" }
    val moved = now.minor * beforeDays - before.minor * nowDays
    val percent = if (before.minor == 0L) 0 else ((moved * PERCENT) / (before.minor * nowDays)).toInt()
    return when {
        before.minor == 0L -> if (now.minor == 0L) Comparison.NothingEither else Comparison.NothingBefore
        moved == 0L -> Comparison.Unchanged
        percent == 0 -> Comparison.Slight(up = moved > 0)
        else -> Comparison.Change(percent)
    }
}

private const val PERCENT = 100L
