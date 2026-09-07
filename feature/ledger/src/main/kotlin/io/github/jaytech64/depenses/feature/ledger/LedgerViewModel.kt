// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.feature.ledger

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jaytech64.depenses.core.data.TransactionRepository
import io.github.jaytech64.depenses.core.model.Money
import io.github.jaytech64.depenses.core.model.Transaction
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import javax.inject.Inject

data class LedgerUiState(
    val transactions: List<Transaction> = emptyList(),
    val today: Money = Money(0, Money.CAD),
    val loading: Boolean = true,
)

@HiltViewModel
class LedgerViewModel
    @Inject
    constructor(
        repository: TransactionRepository,
    ) : ViewModel() {
        val uiState: StateFlow<LedgerUiState> =
            combine(
                repository.all(),
                repository.totalForRange(LocalDate.now(), LocalDate.now()),
            ) { transactions, today ->
                LedgerUiState(transactions = transactions, today = today, loading = false)
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = LedgerUiState(),
            )

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
