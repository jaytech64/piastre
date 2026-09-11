// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.feature.ledger

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jaytech64.depenses.core.data.CardRepository
import io.github.jaytech64.depenses.core.data.MerchantRepository
import io.github.jaytech64.depenses.core.data.TransactionEditor
import io.github.jaytech64.depenses.core.data.TransactionRepository
import io.github.jaytech64.depenses.core.designsystem.Formatters
import io.github.jaytech64.depenses.core.model.Card
import io.github.jaytech64.depenses.core.model.Merchant
import io.github.jaytech64.depenses.core.model.Money
import io.github.jaytech64.depenses.core.model.Payment
import io.github.jaytech64.depenses.core.model.Transaction
import io.github.jaytech64.depenses.core.model.TransactionKind
import io.github.jaytech64.depenses.core.model.TransactionSource
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
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Provider

/** The navigation argument naming the purchase to edit. Absent when adding one. */
const val EXPENSE_ID_ARG = "expenseId"

/**
 * What has been typed so far. [amountText] is kept as text rather than as a
 * [Money] because half-typed input is not an amount yet, and a field that
 * silently rounds what you are still writing is worse than one that waits.
 */
data class ExpenseFormUiState(
    val amountText: String = "",
    val merchantText: String = "",
    val date: LocalDate,
    val time: LocalTime,
    val note: String = "",
    val kind: TransactionKind = TransactionKind.EXPENSE,
    /** Null when nothing was said about it. */
    val payment: Payment? = null,
    /** A purchase a notification recorded: its card is the bank's to say, so it is shown rather than offered. */
    val isCaptured: Boolean = false,
    val capturedCardLabel: String? = null,
    val isEditing: Boolean = false,
    /** True while the purchase being edited is read off disk, so the form never shows "now" in its place. */
    val loading: Boolean = false,
    val saving: Boolean = false,
    /** Flips once, when the change is on disk. The screen leaves on it. */
    val done: Boolean = false,
) {
    val amount: Money? get() = Formatters.parseMoney(amountText)

    /** A purchase needs an amount. Everything else on this form is optional. */
    val canSave: Boolean get() = amount != null && !saving && !loading

    /** Income has no Paid with field, so whatever it said before the switch is not kept. */
    val paymentToSave: Payment? get() = payment.takeIf { kind == TransactionKind.EXPENSE }
}

/**
 * One form for adding a purchase and for correcting one, because the two ask the
 * same questions — and a second form would drift from the first the day one of
 * them learns something.
 */
