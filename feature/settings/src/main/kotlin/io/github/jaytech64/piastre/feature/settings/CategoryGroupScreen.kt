// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jaytech64.piastre.core.designsystem.CategoryDialog
import io.github.jaytech64.piastre.core.designsystem.MarkCircle
import io.github.jaytech64.piastre.core.designsystem.categoryMark
import io.github.jaytech64.piastre.core.model.CategoryGroup
import io.github.jaytech64.piastre.core.model.CategoryUsage
import java.text.Collator

/**
 * One group's categories, A to Z the way the screen's language sorts — Épicerie
 * among the E's — with what uses each. A row opens the name to change it, move it
 * to another group, or delete it. The title's buttons rename or delete the group
 * itself; the page of categories in no group has neither. [onClose] is called once
 * the group is deleted.
 */
@Composable
fun CategoryGroupScreen(
    onClose: () -> Unit,
    viewModel: CategoryGroupViewModel = hiltViewModel(),
) {
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val group by viewModel.group.collectAsStateWithLifecycle()
    val editing by viewModel.editing.collectAsStateWithLifecycle()
    val deleted by viewModel.deleted.collectAsStateWithLifecycle()
    val locale = LocalConfiguration.current.locales[0]
    val sorted =
        remember(
            categories,
            locale,
        ) { categories.sortedWith(compareBy(Collator.getInstance(locale)) { it.category.name }) }

    LaunchedEffect(deleted) { if (deleted) onClose() }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        GroupTitle(
            name =
                if (viewModel.groupId ==
                    null
                ) {
                    stringResource(R.string.categories_ungrouped)
                } else {
                    group?.name.orEmpty()
                },
            editable = viewModel.groupId != null,
            onRename = { viewModel.edit(CategoryEdit.RenameGroup()) },
            onDelete = { viewModel.edit(CategoryEdit.ConfirmDeleteGroup) },
        )

        sorted.forEach { usage ->
            CategoryRow(usage, onClick = { viewModel.edit(CategoryEdit.Existing(usage)) })
        }

        TextButton(onClick = { viewModel.edit(CategoryEdit.New) }, modifier = Modifier.padding(horizontal = 4.dp)) {
            Text(stringResource(R.string.settings_categories_add))
        }
    }

    CategoryGroupDialogs(editing, viewModel, groups, group, categories.size)
}

/** Whichever dialog [editing] says is open over the page, if any. */
@Composable
private fun CategoryGroupDialogs(
    editing: CategoryEdit?,
    viewModel: CategoryGroupViewModel,
    groups: List<CategoryGroup>,
    group: CategoryGroup?,
    categoryCount: Int,
) {
    when (val edit = editing) {
        CategoryEdit.New -> {
            CategoryDialog(
                name = null,
                onSave = viewModel::save,
                onDismiss = { viewModel.edit(null) },
                groups = groups,
                groupId = viewModel.groupId,
            )
        }

        is CategoryEdit.Existing -> {
            CategoryDialog(
                name = edit.usage.category.name,
                onSave = viewModel::save,
                onDismiss = { viewModel.edit(null) },
                error = stringResource(R.string.settings_categories_name_taken).takeIf { edit.refused },
                onDelete = { viewModel.edit(CategoryEdit.ConfirmDelete(edit.usage)) },
                groups = groups,
                groupId = edit.usage.category.groupId,
                icon = edit.usage.category.icon,
            )
        }

        is CategoryEdit.ConfirmDelete -> {
            ConfirmDeleteDialog(
                edit.usage,
                onConfirm = { viewModel.delete(edit.usage) },
                onCancel = { viewModel.edit(null) },
            )
        }

        is CategoryEdit.RenameGroup -> {
            GroupDialog(
                name = group?.name.orEmpty(),
                onSave = viewModel::renameGroup,
                onDismiss = { viewModel.edit(null) },
                refused = edit.refused,
            )
        }

        CategoryEdit.ConfirmDeleteGroup -> {
            ConfirmDeleteGroupDialog(
                name = group?.name.orEmpty(),
                categoryCount = categoryCount,
                onConfirm = viewModel::deleteGroup,
                onCancel = { viewModel.edit(null) },
            )
        }

        null -> {
            Unit
        }
    }
}

