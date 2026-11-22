// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.feature.reports

import io.github.jaytech64.piastre.core.data.MerchantSpending
import io.github.jaytech64.piastre.core.model.DaySpan
import io.github.jaytech64.piastre.core.model.Money
import java.time.LocalDate
import java.time.Year
import java.time.YearMonth
import kotlin.math.abs

/** What a merchant report is read over: a calendar month or a calendar year. */
enum class MerchantSpanUnit { MONTH, YEAR }

/**
 * A calendar month or year, anchored on its first day. A week or a single day
 * is not offered: a merchant visited once a fortnight has no average over
 * either, and a ranking of three purchases says nothing a ledger does not.
 */
data class MerchantSpan(
    val unit: MerchantSpanUnit,
    override val start: LocalDate,
) : DaySpan {
    override val endInclusive: LocalDate
        get() =
            when (unit) {
                MerchantSpanUnit.MONTH -> YearMonth.from(start).atEndOfMonth()
                MerchantSpanUnit.YEAR -> Year.from(start).atMonth(DECEMBER).atEndOfMonth()
            }

    val month: YearMonth get() = YearMonth.from(start)

    fun previous(): MerchantSpan = shiftedBy(-1)

    fun next(): MerchantSpan = shiftedBy(1)

    private fun shiftedBy(units: Long): MerchantSpan =
        copy(
            start =
                when (unit) {
                    MerchantSpanUnit.MONTH -> start.plusMonths(units)
                    MerchantSpanUnit.YEAR -> start.plusYears(units)
                },
        )

    companion object {
        private const val DECEMBER = 12

        fun containing(
            unit: MerchantSpanUnit,
            date: LocalDate,
        ): MerchantSpan =
            MerchantSpan(
                unit = unit,
                start =
                    when (unit) {
                        MerchantSpanUnit.MONTH -> date.withDayOfMonth(1)
                        MerchantSpanUnit.YEAR -> date.withDayOfYear(1)
                    },
            )
    }
}

/** What the merchants are ranked by. */
enum class MerchantRank { TOTAL, VISITS, AVERAGE }

/** A merchant's line in the report. */
data class MerchantLine(
    val merchantId: Long,
    val name: String,
    val total: Money,
    val visits: Int,
    /** [total] over [visits], to the nearest cent. */
    val average: Money,
    /** Of the span's spending, in `0f..1f`, as a report's ranking draws its bars. */
    val share: Float,
)

/** Every merchant money went out at, ranked, and what went out with none. */
data class MerchantRanking(
    val lines: List<MerchantLine>,
    /** Every row the merchants above leave out because none was recorded, transfers included. */
    val withoutMerchant: Money,
    /** What the span spent in all, which [lines] and [withoutMerchant] add up to. */
    val total: Money,
)

/**
 * The rows [merchantSpending][io.github.jaytech64.piastre.core.data.ReportRepository.merchantSpending]
 * gives for a span, ranked by [rank].
 *
 * Ties fall to the total, then to the name, so the order never shuffles between
 * two readings of the same ledger.
 */
fun merchantRanking(
    rows: List<MerchantSpending>,
    rank: MerchantRank,
    currency: String = Money.CAD,
): MerchantRanking {
    val total = rows.sumOf { it.total.minor }
    val lines =
        rows.mapNotNull { row ->
            val id = row.merchantId ?: return@mapNotNull null
            val name = row.name ?: return@mapNotNull null
            MerchantLine(
                merchantId = id,
                name = name,
                total = row.total,
                visits = row.visits,
                average = row.total.averageOver(row.visits),
                share = if (total == 0L) 0f else row.total.minor.toFloat() / total,
            )
        }
    val byTotalThenName = compareByDescending<MerchantLine> { it.total.minor }.thenBy { it.name.lowercase() }
    val order =
        when (rank) {
            MerchantRank.TOTAL -> byTotalThenName
            MerchantRank.VISITS -> compareByDescending<MerchantLine> { it.visits }.then(byTotalThenName)
            MerchantRank.AVERAGE -> compareByDescending<MerchantLine> { it.average.minor }.then(byTotalThenName)
        }
    return MerchantRanking(
        lines = lines.sortedWith(order),
        withoutMerchant =
            Money(rows.filter { it.merchantId == null || it.name == null }.sumOf { it.total.minor }, currency),
        total = Money(total, currency),
    )
}

/**
 * This amount shared among [count] visits, rounded half away from zero to the
 * cent, in whole minor units from start to end: an average is the one figure
 * here that divides, and it divides a [Long].
 */
internal fun Money.averageOver(count: Int): Money {
    if (count <= 0) return copy(minor = 0)
    val quotient = minor / count
    val remainder = minor % count
    val rounded =
        when {
            2 * abs(remainder) < count -> quotient
            minor >= 0 -> quotient + 1
            else -> quotient - 1
        }
    return copy(minor = rounded)
}
