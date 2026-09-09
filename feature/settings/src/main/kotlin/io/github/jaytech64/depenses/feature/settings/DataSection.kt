// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import io.github.jaytech64.depenses.core.data.CsvLabels
import io.github.jaytech64.depenses.core.data.ExportFormat

/**
 * Portability and erasure — Law 25 obligations rather than features, and the
 * reason they sit together under one heading rather than among the switches.
 */
@Composable
internal fun DataSection(
    lastAction: LastAction?,
    viewModel: SettingsViewModel,
) {
    val labels = csvLabels()
    var confirming by remember { mutableStateOf<Destructive?>(null) }

    SectionHeader(stringResource(R.string.settings_data_section))

    SettingRow(
        title = stringResource(R.string.settings_export),
        body = stringResource(R.string.settings_export_body),
    )
    Row(
        Modifier.padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ExportButton(ExportFormat.CSV, stringResource(R.string.settings_export_csv), labels, viewModel)
        ExportButton(ExportFormat.JSON, stringResource(R.string.settings_export_json), labels, viewModel)
    }

    lastAction?.let { ActionOutcome(it) }

    SettingRow(
        title = stringResource(R.string.settings_clear_raw),
        body = stringResource(R.string.settings_clear_raw_body),
    ) {
        OutlinedButton(onClick = { confirming = Destructive.RAW_LOG }) {
            Text(stringResource(R.string.settings_clear_raw_action))
        }
    }

    SettingRow(
        title = stringResource(R.string.settings_erase),
        body = stringResource(R.string.settings_erase_body),
        titleColor = MaterialTheme.colorScheme.error,
    ) {
        OutlinedButton(onClick = { confirming = Destructive.EVERYTHING }) {
            Text(stringResource(R.string.settings_erase_action), color = MaterialTheme.colorScheme.error)
        }
    }

    when (confirming) {
        null -> {
            Unit
        }

        Destructive.RAW_LOG -> {
            ClearRawLogDialog(
                onDismiss = { confirming = null },
                onConfirm = {
                    viewModel.clearRawLog()
                    confirming = null
                },
            )
        }

        Destructive.EVERYTHING -> {
            EraseEverythingDialog(
                onDismiss = { confirming = null },
                onConfirm = {
                    viewModel.eraseEverything()
                    confirming = null
                },
            )
        }
    }
}

private enum class Destructive { RAW_LOG, EVERYTHING }

@Composable
private fun ExportButton(
    format: ExportFormat,
    label: String,
    labels: CsvLabels,
    viewModel: SettingsViewModel,
) {
    // CreateDocument fixes its mime type at construction, so each format needs its
    // own launcher rather than one taking the type at launch.
    val launcher =
        rememberLauncherForActivityResult(CreateDocument(format.mimeType)) { uri ->
            uri?.let { viewModel.export(it, format, labels) }
        }
    OutlinedButton(onClick = { launcher.launch(viewModel.suggestedFileName(format)) }) {
        Text(label)
    }
}

@Composable
private fun ActionOutcome(action: LastAction) {
    val text =
        when (action) {
            is LastAction.Exported -> {
                pluralStringResource(R.plurals.settings_export_done, action.transactions, action.transactions)
            }

            is LastAction.ExportFailed -> {
                action.reason
                    ?.let { stringResource(R.string.settings_export_failed, it) }
                    ?: stringResource(R.string.settings_export_failed_unknown)
            }

            LastAction.RawLogCleared -> {
                stringResource(R.string.settings_clear_raw_done)
            }

            LastAction.Erased -> {
                stringResource(R.string.settings_erase_done)
            }
        }
    val colour =
        if (action is LastAction.ExportFailed) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }

    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = colour,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun ClearRawLogDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_clear_raw_title)) },
        text = { Text(stringResource(R.string.settings_clear_raw_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.settings_clear_raw_action)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) }
        },
    )
}

/**
 * Typing the word is the guard, not a second tap.
 *
 * This is the one action in the app with nothing behind it — no undo, no export
 * already taken, no copy anywhere else, because the app has no network. A
 * confirm button you can hit twice by reflex is not a guard against that.
 */
@Composable
private fun EraseEverythingDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val keyword = stringResource(R.string.settings_erase_keyword)
    var typed by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_erase_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.settings_erase_message, keyword))
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    singleLine = true,
                    label = { Text(keyword) },
                    // Autocorrect off, or the keyboard rewrites the confirmation
                    // out from under you: typing EFFACE on a real phone produced
                    // "GRACE ", which the guard then refused for the wrong reason.
                    keyboardOptions =
                        KeyboardOptions(
                            capitalization = KeyboardCapitalization.Characters,
                            autoCorrectEnabled = false,
                            imeAction = ImeAction.Done,
                        ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = typed.trim() == keyword) {
                Text(stringResource(R.string.settings_erase_action), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) }
        },
    )
}

/** The CSV headings, read here because this is where the resources live. */
@Composable
private fun csvLabels() =
    CsvLabels(
        date = stringResource(R.string.export_column_date),
        time = stringResource(R.string.export_column_time),
        merchant = stringResource(R.string.export_column_merchant),
        amount = stringResource(R.string.export_column_amount),
        currency = stringResource(R.string.export_column_currency),
        source = stringResource(R.string.export_column_source),
        status = stringResource(R.string.export_column_status),
        card = stringResource(R.string.export_column_card),
        note = stringResource(R.string.export_column_note),
    )
