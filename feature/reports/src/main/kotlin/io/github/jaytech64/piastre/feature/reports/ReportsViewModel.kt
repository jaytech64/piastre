// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.feature.reports

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jaytech64.piastre.core.data.CategorySpending
import io.github.jaytech64.piastre.core.data.LocalDateTicker
import io.github.jaytech64.piastre.core.data.ReportRepository
import io.github.jaytech64.piastre.core.data.TransactionRepository
import io.github.jaytech64.piastre.core.model.Money
import io.github.jaytech64.piastre.core.model.PeriodUnit
import io.github.jaytech64.piastre.core.model.ReportPeriod
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import javax.inject.Inject

/** A category's place in the period, with the share of it that went there. */
data class CategoryShare(
    val categoryId: Long,
    val name: String,
    val total: Money,
    val count: Int,
    /** Of the period's spending, in `0f..1f`, so a group's categories add up to its own bar. */
    val share: Float,
)

/** What a tap in the ranking asks to see the transactions of, over the period the report is on. */
sealed interface SpendingOf {
    data class OneCategory(
        val categoryId: Long,
    ) : SpendingOf

    data class OneGroup(
        val groupId: Long,
    ) : SpendingOf

    data object NoCategory : SpendingOf
}

/**
 * A row of the ranking: a group with the categories money went to in it, or a
 * category that is in no group, which ranks under its own name and has nothing
 * to open.
 */
data class RankedSpending(
    /** Null for a category in no group. */
    val groupId: Long?,
    /** The category itself when it is in no group, and null for a group. */
    val categoryId: Long?,
    val name: String,
    val total: Money,
    /** Of the period's spending, in `0f..1f`. A bar drawn to this says something true. */
    val share: Float,
    /** Heaviest first, and empty for a category in no group. */
    val categories: List<CategoryShare> = emptyList(),
)

/**
 * How a figure for a period compares with the same figure the period before.
 *
 * Three states rather than a number, because two of the three cases have no
 * percentage to report and printing one anyway would be a fabrication.
 */
sealed interface Comparison {
    /** The figure was zero in both periods; there is nothing to compare. */
    data object NothingEither : Comparison

    /** The figure was zero last time, so the change is not a percentage of anything. */
    data object NothingBefore : Comparison

    /** Negative is less than last time, which for spending is the good direction. */
    data class Change(
        val percent: Int,
    ) : Comparison
}

data class ReportsUiState(
    /** Meaningless until [today] is known, and the screen draws nothing until then. */
    val period: ReportPeriod = ReportPeriod(PeriodUnit.WEEK, LocalDate.ofEpochDay(0)),
    /** Null until the clock has been read once; never a sentinel date. */
    val today: LocalDate? = null,
    /**
     * What went out: purchases, and transfers into a bank account (ADR-0017). It
     * is the figure the ranking and the calendar below break down.
     */
    val total: Money = Money(0, Money.CAD),
    /** How many transactions [total] adds up. */
    val spendingCount: Int = 0,
    /**
     * Null when nothing was spent. "Rien de dépensé" already says so, and a
     * ▼ 100 % under it after a day with spending would say it a second time.
     */
    val comparison: Comparison? = null,
    val income: Money = Money(0, Money.CAD),
    /**
     * Null for a day or a week. Pay arrives every week or two, so a shorter period
     * mostly compares a payday with a week without one, and the percentage says
     * nothing about the money.
     */
    val incomeComparison: Comparison? = null,
    /** The five heaviest groups, and categories in no group, of [total]. */
    val ranking: List<RankedSpending> = emptyList(),
    /**
     * What went out with no category. It ranks nowhere and is said under the
     * ranking, as any breakdown that leaves rows out says so beside itself (ADR-0002).
     */
    val uncategorised: Money = Money(0, Money.CAD),
    /** The group whose categories are shown, while it is in [ranking]. */
    val openGroupId: Long? = null,
    /** Every day of [period]'s month that money was spent on. Absent days had none. */
    val monthTotals: Map<LocalDate, Money> = emptyMap(),
    /** The transactions in the period that no figure here counted, because they were in another currency. */
    val otherCurrencyCount: Int = 0,
    /** The whole ledger's queue, which the totals here also leave out. */
    val needsReviewCount: Int = 0,
    val loading: Boolean = true,
) {
    /**
     * You can reach the period you are living in and no further. A report of a
     * week that has not happened is an empty screen that looks like a bug.
     */
    val canStepForward: Boolean get() = today != null && period.endInclusive < today

    /**
     * What came in less what went out, which is the ledger's unfiltered balance
     * for the period: both count a transfer by the accounts it joins.
     */
    val balance: Money get() = income + -total

    /** Nothing came in and nothing was spent. A payday with no spending is not empty. */
    val isEmpty: Boolean get() = spendingCount == 0 && income.minor == 0L && !loading
}

