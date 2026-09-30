// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.feature.ledger

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDialog
import androidx.compose.material3.TimePickerDialogDefaults
import androidx.compose.material3.TimePickerDisplayMode
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.jaytech64.depenses.core.designsystem.AccountDialog
import io.github.jaytech64.depenses.core.designsystem.CategoryDialog
import io.github.jaytech64.depenses.core.designsystem.Formatters
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
        Picking.DATE, Picking.LAST_DATE -> {
            DateDialog(picking, state, viewModel, onClose)
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
                stopping = state.stoppingName(),
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

        Picking.ADD_CATEGORY -> {
            CategoryDialog(
                name = null,
                onSave = { name ->
                    viewModel.category.add(name)
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

/** The calendar for the transaction's own day, or for the last day it repeats on, which can also be none. */
@Composable
private fun DateDialog(
    picking: Picking,
    state: ExpenseFormUiState,
    viewModel: ExpenseFormViewModel,
    onClose: () -> Unit,
) {
    if (picking == Picking.DATE) {
        DatePickerSheet(
            date = state.date,
            onPicked = {
                viewModel.onDateChange(it)
                onClose()
            },
            onCancel = onClose,
        )
        return
    }
    DatePickerSheet(
        date = state.lastDate ?: state.recurrence?.nextAfter(state.date) ?: state.date,
        onPicked = {
            viewModel.repeat.onLastDateChange(it)
            onClose()
        },
        onCancel = onClose,
        onNone = {
            viewModel.repeat.onLastDateChange(null)
            onClose()
        },
    )
}

/** Which dialog is over the form. Deleting asks first, because it cannot be undone from here. */
internal enum class Picking { NOTHING, DATE, LAST_DATE, TIME, DELETE, ADD_ACCOUNT, ADD_DESTINATION, ADD_CATEGORY }

/**
 * A calendar for one day. [onNone], when given, is a button beside *Cancel* that
 * takes the day away again, named [noneLabel]; [selectable] greys out the days
 * that cannot be picked.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DatePickerSheet(
    date: LocalDate,
    onPicked: (LocalDate) -> Unit,
    onCancel: () -> Unit,
    onNone: (() -> Unit)? = null,
    noneLabel: String = stringResource(R.string.repeat_never),
    confirmLabel: String = stringResource(R.string.manual_save),
    selectable: SelectableDates = DatePickerDefaults.AllDates,
) {
    // The picker speaks UTC millis; the date is held as a plain LocalDate, so the
    // conversion is pinned to UTC at both ends rather than to a zone that would
    // shift the day by one either side of midnight.
    val pickerState =
        rememberDatePickerState(initialSelectedDateMillis = date.toUtcMillis(), selectableDates = selectable)

    // DatePickerDialog rather than AlertDialog: the latter's content slot is
    // narrower than the calendar grid, which clips the last weekday column.
    DatePickerDialog(
        onDismissRequest = onCancel,
        confirmButton = {
            TextButton(
                onClick = { pickerState.selectedDateMillis?.let { onPicked(it.toUtcLocalDate()) } },
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            // A day can be taken away again, which the calendar alone cannot say.
            onNone?.let { TextButton(onClick = it) { Text(noneLabel) } }
            TextButton(onClick = onCancel) { Text(stringResource(R.string.manual_cancel)) }
        },
    ) {
        DatePicker(state = pickerState)
    }
}

/**
 * Opens on typed hour and minute fields, since a time read off a receipt is
 * quicker typed than dialled; the toggle in the corner swaps to the clock face
 * and back. The clock is withheld on a window too short to hold it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerSheet(
    time: LocalTime,
    onPicked: (LocalTime) -> Unit,
    onCancel: () -> Unit,
) {
    val pickerState = rememberTimePickerState(initialHour = time.hour, initialMinute = time.minute, is24Hour = true)
    var displayMode by remember { mutableStateOf(TimePickerDisplayMode.Input) }
    val clockFits = LocalConfiguration.current.screenHeightDp.dp > TimePickerDialogDefaults.MinHeightForTimePicker
    val showsClock = displayMode == TimePickerDisplayMode.Picker && clockFits

    TimePickerDialog(
        onDismissRequest = onCancel,
        title = { TimePickerDialogDefaults.Title(displayMode = displayMode) },
        confirmButton = {
            TextButton(onClick = { onPicked(LocalTime.of(pickerState.hour, pickerState.minute)) }) {
                Text(stringResource(R.string.manual_save))
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.manual_cancel)) } },
        modeToggleButton = {
            if (clockFits) {
                TimePickerDialogDefaults.DisplayModeToggle(
                    onDisplayModeChange = { displayMode = displayMode.toggled() },
                    displayMode = displayMode,
                )
            }
        },
    ) {
        if (showsClock) TimePicker(state = pickerState) else TimeInput(state = pickerState)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
private fun TimePickerDisplayMode.toggled(): TimePickerDisplayMode =
    if (this == TimePickerDisplayMode.Picker) TimePickerDisplayMode.Input else TimePickerDisplayMode.Picker

@Composable
private fun ConfirmDeleteDialog(
    stopping: String?,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Text(
                stopping?.let { stringResource(R.string.recurring_stop_title, it) }
                    ?: stringResource(R.string.edit_delete_title),
            )
        },
        text = {
            Text(
                stringResource(
                    if (stopping !=
                        null
                    ) {
                        R.string.recurring_stop_body
                    } else {
                        R.string.edit_delete_body
                    },
                ),
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Text(stringResource(if (stopping != null) R.string.recurring_stop else R.string.edit_delete))
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.manual_cancel)) } },
    )
}

/**
 * What a recurring transaction is called when asking whether to stop it: its
 * merchant, or — for a transfer, which has none — its amount. Null on anything
 * that is not a recurring transaction.
 */
@Composable
private fun ExpenseFormUiState.stoppingName(): String? {
    if (!isSchedule) return null
    val locale = LocalConfiguration.current.locales[0]
    return merchantText.ifBlank { amount?.let { Formatters.money(it, locale) }.orEmpty() }
}

private fun LocalDate.toUtcMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

internal fun Long.toUtcLocalDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()
