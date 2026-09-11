// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.feature.ledger

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jaytech64.depenses.core.designsystem.Formatters
import io.github.jaytech64.depenses.core.model.Card
import io.github.jaytech64.depenses.core.model.Merchant
import io.github.jaytech64.depenses.core.model.TransactionKind
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.Locale

/**
 * Adds a transaction — money spent or received — or corrects one already in the
 * ledger.
 *
 * Cash and Interac never post a notification, so they are typed. Only the amount
 * is required: a purchase you half-remember is still worth more in the ledger
 * than left out of it.
 */
@Composable
fun ExpenseFormScreen(
    onDone: () -> Unit,
    viewModel: ExpenseFormViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val merchants by viewModel.knownMerchants.collectAsStateWithLifecycle()
    val cards by viewModel.knownCards.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf(Picking.NOTHING) }

    LaunchedEffect(state.done) {
        if (state.done) onDone()
    }

    if (state.loading) return

    EntryForm(
        state = state,
        merchants = merchants,
        cards = cards,
        viewModel = viewModel,
        onPick = { picking = it },
        onCancel = onDone,
    )

    FormDialog(picking = picking, state = state, viewModel = viewModel, onClose = { picking = Picking.NOTHING })
}

@Composable
private fun EntryForm(
    state: ExpenseFormUiState,
    merchants: List<Merchant>,
    cards: List<Card>,
    viewModel: ExpenseFormViewModel,
    onPick: (Picking) -> Unit,
    onCancel: () -> Unit,
) {
    // Scrolls because a split-screen window, or the keyboard, can leave less
    // height than the form needs — and Save is the last thing in it.
    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            stringResource(if (state.isEditing) R.string.edit_title else R.string.manual_title),
            style = MaterialTheme.typography.titleLarge,
        )

        KindToggle(kind = state.kind, onChange = viewModel::onKindChange)

        OutlinedTextField(
            value = state.amountText,
            onValueChange = viewModel::onAmountChange,
            label = { Text(stringResource(R.string.manual_amount)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            isError = state.amountText.isNotBlank() && state.amount == null,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = state.merchantText,
            onValueChange = viewModel::onMerchantChange,
            label = { Text(stringResource(merchantLabel(state.kind))) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        MerchantSuggestions(
            merchants = merchants,
            typed = state.merchantText,
            onPick = viewModel::onMerchantChange,
        )

        if (state.kind == TransactionKind.EXPENSE) {
            PaidWithField(state, cards, onPick = viewModel::onPaymentChange, onAddCard = { onPick(Picking.ADD_CARD) })
        }

        WhenRow(state = state, onPick = onPick)

        OutlinedTextField(
            value = state.note,
            onValueChange = viewModel::onNoteChange,
            label = { Text(stringResource(R.string.manual_note)) },
            modifier = Modifier.fillMaxWidth(),
        )

        FormActions(
            state = state,
            onSave = viewModel::save,
            onCancel = onCancel,
            onDelete = { onPick(Picking.DELETE) },
        )
    }
}

/** Save and Cancel together; Delete kept apart at the far end, and only when there is something to delete. */
@Composable
private fun FormActions(
    state: ExpenseFormUiState,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onSave, enabled = state.canSave) {
            Text(stringResource(R.string.manual_save))
        }
        TextButton(onClick = onCancel) { Text(stringResource(R.string.manual_cancel)) }
        if (state.isEditing) {
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = onDelete,
                enabled = !state.saving,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Text(stringResource(R.string.edit_delete))
            }
        }
    }
}

/**
 * Expense or income, first on the form because it changes what the rest of it
 * asks: income comes from a source, not a merchant.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KindToggle(
    kind: TransactionKind,
    onChange: (TransactionKind) -> Unit,
) {
    val options =
        listOf(
            TransactionKind.EXPENSE to R.string.form_kind_expense,
            TransactionKind.INCOME to R.string.form_kind_income,
        )
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (option, label) ->
            SegmentedButton(
                selected = kind == option,
                onClick = { onChange(option) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
            ) {
                Text(stringResource(label))
            }
        }
    }
}

private fun merchantLabel(kind: TransactionKind): Int =
    when (kind) {
        TransactionKind.EXPENSE -> R.string.manual_merchant
        TransactionKind.INCOME -> R.string.form_source
    }

/** Defaults to now, because that is when almost every cash purchase is entered. */
@Composable
private fun WhenRow(
    state: ExpenseFormUiState,
    onPick: (Picking) -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { onPick(Picking.DATE) }) {
            Text(Formatters.date(state.date, locale))
        }
        OutlinedButton(onClick = { onPick(Picking.TIME) }) {
            Text(Formatters.time(state.time.toInstantOn(state.date), ZoneOffset.UTC, locale))
        }
    }
}

/**
 * Names you have used before, filtered as you type.
 *
 * The point is not convenience: picking an existing name is what stops "Marché
 * St-Joseph" and "MARCHE ST JOSEPH" becoming two merchants in the reports.
 */
@Composable
private fun MerchantSuggestions(
    merchants: List<Merchant>,
    typed: String,
    onPick: (String) -> Unit,
) {
    // The name just picked stays in the row on purpose. Dropping it would collapse
    // the row under the finger that tapped it and slide the buttons up into the
    // gap — the kind of layout jump that gets Annuler pressed instead of the note.
    val matches =
        remember(merchants, typed) {
            merchants
                .filter { typed.isBlank() || it.displayName.contains(typed, ignoreCase = true) }
                .take(MAX_SUGGESTIONS)
        }
    if (matches.isEmpty()) return

    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        matches.forEach { merchant ->
            SuggestionChip(
                onClick = { onPick(merchant.displayName) },
                label = { Text(merchant.displayName) },
            )
        }
    }
}

private fun LocalTime.toInstantOn(date: LocalDate): Instant = date.atTime(this).toInstant(ZoneOffset.UTC)

private const val MAX_SUGGESTIONS = 6
