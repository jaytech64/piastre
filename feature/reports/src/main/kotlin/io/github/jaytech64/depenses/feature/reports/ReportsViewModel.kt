// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.feature.reports

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jaytech64.depenses.core.data.LocalDateTicker
import io.github.jaytech64.depenses.core.data.MerchantSpending
import io.github.jaytech64.depenses.core.data.ReportRepository
import io.github.jaytech64.depenses.core.data.TransactionRepository
import io.github.jaytech64.depenses.core.model.Money
import io.github.jaytech64.depenses.core.model.PeriodUnit
import io.github.jaytech64.depenses.core.model.ReportPeriod
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

/** A merchant's place in the period, with the share of it that went there. */
data class MerchantShare(
    val merchantId: Long,
    val name: String,
    val total: Money,
    val count: Int,
    /** Of the period's total, in `0f..1f`. A bar drawn to this says something true. */
    val share: Float,
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
    /** What was spent: the figure the ranking and the calendar below break down. */
    val total: Money = Money(0, Money.CAD),
    val purchaseCount: Int = 0,
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
    val merchants: List<MerchantShare> = emptyList(),
    /** Every day of [period]'s month that money was spent on. Absent days had none. */
    val monthTotals: Map<LocalDate, Money> = emptyMap(),
    /** Incomes and purchases in the period that no figure here counted, because they were in another currency. */
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
     * What came in less what was spent. A transfer is in neither figure, so it
     * moves this by nothing, as it moves the ledger's unfiltered balance.
     */
    val balance: Money get() = income + -total

    /** Nothing came in and nothing was spent. A payday with no spending is not empty. */
    val isEmpty: Boolean get() = purchaseCount == 0 && income.minor == 0L && !loading
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
                reports.topMerchants(period, today, TOP_MERCHANTS),
                reports.dailyTotals(month, today),
                combine(reports.otherCurrencyCount(period, today), needsReview, ::Pair),
            ) { (spending, before), (income, incomeBefore), merchants, monthTotals, (otherCurrency, review) ->
                ReportsUiState(
                    period = period,
                    today = today,
                    total = spending.total,
                    purchaseCount = spending.count,
                    comparison = if (spending.count == 0) null else compare(spending.total, before),
                    income = income,
                    incomeComparison = incomeBefore?.let { compare(income, it) },
                    merchants = merchants.shareOf(spending.total),
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
         * A share of the period rather than of the leading merchant: a bar filled to
         * 35 % then means 35 % of what the period cost, which is a fact. Scaled to
         * the leader it would only mean "second place", and would fill the screen
         * identically whether the leader took half the period or a twentieth of it.
         */
        private fun List<MerchantSpending>.shareOf(periodTotal: Money): List<MerchantShare> =
            map { merchant ->
                MerchantShare(
                    merchantId = merchant.merchantId,
                    name = merchant.name,
                    total = merchant.total,
                    count = merchant.count,
                    share =
                        if (periodTotal.minor == 0L) {
                            0f
                        } else {
                            merchant.total.minor.toFloat() / periodTotal.minor
                        },
                )
            }

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
            const val UNIT = "periodUnit"
            const val ANCHOR_EPOCH_DAY = "periodAnchorEpochDay"
            const val PERCENT = 100L

            /** Five is what fits above the fold beside the calendar, and what "top merchants" means. */
            const val TOP_MERCHANTS = 5
        }
    }
