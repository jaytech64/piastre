// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.feature.ledger

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.jaytech64.depenses.core.designsystem.Formatters
import io.github.jaytech64.depenses.core.model.Rhythm
import java.time.LocalDate

/** When it happened, and — on a transaction that can repeat, under the date it counts from — when it comes back. */
@Composable
internal fun WhenSection(
    state: ExpenseFormUiState,
    viewModel: ExpenseFormViewModel,
    onPick: (Picking) -> Unit,
) {
    WhenRow(state = state, onDateChange = viewModel::onDateChange, onPick = onPick)
    if (state.canRepeat) {
        RepeatFields(
            state,
            onRhythmChange = viewModel.repeat::onRhythmChange,
            onPickLastDate = { onPick(Picking.LAST_DATE) },
        )
    }
}

/**
 * How often the transaction comes back, and — once it does — until when, with
 * the next day it will be written on. Every choice is worded from the date
 * already on the form, so there is nothing else to fill in.
 */
@Composable
private fun RepeatFields(
    state: ExpenseFormUiState,
    onRhythmChange: (Rhythm?) -> Unit,
    onPickLastDate: () -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    RhythmField(rhythm = state.rhythm, date = state.date, onPick = onRhythmChange)
    if (state.rhythm == null) return

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        EndField(lastDate = state.lastDate, isError = state.endsBeforeItStarts, onOpen = onPickLastDate)
        state.recurrence?.nextAfter(state.date)?.let { next ->
            Text(
                stringResource(R.string.repeat_next, Formatters.date(next, locale)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RhythmField(
    rhythm: Rhythm?,
    date: LocalDate,
    onPick: (Rhythm?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    fun pick(choice: Rhythm?) {
        onPick(choice)
        expanded = false
    }

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = rhythmLabel(rhythm, date),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.form_repeats)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier =
                Modifier
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            (listOf(null) + Rhythm.entries).forEach { choice ->
                DropdownMenuItem(text = { Text(rhythmLabel(choice, date)) }, onClick = { pick(choice) })
            }
        }
    }
}

/** Opens the calendar rather than a menu: the only choices are a day, or none. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EndField(
    lastDate: LocalDate?,
    isError: Boolean,
    onOpen: () -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    ExposedDropdownMenuBox(expanded = false, onExpandedChange = { onOpen() }) {
        OutlinedTextField(
            value =
                lastDate?.let { stringResource(R.string.repeat_ends_on, Formatters.date(it, locale)) }
                    ?: stringResource(R.string.repeat_never),
            onValueChange = {},
            readOnly = true,
            isError = isError,
            label = { Text(stringResource(R.string.repeat_ends)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = false) },
            modifier =
                Modifier
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
        )
    }
}

/** What a rhythm is called, worded from the day it counts from: *Every month, on the 25th*. */
@Composable
internal fun rhythmLabel(
    rhythm: Rhythm?,
    date: LocalDate,
): String {
    val locale = LocalConfiguration.current.locales[0]
    return when (rhythm) {
        null -> stringResource(R.string.repeat_never)
        Rhythm.WEEKLY -> stringResource(R.string.repeat_weekly, Formatters.weekday(date, locale))
        Rhythm.EVERY_TWO_WEEKS -> stringResource(R.string.repeat_every_two_weeks, Formatters.weekday(date, locale))
        Rhythm.MONTHLY -> stringResource(R.string.repeat_monthly, Formatters.dayOfMonth(date.dayOfMonth, locale))
        Rhythm.YEARLY -> stringResource(R.string.repeat_yearly, Formatters.dayAndMonth(date, locale))
    }
}

/**
 * Asked before a save that would write rows for days already past: how many,
 * and over which days, so a year of rent is added knowingly and a row already
 * typed by hand is not doubled by surprise.
 */
@Composable
internal fun BackfillDialog(
    backfill: Backfill,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            Text(
                pluralStringResource(
                    R.plurals.repeat_backfill,
                    backfill.count,
                    backfill.count,
                    Formatters.date(backfill.first, locale),
                    Formatters.date(backfill.last, locale),
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(pluralStringResource(R.plurals.repeat_backfill_confirm, backfill.count, backfill.count))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.manual_cancel)) } },
    )
}
