// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.data

import io.github.jaytech64.depenses.core.database.ReportDao
import io.github.jaytech64.depenses.core.model.Category
import io.github.jaytech64.depenses.core.model.CategoryGroup
import io.github.jaytech64.depenses.core.model.Money
import io.github.jaytech64.depenses.core.model.ReportPeriod
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
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
 * transaction — it reads aggregates, and a period is the only thing it is ever
 * asked about.
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
         * What went out on each day of [period], days with no spending absent rather than
         * present as zero — "nothing" and "not yet" are different facts, and only the
         * caller knows which one an absent day is.
         */
        fun dailyTotals(
            period: ReportPeriod,
            today: LocalDate,
            currency: String = Money.CAD,
        ): Flow<Map<LocalDate, Money>> =
            reports
                .dailyTotals(period.start.epochDay, period.lastDayThrough(today), currency)
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
            period: ReportPeriod,
            today: LocalDate,
            currency: String = Money.CAD,
        ): Flow<PeriodSpending> =
            reports
                .dailyTotals(period.start.epochDay, period.lastDayThrough(today), currency)
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
            period: ReportPeriod,
            today: LocalDate,
            currency: String = Money.CAD,
        ): Flow<Money> =
            reports
                .totalForRange(period.start.epochDay, period.lastDayThrough(today), currency)
                .map { Money(it ?: 0L, currency) }

        /**
         * What came in over [period], money moved back out of a bank account
         * included. A report reads it beside the spending to say what the period's
         * balance is made of; nothing else on this repository counts an income.
         */
        fun income(
            period: ReportPeriod,
            today: LocalDate,
            currency: String = Money.CAD,
        ): Flow<Money> =
            reports
                .incomeForRange(period.start.epochDay, period.lastDayThrough(today), currency)
                .map { Money(it ?: 0L, currency) }

        /**
         * What [spending] counts, by category, in no particular order: the rows add
         * up to its total and its count. Each category comes with the group it is
         * in now, so a report follows a category that was moved (ADR-0016).
         */
        fun categorySpending(
            period: ReportPeriod,
            today: LocalDate,
            currency: String = Money.CAD,
        ): Flow<List<CategorySpending>> =
            reports
                .categoryTotals(period.start.epochDay, period.lastDayThrough(today), currency)
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

        /** The rows in [period] that every other figure on this repository left out for their currency. */
        fun otherCurrencyCount(
            period: ReportPeriod,
            today: LocalDate,
            currency: String = Money.CAD,
        ): Flow<Int> = reports.otherCurrencyCount(period.start.epochDay, period.lastDayThrough(today), currency)
    }

/**
 * `localDateEpochDay` is stored as an `Int`, because a day number is four bytes
 * and an index over it is cheaper than one over a `Long`.
 */
private val LocalDate.epochDay: Int get() = toEpochDay().toInt()

/** The period's last day, or [today] while the period is still running. */
private fun ReportPeriod.lastDayThrough(today: LocalDate): Int = minOf(endInclusive, today).epochDay
