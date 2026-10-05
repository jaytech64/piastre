// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.feature.ledger

import androidx.compose.foundation.layout.Column
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
                // The hour of a captured transaction is the one Wallet gave it.
                onRecurring = { viewModel.repeat.onRecurringChange(true) }.takeUnless { state.isCaptured },
                onClose = onClose,
            )
        }

        Picking.DELETE -> {
            ConfirmDeleteDialog(
                wording = state.deleteWording(),
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
            val groups by viewModel.category.groups.collectAsStateWithLifecycle()
            CategoryDialog(
                name = null,
                onSave = { name, groupId ->
                    viewModel.category.add(name, groupId)
                    onClose()
                },
                onDismiss = onClose,
                groups = groups,
            )
        }

        Picking.TEMPLATES -> {
            TemplateSheet(viewModel.template, onClose)
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
internal enum class Picking {
    NOTHING,
    DATE,
    LAST_DATE,
    TIME,
    DELETE,
    ADD_ACCOUNT,
    ADD_DESTINATION,
    ADD_CATEGORY,
    TEMPLATES,
}

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
 *
 * [onRecurring] is the answer that is not an hour — it comes back, so it has a
 * day and no hour — and closes the dialog too. It is null on a captured
 * transaction, whose hour is the one Wallet gave it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerSheet(
    time: LocalTime,
    onPicked: (LocalTime) -> Unit,
    onRecurring: (() -> Unit)?,
    onClose: () -> Unit,
) {
    val pickerState = rememberTimePickerState(initialHour = time.hour, initialMinute = time.minute, is24Hour = true)
    var displayMode by remember { mutableStateOf(TimePickerDisplayMode.Input) }
    val clockFits = LocalConfiguration.current.screenHeightDp.dp > TimePickerDialogDefaults.MinHeightForTimePicker
    val showsClock = displayMode == TimePickerDisplayMode.Picker && clockFits

    TimePickerDialog(
        onDismissRequest = onClose,
        title = { TimePickerDialogDefaults.Title(displayMode = displayMode) },
        confirmButton = {
            TextButton(onClick = { onPicked(LocalTime.of(pickerState.hour, pickerState.minute)) }) {
                Text(stringResource(R.string.manual_save))
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.manual_cancel)) } },
        modeToggleButton = {
            if (clockFits) {
                TimePickerDialogDefaults.DisplayModeToggle(
                    onDisplayModeChange = { displayMode = displayMode.toggled() },
                    displayMode = displayMode,
                )
            }
        },
    ) {
        // Under the hour rather than beside Cancel and Save: the row of actions has no room for a third.
        Column {
            if (showsClock) TimePicker(state = pickerState) else TimeInput(state = pickerState)
            onRecurring?.let {
                TextButton(
                    onClick = {
                        it()
                        onClose()
                    },
                ) { Text(stringResource(R.string.form_time_recurring)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
private fun TimePickerDisplayMode.toggled(): TimePickerDisplayMode =
    if (this == TimePickerDisplayMode.Picker) TimePickerDisplayMode.Input else TimePickerDisplayMode.Picker

@Composable
private fun ConfirmDeleteDialog(
    wording: DeleteWording,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(wording.title) },
        text = { Text(wording.body) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Text(wording.confirm)
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.manual_cancel)) } },
    )
}

/** What the dialog asks before deleting, which says what deleting keeps. */
private data class DeleteWording(
    val title: String,
    val body: String,
    val confirm: String,
)

/**
 * A recurring transaction is stopped, and named by its merchant or — for a
 * transfer, which has none — its amount. A template is deleted by its name. Both
 * say that what they wrote or filled stays.
 */
@Composable
private fun ExpenseFormUiState.deleteWording(): DeleteWording {
    val locale = LocalConfiguration.current.locales[0]
    return when {
        isTemplate -> {
            DeleteWording(
                title = stringResource(R.string.template_delete_title, templateName),
                body = stringResource(R.string.template_delete_body),
                confirm = stringResource(R.string.edit_delete),
            )
        }

        isSchedule -> {
            val name = merchantText.ifBlank { amount?.let { Formatters.money(it, locale) }.orEmpty() }
            DeleteWording(
                title = stringResource(R.string.recurring_stop_title, name),
                body = stringResource(R.string.recurring_stop_body),
                confirm = stringResource(R.string.recurring_stop),
            )
        }

        else -> {
            DeleteWording(
                title = stringResource(R.string.edit_delete_title),
                body = stringResource(R.string.edit_delete_body),
                confirm = stringResource(R.string.edit_delete),
            )
        }
    }
}

private fun LocalDate.toUtcMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

internal fun Long.toUtcLocalDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()
