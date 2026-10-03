// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.feature.settings

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
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
 * Names a new group, or renames one. [name] is null when adding. [refused] says,
 * under the field, that another group already has the name last tried.
 */
@Composable
internal fun GroupDialog(
    name: String?,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
    refused: Boolean = false,
) {
    var typed by remember(name) { mutableStateOf(name.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (name == null) R.string.categories_group_add_title else R.string.categories_group_rename,
                ),
            )
        },
        text = {
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it },
                label = { Text(stringResource(R.string.categories_group_name)) },
                isError = refused,
                supportingText =
                    if (refused) {
                        (
                            {
                                Text(
                                    stringResource(R.string.categories_group_name_taken),
                                )
                            }
                        )
                    } else {
                        null
                    },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(typed) }, enabled = typed.isNotBlank()) {
                Text(
                    stringResource(if (name == null) R.string.categories_group_add else R.string.categories_group_save),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_categories_cancel)) }
        },
    )
}
