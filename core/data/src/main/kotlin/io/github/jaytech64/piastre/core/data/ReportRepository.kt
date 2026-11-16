// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.core.data

import io.github.jaytech64.piastre.core.database.ReportDao
import io.github.jaytech64.piastre.core.database.SPENT_ON_CATEGORY
import io.github.jaytech64.piastre.core.database.SPENT_ON_EVERYTHING
import io.github.jaytech64.piastre.core.database.SPENT_ON_GROUP
import io.github.jaytech64.piastre.core.database.SPENT_ON_NO_CATEGORY
import io.github.jaytech64.piastre.core.model.Category
import io.github.jaytech64.piastre.core.model.CategoryGroup
import io.github.jaytech64.piastre.core.model.DaySpan
import io.github.jaytech64.piastre.core.model.Days
import io.github.jaytech64.piastre.core.model.Money
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What went to one category over a period. A null [category] is everything that
 * was given none; a null [group] is a category in no group.
 */
data class CategorySpending(
    val category: Category?,
    val group: CategoryGroup?,
    val total: Money,
    val count: Int,
)

/**
 * What a figure counts the spending of: all of it, one category, one group as
 * its categories are grouped now, or what was given no category.
 */
sealed interface SpentOn {
    data object Everything : SpentOn

    data class OneCategory(
        val categoryId: Long,
    ) : SpentOn

    data class OneGroup(
        val groupId: Long,
    ) : SpentOn

    data object NoCategory : SpentOn
}

/** What went out over a period, and how many transactions that was. */
data class PeriodSpending(
    val total: Money,
    val count: Int,
)

/**
 * The reading side of the ledger: totals, rankings and per-day sums, all grouped
 * in SQL on an indexed column rather than in Kotlin over the whole table.
 *
 * Kept apart from [TransactionRepository] because nothing here reads or writes a
 * transaction — it reads aggregates, and a span of days is the only thing it is
 * ever asked about.
 *
 * Every query keys on one currency. That is not an oversight: converting needs a
 * rate, a rate needs a network, and this app has none. [otherCurrencyCount] is
 * how a screen says so out loud.
 *
 * A transfer counts by the two accounts it joins, as it does in the ledger: one
 * into a bank account is spending, one back out of it is income, and any other
 * is in no figure (ADR-0017).
 *
 * Every figure counts a period up to today and no further: a transaction dated
 * after today counts from its day, as it does in the ledger (ADR-0015), so each
 * function takes today alongside the period.
 */
