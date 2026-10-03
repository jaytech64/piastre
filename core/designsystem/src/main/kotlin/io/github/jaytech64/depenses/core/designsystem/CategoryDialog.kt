// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import io.github.jaytech64.depenses.core.model.CategoryGroup

/**
 * Names a new category, or renames one. The expense form and Settings open this
 * same dialog, so a category is named the same way from either place.
 *
 * [name] is null when adding. [error] is shown under the field when the last
 * attempt was refused — a rename onto a name another category already has.
 * [onDelete] is null when deleting is not offered, and the button is then not drawn.
 *
 * [groupId] is the group the category starts in, null for none; the field that
 * changes it is drawn only once there are [groups] to pick from. Typing the name
 * of a category you already have picks that one, which stays in its own group.
 */
@Composable
fun CategoryDialog(
    name: String?,
    onSave: (name: String, groupId: Long?) -> Unit,
    onDismiss: () -> Unit,
    error: String? = null,
    onDelete: (() -> Unit)? = null,
    groups: List<CategoryGroup> = emptyList(),
    groupId: Long? = null,
) {
    var typed by remember(name) { mutableStateOf(name.orEmpty()) }
    var group by remember(groupId) { mutableStateOf(groupId) }

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
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    label = { Text(stringResource(R.string.category_dialog_name)) },
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    singleLine = true,
                )
                if (groups.isNotEmpty()) GroupField(group, groups, onPick = { group = it })
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(typed, group) }, enabled = typed.isNotBlank()) {
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

/** The group the category sits in, picked from yours, or none. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupField(
    selectedId: Long?,
    groups: List<CategoryGroup>,
    onPick: (Long?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    fun pick(id: Long?) {
        onPick(id)
        expanded = false
    }

    val none = stringResource(R.string.category_dialog_no_group)
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = groups.firstOrNull { it.id == selectedId }?.name ?: none,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.category_dialog_group)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier =
                Modifier
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text(none) }, onClick = { pick(null) })
            groups.forEach { group ->
                DropdownMenuItem(text = { Text(group.name) }, onClick = { pick(group.id) })
            }
        }
    }
}
