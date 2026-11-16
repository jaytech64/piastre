// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.feature.reports

import io.github.jaytech64.piastre.core.data.CategorySpending
import io.github.jaytech64.piastre.core.data.PeriodSpending
import io.github.jaytech64.piastre.core.model.Days
import io.github.jaytech64.piastre.core.model.Money
import java.time.LocalDate
import java.time.YearMonth

/** One month of the year a trend draws. */
data class TrendMonth(
    val month: YearMonth,
    /** Null before the ledger began: not yet, which is a different fact from nothing. */
    val spent: Money?,
    val count: Int,
    /** The same month a year earlier, or null when the ledger does not reach back to it. */
    val yearBefore: Money?,
    /** The amount the subject's budget judged the month against, or null when it had none. */
    val budget: Money?,
    /** The month you are living in, counted so far. */
    val running: Boolean,
) {
    /**
     * Null for the running month: half a month against a whole one a year ago
     * would read as a fall every day until the 1st, for the reason the average
     * leaves the running month out.
     */
    val comparison: Comparison?
        get() = if (spent == null || running) null else yearBefore?.let { comparisonOf(spent, it) }
}

/** Twelve months ending with [end], and what they average. */
data class TrendYear(
    val end: YearMonth,
    /** Oldest first, always twelve. */
    val months: List<TrendMonth>,
    /** Of the complete months alone, or null when there is none yet. */
    val average: Money?,
    val completeMonths: Int,
    val canStepBack: Boolean,
    val canStepForward: Boolean,
    /** The window's transactions that no figure here counted, because they were in another currency. */
    val otherCurrencyCount: Int,
)

/**
 * The twelve months ending with [end], read from [spent], which holds the
 * twelve before them too so that each month can stand beside its year before.
 *
 * A month before [began] — the month of the ledger's first row — has no bar
 * and stays out of the average, since the ledger cannot say what was spent
 * then. A month after it with nothing in [spent] really is zero. The running
 * month is counted so far and stays out of the average too, so the headline
 * does not fall every morning until the 1st.
 */
internal fun trendYear(
    end: YearMonth,
    today: LocalDate,
    began: YearMonth?,
    spent: Map<YearMonth, PeriodSpending>,
    budgetIn: (YearMonth) -> Money?,
    otherCurrencyCount: Int,
    currency: String = Money.CAD,
): TrendYear {
    val current = YearMonth.from(today)
    val zero = Money(0, currency)

    fun reached(month: YearMonth) = began != null && month >= began

    val months =
        (MONTHS_IN_YEAR - 1 downTo 0).map { back ->
            val month = end.minusMonths(back.toLong())
            val yearBefore = month.minusYears(1)
            TrendMonth(
                month = month,
                spent = if (reached(month)) spent[month]?.total ?: zero else null,
                count = spent[month]?.count ?: 0,
                yearBefore = if (reached(yearBefore)) spent[yearBefore]?.total ?: zero else null,
                budget = budgetIn(month),
                running = month == current,
            )
        }
    val complete = months.filter { it.spent != null && it.month < current }
    return TrendYear(
        end = end,
        months = months,
        average =
            if (complete.isEmpty()) {
                null
            } else {
                Money(complete.sumOf { checkNotNull(it.spent).minor } / complete.size, currency)
            },
        completeMonths = complete.size,
        canStepBack = began != null && months.first().month > began,
        canStepForward = end < current,
        otherCurrencyCount = otherCurrencyCount,
    )
}

/**
 * A row of a comparison: a group with its categories under it, a category in
 * no group, or what has no category, which has neither id and no [name].
 */
data class ComparedSpending(
    val groupId: Long?,
    val categoryId: Long?,
    val name: String?,
    val first: Money,
    val second: Money,
    /** The second span against the first, per day when their lengths differ. */
    val change: Comparison,
    /** Heaviest first, and empty but for a group. */
    val categories: List<ComparedSpending> = emptyList(),
)

/** Two spans of days and where the money went in each. */
data class TrendsCompare(
    val first: Days,
    val second: Days,
    /** How many of each span's days have come, which is what a per-day figure divides by. */
    val firstDays: Long,
    val secondDays: Long,
    val everything: ComparedSpending,
    /** Groups and categories in no group, heaviest first, then what has no category. */
    val rows: List<ComparedSpending>,
    val otherCurrencyCount: Int,
) {
    val perDay: Boolean get() = firstDays != secondDays
}

/**
 * What [first] and [second] spent, side by side, arranged as the ranking
 * arranges one period: groups opening onto their categories, then the
 * categories in no group, then what has no category. A row is ordered by the
 * larger of its two figures, so one that stopped stays where it was.
 */
internal fun compared(
    first: List<CategorySpending>,
    second: List<CategorySpending>,
    firstDays: Long,
    secondDays: Long,
    currency: String = Money.CAD,
): Pair<ComparedSpending, List<ComparedSpending>> {
    fun row(
        groupId: Long?,
        categoryId: Long?,
        name: String?,
        a: Long,
        b: Long,
        categories: List<ComparedSpending> = emptyList(),
    ) = ComparedSpending(
        groupId = groupId,
        categoryId = categoryId,
        name = name,
        first = Money(a, currency),
        second = Money(b, currency),
        change = comparisonOf(Money(b, currency), Money(a, currency), secondDays, firstDays),
        categories = categories,
    )

    fun List<CategorySpending>.minorOf(categoryId: Long?) =
        filter { it.category?.id == categoryId }.sumOf { it.total.minor }

    val heaviestFirst = compareByDescending<ComparedSpending> { maxOf(it.first.minor, it.second.minor) }
    val categorised =
        (first + second)
            .mapNotNull { spent ->
                spent.category?.let { it to spent.group }
            }.distinctBy { it.first.id }
    val categoryRows =
        categorised.map { (category, group) ->
            group to row(group?.id, category.id, category.name, first.minorOf(category.id), second.minorOf(category.id))
        }
    val grouped =
        categoryRows
            .filter { (group, _) -> group != null }
            .groupBy({ checkNotNull(it.first) }, { it.second })
            .map { (group, rows) ->
                row(
                    group.id,
                    null,
                    group.name,
                    rows.sumOf {
                        it.first.minor
                    },
                    rows.sumOf { it.second.minor },
                    rows.sortedWith(heaviestFirst),
                )
            }
    val alone = categoryRows.filter { (group, _) -> group == null }.map { it.second }
    val uncategorised =
        row(null, null, null, first.minorOf(null), second.minorOf(null))
            .takeIf { it.first.minor != 0L || it.second.minor != 0L }
    val everything = row(null, null, null, first.sumOf { it.total.minor }, second.sumOf { it.total.minor })
    return everything to (grouped + alone).sortedWith(heaviestFirst) + listOfNotNull(uncategorised)
}

private const val MONTHS_IN_YEAR = 12