@HiltViewModel
class ReportsViewModel
    @Inject
    constructor(
        private val reports: ReportRepository,
        transactions: TransactionRepository,
        ticker: LocalDateTicker,
        private val savedState: SavedStateHandle,
    ) : ViewModel() {
        /**
         * The period lives in saved state for the reason the ledger's account filter
         * does: the bottom bar navigates with `popUpTo(saveState = true)`, which
         * clears view models scoped to the popped entry, so a field would reset the
         * period on every trip to the ledger and back.
         *
         * The anchor is null until you step or pick a day, which is what makes a
         * cold launch open on the period containing today rather than on whichever
         * week was last looked at.
         */
        private val unit: StateFlow<PeriodUnit> = savedState.getStateFlow(UNIT, PeriodUnit.WEEK)
        private val anchor: StateFlow<Long?> = savedState.getStateFlow(ANCHOR_EPOCH_DAY, null)

        @OptIn(ExperimentalCoroutinesApi::class)
        val uiState: StateFlow<ReportsUiState> =
            combine(unit, anchor, ticker.dates()) { selectedUnit, anchorDay, today ->
                val on = anchorDay?.let(LocalDate::ofEpochDay) ?: today
                ReportPeriod.containing(selectedUnit, on) to today
            }.flatMapLatest { (period, today) -> report(period, today, transactions.needsReviewCount()) }
                .combine(
                    savedState.getStateFlow<Long?>(OPEN_GROUP, null),
                ) { state, open -> state.copy(openGroupId = open) }
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                    initialValue = ReportsUiState(),
                )

        private fun report(
            period: ReportPeriod,
            today: LocalDate,
            needsReview: Flow<Int>,
        ): Flow<ReportsUiState> {
            val month = ReportPeriod.containing(PeriodUnit.MONTH, period.start)
            val previousIncome: Flow<Money?> =
                if (period.unit == PeriodUnit.MONTH) reports.income(period.previous(), today) else flowOf(null)
            return combine(
                combine(reports.spending(period, today), reports.total(period.previous(), today), ::Pair),
                combine(reports.income(period, today), previousIncome, ::Pair),
                reports.categorySpending(period, today),
                reports.dailyTotals(month, today),
                combine(reports.otherCurrencyCount(period, today), needsReview, ::Pair),
            ) { (spending, before), (income, incomeBefore), categories, monthTotals, (otherCurrency, review) ->
                ReportsUiState(
                    period = period,
                    today = today,
                    total = spending.total,
                    spendingCount = spending.count,
                    comparison = if (spending.count == 0) null else compare(spending.total, before),
                    income = income,
                    incomeComparison = incomeBefore?.let { compare(income, it) },
                    ranking = categories.rankedWithin(spending.total),
                    uncategorised =
                        categories
                            .filter { it.category == null }
                            .fold(Money(0, Money.CAD)) { sum, spent -> sum + spent.total },
                    monthTotals = monthTotals,
                    otherCurrencyCount = otherCurrency,
                    needsReviewCount = review,
                    loading = false,
                )
            }
        }

        /** Keeps the anchor, so switching from a week to its month stays in that month. */
        fun onUnitSelected(selected: PeriodUnit) {
            savedState[UNIT] = selected
        }

        fun onStepBack() =
            anchorOn(
                uiState.value.period
                    .previous()
                    .start,
            )

        fun onStepForward() {
            if (uiState.value.canStepForward) {
                anchorOn(
                    uiState.value.period
                        .next()
                        .start,
                )
            }
        }

        /** Tapping a day in the calendar narrows to it — which is why there is no date picker. */
        fun onDaySelected(date: LocalDate) {
            savedState[UNIT] = PeriodUnit.DAY
            anchorOn(date)
        }

        /**
         * One group is open at a time, and tapping the open one closes it. It lives
         * in saved state beside the period, so stepping to the next month keeps the
         * same group open to compare.
         */
        fun onGroupTapped(groupId: Long) {
            savedState[OPEN_GROUP] = groupId.takeIf { it != savedState.get<Long?>(OPEN_GROUP) }
        }

        private fun anchorOn(date: LocalDate) {
            savedState[ANCHOR_EPOCH_DAY] = date.toEpochDay()
        }

        /**
         * The change as a whole percent, in `Long` arithmetic the whole way.
         *
         * [Money] is exact minor units and never becomes a `Double`; a percentage is
         * a ratio, so it converts at the edge and comes straight back as an `Int`.
         */
        private fun compare(
            now: Money,
            before: Money,
        ): Comparison =
            when {
                before.minor != 0L -> Comparison.Change((((now.minor - before.minor) * PERCENT) / before.minor).toInt())
                now.minor != 0L -> Comparison.NothingBefore
                else -> Comparison.NothingEither
            }

        /**
         * Categories roll up into the group each is in, a category in no group
         * stands as itself, and the five heaviest of those are kept. What has no
         * category is in none of them.
         */
        private fun List<CategorySpending>.rankedWithin(periodTotal: Money): List<RankedSpending> {
            val categorised = mapNotNull { spent -> spent.category?.let { it to spent } }
            val alone =
                categorised.filter { (_, spent) -> spent.group == null }.map { (category, spent) ->
                    RankedSpending(null, category.id, category.name, spent.total, spent.total.shareOf(periodTotal))
                }
            val grouped =
                categorised
                    .mapNotNull { (category, spent) -> spent.group?.let { it to (category to spent) } }
                    .groupBy({ it.first }, { it.second })
                    .map { (group, within) ->
                        val total = within.fold(Money(0, periodTotal.currency)) { sum, (_, spent) -> sum + spent.total }
                        RankedSpending(
                            groupId = group.id,
                            categoryId = null,
                            name = group.name,
                            total = total,
                            share = total.shareOf(periodTotal),
                            categories =
                                within
                                    .sortedByDescending { (_, spent) -> spent.total.minor }
                                    .map { (category, spent) ->
                                        CategoryShare(
                                            categoryId = category.id,
                                            name = category.name,
                                            total = spent.total,
                                            count = spent.count,
                                            share = spent.total.shareOf(periodTotal),
                                        )
                                    },
                        )
                    }
            return (grouped + alone).sortedByDescending { it.total.minor }.take(TOP_RANKED)
        }

        /**
         * A share of the period rather than of the leading row: a bar filled to
         * 35 % then means 35 % of what the period cost, which is a fact. Scaled to
         * the leader it would only mean "second place", and would fill the screen
         * identically whether the leader took half the period or a twentieth of it.
         */
        private fun Money.shareOf(periodTotal: Money): Float =
            if (periodTotal.minor == 0L) 0f else minor.toFloat() / periodTotal.minor

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
            const val UNIT = "periodUnit"
            const val ANCHOR_EPOCH_DAY = "periodAnchorEpochDay"
            const val OPEN_GROUP = "openGroupId"
            const val PERCENT = 100L

            /** Five is what fits above the fold beside the calendar. */
            const val TOP_RANKED = 5
        }
    }
