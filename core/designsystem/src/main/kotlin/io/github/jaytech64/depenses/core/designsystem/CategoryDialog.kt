// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.designsystem

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization

/**
 * Names a new category, or renames one. The expense form and Settings open this
 * same dialog, so a category is named the same way from either place.
 *
 * [name] is null when adding. [error] is shown under the field when the last
 * attempt was refused — a rename onto a name another category already has.
 * [onDelete] is null when deleting is not offered, and the button is then not drawn.
 */
@Composable
fun CategoryDialog(
    name: String?,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
    error: String? = null,
    onDelete: (() -> Unit)? = null,
) {
    var typed by remember(name) { mutableStateOf(name.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (name ==
                        null
                    ) {
                        R.string.category_dialog_add_title
                    } else {
                        R.string.category_dialog_rename_title
                    },
                ),
            )
        },
        text = {
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it },
                label = { Text(stringResource(R.string.category_dialog_name)) },
                isError = error != null,
                supportingText = error?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(typed) }, enabled = typed.isNotBlank()) {
                Text(stringResource(if (name == null) R.string.category_dialog_add else R.string.category_dialog_save))
            }
        },
        dismissButton = {
            Row {
                onDelete?.let {
                    TextButton(
                        onClick = it,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) {
                        Text(stringResource(R.string.category_dialog_delete))
                    }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.category_dialog_cancel)) }
            }
        },
    )
}
