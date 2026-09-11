// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.feature.ledger

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import io.github.jaytech64.depenses.core.model.Merchant
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.Locale

/**
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
    var picking by remember { mutableStateOf(Picking.NOTHING) }

    LaunchedEffect(state.saved) {
        if (state.saved) onDone()
    }

    EntryForm(
        state = state,
        merchants = merchants,
        viewModel = viewModel,
        onPick = { picking = it },
        onCancel = onDone,
    )

    when (picking) {
        Picking.DATE -> {
            DatePickerSheet(
                date = state.date,
                onPicked = {
                    viewModel.onDateChange(it)
                    picking = Picking.NOTHING
                },
                onCancel = { picking = Picking.NOTHING },
            )
        }

        Picking.TIME -> {
            TimePickerSheet(
                time = state.time,
                onPicked = {
                    viewModel.onTimeChange(it)
                    picking = Picking.NOTHING
                },
                onCancel = { picking = Picking.NOTHING },
            )
        }

        Picking.NOTHING -> {
            Unit
        }
    }
}

@Composable
private fun EntryForm(
    state: ExpenseFormUiState,
    merchants: List<Merchant>,
    viewModel: ExpenseFormViewModel,
    onPick: (Picking) -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.manual_title), style = MaterialTheme.typography.titleLarge)

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
            label = { Text(stringResource(R.string.manual_merchant)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        MerchantSuggestions(
            merchants = merchants,
            typed = state.merchantText,
            onPick = viewModel::onMerchantChange,
        )

        WhenRow(state = state, onPick = onPick)

        OutlinedTextField(
            value = state.note,
            onValueChange = viewModel::onNoteChange,
            label = { Text(stringResource(R.string.manual_note)) },
            modifier = Modifier.fillMaxWidth(),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = viewModel::save, enabled = state.canSave) {
                Text(stringResource(R.string.manual_save))
            }
            TextButton(onClick = onCancel) { Text(stringResource(R.string.manual_cancel)) }
        }
    }
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

private enum class Picking { NOTHING, DATE, TIME }

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatePickerSheet(
    date: LocalDate,
    onPicked: (LocalDate) -> Unit,
    onCancel: () -> Unit,
) {
    // The picker speaks UTC millis; the date is held as a plain LocalDate, so the
    // conversion is pinned to UTC at both ends rather than to a zone that would
    // shift the day by one either side of midnight.
    val pickerState = rememberDatePickerState(initialSelectedDateMillis = date.toUtcMillis())

    AlertDialog(
        onDismissRequest = onCancel,
        confirmButton = {
            TextButton(
                onClick = { pickerState.selectedDateMillis?.let { onPicked(it.toUtcLocalDate()) } },
            ) {
                Text(stringResource(R.string.manual_save))
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.manual_cancel)) } },
        text = { DatePicker(state = pickerState) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerSheet(
    time: LocalTime,
    onPicked: (LocalTime) -> Unit,
    onCancel: () -> Unit,
) {
    val pickerState = rememberTimePickerState(initialHour = time.hour, initialMinute = time.minute, is24Hour = true)

    AlertDialog(
        onDismissRequest = onCancel,
        confirmButton = {
            TextButton(onClick = { onPicked(LocalTime.of(pickerState.hour, pickerState.minute)) }) {
                Text(stringResource(R.string.manual_save))
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.manual_cancel)) } },
        text = { TimePicker(state = pickerState) },
    )
}

private fun LocalDate.toUtcMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun Long.toUtcLocalDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()

private fun LocalTime.toInstantOn(date: LocalDate): Instant = date.atTime(this).toInstant(ZoneOffset.UTC)

private const val MAX_SUGGESTIONS = 6