@HiltViewModel
class ExpenseFormViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val transactions: TransactionRepository,
        private val editor: TransactionEditor,
        merchants: MerchantRepository,
        private val cards: CardRepository,
        private val zoneId: Provider<ZoneId>,
    ) : ViewModel() {
        private val editingId: Long? = savedStateHandle.get<Long>(EXPENSE_ID_ARG)

        /** The purchase as it was on disk before this edit, or null when adding. */
        private var original: Transaction? = null

        private val _uiState =
            MutableStateFlow(
                zoneId.get().let { zone ->
                    ExpenseFormUiState(
                        date = LocalDate.now(zone),
                        time = LocalTime.now(zone),
                        isEditing = editingId != null,
                        loading = editingId != null,
                    )
                },
            )
        val uiState: StateFlow<ExpenseFormUiState> = _uiState.asStateFlow()

        /**
         * Every merchant already known, so the field can offer the name you used
         * last time instead of letting you invent a second spelling of it.
         */
        val knownMerchants: StateFlow<List<Merchant>> =
            merchants.all().stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

        val knownCards: StateFlow<List<Card>> =
            cards.all().stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

        // A purchase that is no longer there has nothing to edit, so the screen
        // simply closes rather than offering to save into a row that is gone.
        init {
            editingId?.let { id ->
                viewModelScope.launch {
                    val transaction = transactions.byId(id)
                    original = transaction
                    _uiState.update { state ->
                        transaction?.let { state.filledFrom(it) } ?: state.copy(loading = false, done = true)
                    }
                }
            }
        }

        fun onAmountChange(text: String) = _uiState.update { it.copy(amountText = text) }

        fun onMerchantChange(text: String) = _uiState.update { it.copy(merchantText = text) }

        fun onNoteChange(text: String) = _uiState.update { it.copy(note = text) }

        fun onDateChange(date: LocalDate) = _uiState.update { it.copy(date = date) }

        fun onTimeChange(time: LocalTime) = _uiState.update { it.copy(time = time) }

        fun onKindChange(kind: TransactionKind) = _uiState.update { it.copy(kind = kind) }

        fun onPaymentChange(payment: Payment?) = _uiState.update { it.copy(payment = payment) }

        /** Adds the card missing from the list and picks it, so adding one never means leaving the form. */
        fun addCard(
            name: String,
            last4: String,
        ) {
            viewModelScope.launch {
                val id = cards.add(name, last4)
                _uiState.update { it.copy(payment = Payment.ByCard(id)) }
            }
        }

        /**
         * Reports completion through [ExpenseFormUiState.done] rather than a
         * callback, so the screen navigates away on a fact that is already on disk
         * instead of on a promise made from inside a coroutine it does not own.
         */
        fun save() {
            val state = _uiState.value
            val amount = state.amount ?: return
            val editing = original
            _uiState.update { it.copy(saving = true) }
            viewModelScope.launch {
                if (editing == null) {
                    transactions.addManual(
                        amount = amount,
                        merchantName = state.merchantText,
                        occurredAt = state.occurredAt(zoneId.get()),
                        note = state.note,
                        kind = state.kind,
                        payment = state.paymentToSave,
                    )
                } else {
                    editor.edit(
                        id = editing.id,
                        amount = amount,
                        kind = state.kind,
                        merchantName = state.merchantText,
                        occurredAt = state.occurredAtLike(editing),
                        note = state.note,
                        payment = state.paymentToSave,
                    )
                }
                _uiState.update { it.copy(saving = false, done = true) }
            }
        }

        /** Only offered while editing: a purchase not yet saved has nothing to remove. */
        fun delete() {
            val editing = original ?: return
            _uiState.update { it.copy(saving = true) }
            viewModelScope.launch {
                editor.remove(editing.id)
                _uiState.update { it.copy(saving = false, done = true) }
            }
        }

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }

/**
 * The form filled from a purchase as stored, read in the zone it was made in —
 * the same clock the ledger row shows it under.
 */
private fun ExpenseFormUiState.filledFrom(transaction: Transaction): ExpenseFormUiState {
    val local = transaction.occurredAt.atZone(transaction.zoneId)
    return copy(
        amountText = Formatters.amountInput(transaction.amount),
        merchantText = transaction.displayMerchant.orEmpty(),
        date = local.toLocalDate(),
        time = local.toLocalTime(),
        note = transaction.note.orEmpty(),
        kind = transaction.kind,
        payment = transaction.payment,
        isCaptured = transaction.source == TransactionSource.NOTIFICATION,
        capturedCardLabel = Formatters.cardName(transaction.cardName, transaction.cardLast4),
        loading = false,
    )
}

internal fun ExpenseFormUiState.occurredAt(zone: ZoneId): Instant = date.atTime(time).atZone(zone).toInstant()

/**
 * The typed date and time, read in the zone the [original] purchase was made in.
 *
 * Its offset is preferred when the wall-clock time is ambiguous, so an untouched
 * purchase made in the hour the clocks fall back does not jump an hour on save.
 */
internal fun ExpenseFormUiState.occurredAtLike(original: Transaction): Instant =
    ZonedDateTime
        .ofLocal(date.atTime(time), original.zoneId, original.occurredAt.atZone(original.zoneId).offset)
        .toInstant()
