// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.feature.reports

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jaytech64.piastre.core.data.LocalDateTicker
import io.github.jaytech64.piastre.core.data.PremiumRepository
import io.github.jaytech64.piastre.core.data.ReportRepository
import io.github.jaytech64.piastre.core.data.TransactionRepository
import io.github.jaytech64.piastre.core.model.Money
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject

data class MerchantsUiState(
    /** Null until the clock has been read once, and the page draws nothing until then. */
    val today: LocalDate? = null,
    val span: MerchantSpan = MerchantSpan(MerchantSpanUnit.MONTH, LocalDate.ofEpochDay(0)),
    val rank: MerchantRank = MerchantRank.TOTAL,
    val ranking: MerchantRanking = MerchantRanking(emptyList(), Money(0, Money.CAD), Money(0, Money.CAD)),
    /** The transactions in the span no figure here counted, because they were in another currency. */
    val otherCurrencyCount: Int = 0,
    /** The whole ledger's queue, which every figure here leaves out. */
    val needsReviewCount: Int = 0,
    val premiumOn: Boolean = false,
) {
    /** You can reach the span you are living in and no further, as on a report. */
    val canStepForward: Boolean get() = today != null && span.endInclusive < today
}

/**
 * Where the money went, by merchant: every merchant over a month or a year, with
 * how many visits and what a visit cost on average.
 *
 * Every figure is [ReportRepository.merchantSpending], which counts exactly
 * what a report counts, so the merchants and the money with none add up to the
 * report's spending for the same days (ADR-0029). Nothing is stored.
 *
 * What you choose lives in saved state, as a report's period does, so a trip to
 * a merchant's transactions and back keeps it.
 */
@HiltViewModel
class MerchantsViewModel
    @Inject
    constructor(
        private val reports: ReportRepository,
        premium: PremiumRepository,
        transactions: TransactionRepository,
        ticker: LocalDateTicker,
        private val savedState: SavedStateHandle,
    ) : ViewModel() {
        /** The month Reports was on, which is where the page opens. */
        private val openedOn: LocalDate =
            YearMonth.parse(requireNotNull(savedState.get<String>(MERCHANTS_MONTH_ARG))).atDay(1)

        @OptIn(ExperimentalCoroutinesApi::class)
        val uiState: StateFlow<MerchantsUiState> =
            combine(
                ticker.dates(),
                savedState.getStateFlow(UNIT, MerchantSpanUnit.MONTH),
                savedState.getStateFlow(ANCHOR_EPOCH_DAY, openedOn.toEpochDay()),
            ) { today, unit, anchor ->
                today to MerchantSpan.containing(unit, minOf(LocalDate.ofEpochDay(anchor), today))
            }.flatMapLatest { (today, span) ->
                combine(
                    reports.merchantSpending(span, today),
                    savedState.getStateFlow(RANK, MerchantRank.TOTAL),
                    reports.otherCurrencyCount(span, today),
                    transactions.needsReviewCount(),
                    premium.premium,
                ) { rows, rank, otherCurrency, review, owned ->
                    MerchantsUiState(
                        today = today,
                        span = span,
                        rank = rank,
                        ranking = merchantRanking(rows, rank),
                        otherCurrencyCount = otherCurrency,
                        needsReviewCount = review,
                        premiumOn = owned.isOn,
                    )
                }
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = MerchantsUiState(),
            )

        /** A month or a year, around the day the page is on now. */
        fun onUnitSelected(unit: MerchantSpanUnit) {
            savedState[UNIT] = unit
        }

        fun onRankSelected(rank: MerchantRank) {
            savedState[RANK] = rank
        }

        fun onStepBack() {
            savedState[ANCHOR_EPOCH_DAY] =
                uiState.value.span
                    .previous()
                    .start
                    .toEpochDay()
        }

        fun onStepForward() {
            if (uiState.value.canStepForward) {
                savedState[ANCHOR_EPOCH_DAY] =
                    uiState.value.span
                        .next()
                        .start
                        .toEpochDay()
            }
        }

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
            const val UNIT = "merchantsUnit"
            const val ANCHOR_EPOCH_DAY = "merchantsAnchorEpochDay"
            const val RANK = "merchantsRank"
        }
    }

/** The month the page opens on, written `2026-11`. */
const val MERCHANTS_MONTH_ARG = "month"

private const val MERCHANTS_ROUTE_BASE = "reports/merchants"

const val MERCHANTS_ROUTE = "$MERCHANTS_ROUTE_BASE/{$MERCHANTS_MONTH_ARG}"

fun merchantsRoute(month: YearMonth): String = "$MERCHANTS_ROUTE_BASE/$month"
