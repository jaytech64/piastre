// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.feature.reports

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jaytech64.piastre.core.data.BudgetMonth
import io.github.jaytech64.piastre.core.data.BudgetRepository
import io.github.jaytech64.piastre.core.data.CategoryGroupRepository
import io.github.jaytech64.piastre.core.data.CategoryRepository
import io.github.jaytech64.piastre.core.data.LocalDateTicker
import io.github.jaytech64.piastre.core.data.PremiumRepository
import io.github.jaytech64.piastre.core.model.BudgetTarget
import io.github.jaytech64.piastre.core.model.Category
import io.github.jaytech64.piastre.core.model.CategoryGroup
import io.github.jaytech64.piastre.core.model.Money
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject

data class BudgetsUiState(
    val month: YearMonth? = null,
    val today: LocalDate? = null,
    val budgets: BudgetMonth? = null,
    val categories: List<Category> = emptyList(),
    val groups: List<CategoryGroup> = emptyList(),
    val premiumOn: Boolean = false,
) {
    /** Only the month you are in can be changed: a past month keeps what it was judged against. */
    val editable: Boolean get() = month != null && today != null && month == YearMonth.from(today)

    val canStepForward: Boolean get() = month != null && today != null && month < YearMonth.from(today)
}

@HiltViewModel
class BudgetsViewModel
    @Inject
    constructor(
        private val budgets: BudgetRepository,
        categories: CategoryRepository,
        groups: CategoryGroupRepository,
        premium: PremiumRepository,
        ticker: LocalDateTicker,
        private val savedState: SavedStateHandle,
    ) : ViewModel() {
        /** Always given by the route that opens this page, from the report's month. */
        private val month: StateFlow<YearMonth> =
            savedState
                .getStateFlow(BUDGET_MONTH_ARG, requireNotNull(savedState.get<String>(BUDGET_MONTH_ARG)))
                .map(YearMonth::parse)
                .stateIn(
                    viewModelScope,
                    SharingStarted.Eagerly,
                    YearMonth.parse(savedState.get<String>(BUDGET_MONTH_ARG)),
                )

        @OptIn(ExperimentalCoroutinesApi::class)
        val uiState: StateFlow<BudgetsUiState> =
            combine(month, ticker.dates(), ::Pair)
                .flatMapLatest { (shown, today) ->
                    combine(
                        budgets.month(shown, today),
                        categories.all(),
                        groups.all(),
                        premium.premium,
                    ) { lines, categoryList, groupList, owned ->
                        BudgetsUiState(shown, today, lines, categoryList.map { it.category }, groupList, owned.isOn)
                    }
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), BudgetsUiState())

        fun onStepBack() {
            savedState[BUDGET_MONTH_ARG] = month.value.minusMonths(1).toString()
        }

        fun onStepForward() {
            if (uiState.value.canStepForward) savedState[BUDGET_MONTH_ARG] = month.value.plusMonths(1).toString()
        }

        /** From this month on; a past month is never changed. */
        fun save(
            target: BudgetTarget,
            amount: Money,
        ) {
            if (!uiState.value.editable) return
            viewModelScope.launch { budgets.set(target, amount, month.value) }
        }

        fun stop(target: BudgetTarget) {
            if (!uiState.value.editable) return
            viewModelScope.launch { budgets.stop(target, month.value) }
        }

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }

/** The month the Budgets page opens on, as `2026-10`. */
const val BUDGET_MONTH_ARG = "month"
