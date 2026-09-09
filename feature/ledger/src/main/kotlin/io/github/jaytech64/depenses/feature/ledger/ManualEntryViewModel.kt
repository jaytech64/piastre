// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.feature.ledger

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jaytech64.depenses.core.data.MerchantRepository
import io.github.jaytech64.depenses.core.data.TransactionRepository
import io.github.jaytech64.depenses.core.designsystem.Formatters
import io.github.jaytech64.depenses.core.model.Merchant
import io.github.jaytech64.depenses.core.model.Money
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Provider

/**
 * What has been typed so far. [amountText] is kept as text rather than as a
 * [Money] because half-typed input is not an amount yet, and a field that
 * silently rounds what you are still writing is worse than one that waits.
 */
data class ManualEntryUiState(
    val amountText: String = "",
    val merchantText: String = "",
    val date: LocalDate,
    val time: LocalTime,
    val note: String = "",
    val saving: Boolean = false,
    /** Flips once, when the row is on disk. The screen leaves on it. */
    val saved: Boolean = false,
) {
    val amount: Money? get() = Formatters.parseMoney(amountText)

    /** A purchase needs an amount. Everything else on this form is optional. */
    val canSave: Boolean get() = amount != null && !saving
}

@HiltViewModel
class ManualEntryViewModel
    @Inject
    constructor(
        private val transactions: TransactionRepository,
        merchants: MerchantRepository,
        private val zoneId: Provider<ZoneId>,
    ) : ViewModel() {
        private val _uiState =
            MutableStateFlow(
                zoneId.get().let { zone ->
                    ManualEntryUiState(date = LocalDate.now(zone), time = LocalTime.now(zone))
                },
            )
        val uiState: StateFlow<ManualEntryUiState> = _uiState.asStateFlow()

        /**
         * Every merchant already known, so the field can offer the name you used
         * last time instead of letting you invent a second spelling of it.
         */
        val knownMerchants: StateFlow<List<Merchant>> =
            merchants.all().stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

        fun onAmountChange(text: String) = _uiState.update { it.copy(amountText = text) }

        fun onMerchantChange(text: String) = _uiState.update { it.copy(merchantText = text) }

        fun onNoteChange(text: String) = _uiState.update { it.copy(note = text) }

        fun onDateChange(date: LocalDate) = _uiState.update { it.copy(date = date) }

        fun onTimeChange(time: LocalTime) = _uiState.update { it.copy(time = time) }

        /**
         * Reports completion through [ManualEntryUiState.saved] rather than a
         * callback, so the screen navigates away on a fact that is already on disk
         * instead of on a promise made from inside a coroutine it does not own.
         */
        fun save() {
            val state = _uiState.value
            val amount = state.amount ?: return
            _uiState.update { it.copy(saving = true) }
            viewModelScope.launch {
                transactions.addManual(
                    amount = amount,
                    merchantName = state.merchantText,
                    occurredAt = state.occurredAt(zoneId.get()),
                    note = state.note,
                )
                _uiState.update { it.copy(saving = false, saved = true) }
            }
        }

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }

internal fun ManualEntryUiState.occurredAt(zone: ZoneId): Instant = date.atTime(time).atZone(zone).toInstant()
