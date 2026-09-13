// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.feature.ledger

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.jaytech64.depenses.core.designsystem.AccountDialog
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * The one dialog [picking] names over the expense form, or none. Each closes
 * itself through [onClose] once it has acted.
 */
@Composable
internal fun FormDialog(
    picking: Picking,
    state: ExpenseFormUiState,
    viewModel: ExpenseFormViewModel,
    onClose: () -> Unit,
) {
    when (picking) {
        Picking.DATE -> {
            DatePickerSheet(
                date = state.date,
                onPicked = {
                    viewModel.onDateChange(it)
                    onClose()
                },
                onCancel = onClose,
            )
        }

        Picking.TIME -> {
            TimePickerSheet(
                time = state.time,
                onPicked = {
                    viewModel.onTimeChange(it)
                    onClose()
                },
                onCancel = onClose,
            )
        }

        Picking.DELETE -> {
            ConfirmDeleteDialog(
                onConfirm = {
                    viewModel.delete()
                    onClose()
                },
                onCancel = onClose,
            )
        }

        Picking.ADD_ACCOUNT, Picking.ADD_DESTINATION -> {
            val slot = if (picking == Picking.ADD_ACCOUNT) AccountSlot.SOURCE else AccountSlot.DESTINATION
            AccountDialog(
                account = null,
                onSave = { name, type, last4, openingBalance ->
                    viewModel.addAccount(name, type, last4, openingBalance, slot)
                    onClose()
                },
                onDismiss = onClose,
            )
        }

        Picking.NOTHING -> {
            Unit
        }
    }
}

/** Which dialog is over the form. Deleting asks first, because it cannot be undone from here. */
internal enum class Picking { NOTHING, DATE, TIME, DELETE, ADD_ACCOUNT, ADD_DESTINATION }

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

    // DatePickerDialog rather than AlertDialog: the latter's content slot is
    // narrower than the calendar grid, which clips the last weekday column.
    DatePickerDialog(
        onDismissRequest = onCancel,
        confirmButton = {
            TextButton(
                onClick = { pickerState.selectedDateMillis?.let { onPicked(it.toUtcLocalDate()) } },
            ) {
                Text(stringResource(R.string.manual_save))
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.manual_cancel)) } },
    ) {
        DatePicker(state = pickerState)
    }
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

@Composable
private fun ConfirmDeleteDialog(
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.edit_delete_title)) },
        text = { Text(stringResource(R.string.edit_delete_body)) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Text(stringResource(R.string.edit_delete))
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.manual_cancel)) } },
    )
}

private fun LocalDate.toUtcMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun Long.toUtcLocalDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()
