// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jaytech64.depenses.core.data.CsvLabels
import io.github.jaytech64.depenses.core.data.ExportFormat
import io.github.jaytech64.depenses.core.data.ImportProblem
import io.github.jaytech64.depenses.core.data.ImportSummary
import io.github.jaytech64.depenses.core.designsystem.Formatters

/**
 * Portability and erasure — Law 25 obligations rather than features, and the
 * reason they sit together under one heading rather than among the switches.
 */
@Composable
internal fun DataSection(
    lastAction: LastAction?,
    viewModel: SettingsViewModel,
) {
    var confirming by remember { mutableStateOf<Destructive?>(null) }

    SectionHeader(stringResource(R.string.settings_data_section))

    PortabilityRows(lastAction, viewModel)

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

/**
 * The file leaving the app and a file coming back in, with one outcome line for
 * whichever happened last.
 */
@Composable
private fun PortabilityRows(
    lastAction: LastAction?,
    viewModel: SettingsViewModel,
) {
    val labels = csvLabels()
    val pendingImport by viewModel.pendingImport.collectAsStateWithLifecycle()

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

    SettingRow(
        title = stringResource(R.string.settings_import),
        body = stringResource(R.string.settings_import_body),
    ) {
        ImportButton(viewModel)
    }

    lastAction?.let { ActionOutcome(it) }

    pendingImport?.let { reading ->
        ImportDialog(reading.summary, onConfirm = viewModel::confirmImport, onDismiss = viewModel::cancelImport)
    }
}

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
private fun ImportButton(viewModel: SettingsViewModel) {
    val launcher =
        rememberLauncherForActivityResult(OpenDocument()) { uri ->
            uri?.let(viewModel::readImport)
        }
    OutlinedButton(onClick = { launcher.launch(IMPORT_TYPES) }) {
        Text(stringResource(R.string.settings_import_action))
    }
}

/**
 * A file saved to Downloads is not always typed as JSON by the provider holding
 * it, so the picker also offers the generic types such a file lands under.
 */
private val IMPORT_TYPES = arrayOf("application/json", "application/octet-stream", "text/plain")

/**
 * What an import will do, said before it does it. Import has no undo, so the
 * summary names what is added, what is left alone, and every opening balance of
 * yours the file disagrees with.
 */
@Composable
private fun ImportDialog(
    summary: ImportSummary,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                pluralStringResource(R.plurals.settings_import_title, summary.newTransactions, summary.newTransactions),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                summary.firstDay?.let { first ->
                    Text(
                        stringResource(
                            R.string.settings_import_period,
                            Formatters.date(first),
                            Formatters.date(summary.lastDay ?: first),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(stringResource(R.string.settings_import_accounts, summary.accountsFound, summary.accountsCreated))
                Text(stringResource(R.string.settings_import_opening, summary.openingBalancesSet))
                summary.openingBalancesKept.forEach { kept ->
                    Text(
                        stringResource(
                            R.string.settings_import_kept,
                            kept.account,
                            Formatters.money(kept.inFile),
                            Formatters.money(kept.kept),
                        ),
                    )
                }
                Text(stringResource(R.string.settings_import_merchants, summary.newMerchants))
                Text(stringResource(R.string.settings_import_already, summary.alreadyHere))
                Text(
                    stringResource(R.string.settings_import_no_undo),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.settings_import_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) }
        },
    )
}

@Composable
private fun importProblem(problem: ImportProblem): String =
    when (problem) {
        ImportProblem.NotAnExport -> {
            stringResource(R.string.settings_import_not_export)
        }

        is ImportProblem.UnsupportedVersion -> {
            stringResource(R.string.settings_import_version, problem.version)
        }

        is ImportProblem.Inconsistent -> {
            stringResource(R.string.settings_import_inconsistent, problem.detail)
        }

        is ImportProblem.AmbiguousAccount -> {
            stringResource(R.string.settings_import_ambiguous, problem.name)
        }

        is ImportProblem.Unreadable -> {
            problem.reason
                ?.let { stringResource(R.string.settings_import_unreadable, it) }
                ?: stringResource(R.string.settings_import_unreadable_unknown)
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

            is LastAction.Imported -> {
                pluralStringResource(
                    R.plurals.settings_import_done,
                    action.transactions,
                    action.transactions,
                    action.alreadyHere,
                )
            }

            is LastAction.ImportRefused -> {
                importProblem(action.problem)
            }

            LastAction.RawLogCleared -> {
                stringResource(R.string.settings_clear_raw_done)
            }

            LastAction.Erased -> {
                stringResource(R.string.settings_erase_done)
            }
        }
    val colour =
        if (action is LastAction.ExportFailed || action is LastAction.ImportRefused) {
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
        type = stringResource(R.string.export_column_type),
        account = stringResource(R.string.export_column_account),
        toAccount = stringResource(R.string.export_column_to_account),
        category = stringResource(R.string.export_column_category),
    )