/** The group's name, with its rename and delete buttons when it is a group you made. */
@Composable
private fun GroupTitle(
    name: String,
    editable: Boolean,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 8.dp, end = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
        if (editable) {
            IconButton(onClick = onRename) {
                Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.categories_group_rename))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.categories_group_delete))
            }
        }
    }
}

/**
 * Says what deleting a group undoes: its categories are left in none. Every
 * transaction keeps its category, which the dialog says too.
 */
@Composable
private fun ConfirmDeleteGroupDialog(
    name: String,
    categoryCount: Int,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val ungroups =
        pluralStringResource(R.plurals.categories_group_delete_ungroups, categoryCount, categoryCount)
            .takeIf { categoryCount > 0 }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.settings_categories_delete_title, name)) },
        text = {
            Text(
                listOfNotNull(ungroups, stringResource(R.string.categories_group_delete_no_transaction))
                    .joinToString(" "),
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.settings_categories_delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.settings_categories_cancel)) }
        },
    )
}

@Composable
private fun CategoryRow(
    usage: CategoryUsage,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MarkCircle(categoryMark(usage.category.name, usage.category.icon))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(usage.category.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                listOfNotNull(
                    pluralStringResource(
                        R.plurals.settings_accounts_transactions,
                        usage.transactionCount,
                        usage.transactionCount,
                    ),
                    automaticFor(usage)?.let { stringResource(R.string.settings_categories_automatic, it) },
                ).joinToString(SEPARATOR),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Says exactly what deleting undoes: the transactions left with no category, and
 * the merchants and accounts that stop giving it. Nothing about money, because
 * nothing about money changes — which the dialog says too.
 */
@Composable
private fun ConfirmDeleteDialog(
    usage: CategoryUsage,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val transactions =
        pluralStringResource(
            R.plurals.settings_categories_delete_transactions,
            usage.transactionCount,
            usage.transactionCount,
        ).takeIf { usage.transactionCount > 0 }
    val stops =
        automaticFor(usage)?.let {
            pluralStringResource(
                R.plurals.settings_categories_delete_automatic,
                usage.merchantCount + usage.accountCount,
                it,
            )
        }
    val undone =
        when {
            transactions != null && stops != null -> {
                stringResource(
                    R.string.settings_categories_delete_both,
                    transactions,
                    stops,
                )
            }

            else -> {
                (transactions ?: stops)?.let { stringResource(R.string.settings_categories_delete_one, it) }
            }
        }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.settings_categories_delete_title, usage.category.name)) },
        text = {
            Text(listOfNotNull(undone, stringResource(R.string.settings_categories_delete_no_money)).joinToString(" "))
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.settings_categories_delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onCancel,
            ) { Text(stringResource(R.string.settings_categories_cancel)) }
        },
    )
}

/** "2 merchants", "1 account", "2 merchants and 1 account", or null when nothing gives it automatically. */
@Composable
private fun automaticFor(usage: CategoryUsage): String? {
    val merchants =
        pluralStringResource(R.plurals.settings_categories_merchants, usage.merchantCount, usage.merchantCount)
            .takeIf { usage.merchantCount > 0 }
    val accounts =
        pluralStringResource(R.plurals.settings_categories_accounts, usage.accountCount, usage.accountCount)
            .takeIf { usage.accountCount > 0 }
    return if (merchants != null && accounts != null) {
        stringResource(R.string.settings_categories_and, merchants, accounts)
    } else {
        merchants ?: accounts
    }
}

private const val SEPARATOR = " · "
