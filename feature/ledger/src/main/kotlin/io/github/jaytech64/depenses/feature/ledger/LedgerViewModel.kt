// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.feature.ledger

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jaytech64.depenses.core.data.LocalDateTicker
import io.github.jaytech64.depenses.core.data.TransactionRepository
import io.github.jaytech64.depenses.core.model.Money
import io.github.jaytech64.depenses.core.model.Transaction
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject

/** One local day, with its balance: what came in minus what went out. */
data class LedgerDay(
    val date: LocalDate,
    val total: Money,
    val transactions: List<Transaction>,
)

data class LedgerUiState(
    val days: List<LedgerDay> = emptyList(),
    /** Null until the clock has been read once; never a sentinel date. */
    val today: LocalDate? = null,
    /** Negative on a month you only spent, which is most months. */
    val balanceThisMonth: Money = Money(0, Money.CAD),
    val needsReview: Int = 0,
    val loading: Boolean = true,
) {
    val isEmpty: Boolean get() = days.isEmpty() && !loading
}

@HiltViewModel
class LedgerViewModel
    @Inject
    constructor(
        repository: TransactionRepository,
        ticker: LocalDateTicker,
    ) : ViewModel() {
        /**
         * The current day is a source of truth in its own right, not a value read
         * once at construction. Without it the heading still says "today" over
         * yesterday's purchases the morning after the screen was first opened.
         */
        val uiState: StateFlow<LedgerUiState> =
            combine(
                repository.confirmed(),
                repository.needsReviewCount(),
                ticker.dates(),
            ) { transactions, needsReview, today ->
                val days = transactions.groupByLocalDay()
                LedgerUiState(
                    days = days,
                    today = today,
                    balanceThisMonth = days.balanceIn(YearMonth.from(today)),
                    needsReview = needsReview,
                    loading = false,
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = LedgerUiState(),
            )

        /**
         * Grouped in Kotlin rather than SQL because the rows are already sorted and
         * already in memory — this is the one screen that wants every transaction,
         * so a second query would re-read what the first one just returned.
         */
        private fun List<Transaction>.groupByLocalDay(): List<LedgerDay> =
            groupBy { it.localDate }
                .map { (date, forDay) ->
                    LedgerDay(
                        date = date,
                        total = forDay.map { it.signedAmount }.reduce(Money::plus),
                        transactions = forDay,
                    )
                }.sortedByDescending { it.date }

        /**
         * Every day of the calendar month counts, including one dated ahead of
         * today: a purchase the list shows is a purchase the headline above it has
         * counted.
         */
        private fun List<LedgerDay>.balanceIn(month: YearMonth): Money =
            filter { YearMonth.from(it.date) == month }
                .map { it.total }
                .reduceOrNull(Money::plus) ?: Money(0, Money.CAD)

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
