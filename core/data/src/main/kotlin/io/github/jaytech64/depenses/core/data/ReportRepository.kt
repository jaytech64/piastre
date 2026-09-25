// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.data

import io.github.jaytech64.depenses.core.database.ReportDao
import io.github.jaytech64.depenses.core.model.Money
import io.github.jaytech64.depenses.core.model.ReportPeriod
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** A merchant's share of a period, with how many purchases make it up. */
data class MerchantSpending(
    val merchantId: Long,
    val name: String,
    val total: Money,
    val count: Int,
)

/** What a period cost, and how many purchases that was. */
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
 */
@Singleton
class ReportRepository
    @Inject
    constructor(
        private val reports: ReportDao,
    ) {
        /**
         * What each day of [period] cost, days with no spending absent rather than
         * present as zero — "nothing" and "not yet" are different facts, and only the
         * caller knows which one an absent day is.
         */
        fun dailyTotals(
            period: ReportPeriod,
            currency: String = Money.CAD,
        ): Flow<Map<LocalDate, Money>> =
            reports
                .dailyTotals(period.start.epochDay, period.endInclusive.epochDay, currency)
                .map { rows ->
                    rows.associate { LocalDate.ofEpochDay(it.day.toLong()) to Money(it.totalMinor, currency) }
                }

        /**
         * The period's total and its purchase count in one pass.
         *
         * Read from the per-day rows rather than from `totalForRange`, because those
         * rows already carry both numbers and a second query would re-read what the
         * first one just returned.
         */
        fun spending(
            period: ReportPeriod,
            currency: String = Money.CAD,
        ): Flow<PeriodSpending> =
            reports
                .dailyTotals(period.start.epochDay, period.endInclusive.epochDay, currency)
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
            currency: String = Money.CAD,
        ): Flow<Money> =
            reports
                .totalForRange(period.start.epochDay, period.endInclusive.epochDay, currency)
                .map { Money(it ?: 0L, currency) }

        /**
         * What came in over [period]. A report reads it beside the spending to say
         * what the period's balance is made of; nothing else on this repository
         * counts an income.
         */
        fun income(
            period: ReportPeriod,
            currency: String = Money.CAD,
        ): Flow<Money> =
            reports
                .incomeForRange(period.start.epochDay, period.endInclusive.epochDay, currency)
                .map { Money(it ?: 0L, currency) }

        /**
         * The merchants a period went to, heaviest first.
         *
         * A purchase whose merchant never resolved is counted in [spending] and
         * absent here, because a ranking cannot rank something with no name. The
         * ranking is a top [limit] and never claims to sum to the period.
         */
        fun topMerchants(
            period: ReportPeriod,
            limit: Int,
            currency: String = Money.CAD,
        ): Flow<List<MerchantSpending>> =
            reports
                .topMerchants(period.start.epochDay, period.endInclusive.epochDay, currency, limit)
                .map { rows ->
                    rows.map {
                        MerchantSpending(
                            merchantId = it.merchantId,
                            name = it.name,
                            total = Money(it.totalMinor, currency),
                            count = it.count,
                        )
                    }
                }

        /** Incomes and purchases in [period] that every other figure on this repository left out. */
        fun otherCurrencyCount(
            period: ReportPeriod,
            currency: String = Money.CAD,
        ): Flow<Int> = reports.otherCurrencyCount(period.start.epochDay, period.endInclusive.epochDay, currency)
    }

/**
 * `localDateEpochDay` is stored as an `Int`, because a day number is four bytes
 * and an index over it is cheaper than one over a `Long`.
 */
private val LocalDate.epochDay: Int get() = toEpochDay().toInt()