@Singleton
class ReportRepository
    @Inject
    constructor(
        private val reports: ReportDao,
    ) {
        /**
         * What went out on each day of [span], days with no spending absent rather than
         * present as zero — "nothing" and "not yet" are different facts, and only the
         * caller knows which one an absent day is.
         */
        fun dailyTotals(
            span: DaySpan,
            today: LocalDate,
            currency: String = Money.CAD,
        ): Flow<Map<LocalDate, Money>> =
            reports
                .dailyTotals(span.start.epochDay, span.lastDayThrough(today), currency)
                .map { rows ->
                    rows.associate { LocalDate.ofEpochDay(it.day.toLong()) to Money(it.totalMinor, currency) }
                }

        /**
         * What went out over the period, and in how many transactions, in one pass.
         *
         * Read from the per-day rows rather than from `totalForRange`, because those
         * rows already carry both numbers and a second query would re-read what the
         * first one just returned.
         */
        fun spending(
            span: DaySpan,
            today: LocalDate,
            currency: String = Money.CAD,
        ): Flow<PeriodSpending> =
            reports
                .dailyTotals(span.start.epochDay, span.lastDayThrough(today), currency)
                .map { rows ->
                    PeriodSpending(
                        total = Money(rows.sumOf { it.totalMinor }, currency),
                        count = rows.sumOf { it.count },
                    )
                }

        /**
         * A period's total alone, for the period a report compares against. The
         * comparison needs no counts and no breakdown, so it does not pay for them.
         */
        fun total(
            span: DaySpan,
            today: LocalDate,
            currency: String = Money.CAD,
        ): Flow<Money> =
            reports
                .totalForRange(span.start.epochDay, span.lastDayThrough(today), currency)
                .map { Money(it ?: 0L, currency) }

        /**
         * What came in over [span], money moved back out of a bank account
         * included. A report reads it beside the spending to say what the period's
         * balance is made of; nothing else on this repository counts an income.
         */
        fun income(
            span: DaySpan,
            today: LocalDate,
            currency: String = Money.CAD,
        ): Flow<Money> =
            reports
                .incomeForRange(span.start.epochDay, span.lastDayThrough(today), currency)
                .map { Money(it ?: 0L, currency) }

        /**
         * What [spending] counts, by category, in no particular order: the rows add
         * up to its total and its count. Each category comes with the group it is
         * in now, so a report follows a category that was moved (ADR-0016).
         */
        fun categorySpending(
            span: DaySpan,
            today: LocalDate,
            currency: String = Money.CAD,
        ): Flow<List<CategorySpending>> =
            reports
                .categoryTotals(span.start.epochDay, span.lastDayThrough(today), currency)
                .map { rows ->
                    rows.map { row ->
                        val groupId = row.groupId
                        val groupName = row.groupName
                        val categoryId = row.categoryId
                        val categoryName = row.categoryName
                        CategorySpending(
                            category =
                                if (categoryId != null && categoryName != null) {
                                    Category(categoryId, categoryName, groupId)
                                } else {
                                    null
                                },
                            group =
                                if (groupId != null &&
                                    groupName != null
                                ) {
                                    CategoryGroup(groupId, groupName)
                                } else {
                                    null
                                },
                            total = Money(row.totalMinor, currency),
                            count = row.count,
                        )
                    }
                }

        /**
         * What [categorySpending] counts for [on], in each calendar month from
         * [from] through [through], and up to today in the month it falls in.
         * A month with nothing spent is absent rather than zero, as a day is in
         * [dailyTotals]; whether that means nothing or not yet is [firstDay]'s
         * to say.
         */
        fun monthlySpending(
            from: YearMonth,
            through: YearMonth,
            on: SpentOn,
            today: LocalDate,
            currency: String = Money.CAD,
        ): Flow<Map<YearMonth, PeriodSpending>> {
            val (onKind, onId) =
                when (on) {
                    SpentOn.Everything -> SPENT_ON_EVERYTHING to 0L
                    is SpentOn.OneCategory -> SPENT_ON_CATEGORY to on.categoryId
                    is SpentOn.OneGroup -> SPENT_ON_GROUP to on.groupId
                    SpentOn.NoCategory -> SPENT_ON_NO_CATEGORY to 0L
                }
            val span = Days(from.atDay(1), through.atEndOfMonth())
            return reports
                .monthlyTotals(span.start.epochDay, span.lastDayThrough(today), currency, onKind, onId)
                .map { rows ->
                    rows.associate {
                        YearMonth.parse(it.month) to
                            PeriodSpending(Money(it.totalMinor, currency), it.count)
                    }
                }
        }

        /** The day of the ledger's first confirmed row, or null while it has none. */
        fun firstDay(): Flow<LocalDate?> =
            reports.firstConfirmedDay().map { day ->
                day?.let { LocalDate.ofEpochDay(it.toLong()) }
            }

        /** The rows in [span] that every other figure on this repository left out for their currency. */
        fun otherCurrencyCount(
            span: DaySpan,
            today: LocalDate,
            currency: String = Money.CAD,
        ): Flow<Int> = reports.otherCurrencyCount(span.start.epochDay, span.lastDayThrough(today), currency)
    }

/**
 * `localDateEpochDay` is stored as an `Int`, because a day number is four bytes
 * and an index over it is cheaper than one over a `Long`.
 */
private val LocalDate.epochDay: Int get() = toEpochDay().toInt()

/** The span's last day, or [today] while the span is still running. */
private fun DaySpan.lastDayThrough(today: LocalDate): Int = minOf(endInclusive, today).epochDay
