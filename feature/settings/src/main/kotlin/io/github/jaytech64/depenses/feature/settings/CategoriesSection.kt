// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jaytech64.depenses.core.designsystem.CategoryDialog
import io.github.jaytech64.depenses.core.model.CategoryUsage
import java.text.Collator

/**
 * Every category, A to Z the way the screen's language sorts — Épicerie among
 * the E's — with what uses it. A row opens the name to change it, or to delete it.
 */
@Composable
internal fun CategoriesSection(viewModel: CategoriesViewModel = hiltViewModel()) {
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val editing by viewModel.editing.collectAsStateWithLifecycle()
    val locale = LocalConfiguration.current.locales[0]
    val sorted =
        remember(
            categories,
            locale,
        ) { categories.sortedWith(compareBy(Collator.getInstance(locale)) { it.category.name }) }

    SectionHeader(stringResource(R.string.settings_categories_section))

    sorted.forEach { usage ->
        CategoryRow(usage, onClick = { viewModel.edit(CategoryEdit.Existing(usage)) })
    }

    TextButton(onClick = { viewModel.edit(CategoryEdit.New) }, modifier = Modifier.padding(horizontal = 4.dp)) {
        Text(stringResource(R.string.settings_categories_add))
    }

    when (val edit = editing) {
        CategoryEdit.New -> {
            CategoryDialog(name = null, onSave = viewModel::save, onDismiss = { viewModel.edit(null) })
        }

        is CategoryEdit.Existing -> {
            CategoryDialog(
                name = edit.usage.category.name,
                onSave = viewModel::save,
                onDismiss = { viewModel.edit(null) },
                error = stringResource(R.string.settings_categories_name_taken).takeIf { edit.refused },
                onDelete = { viewModel.edit(CategoryEdit.ConfirmDelete(edit.usage)) },
            )
        }

        is CategoryEdit.ConfirmDelete -> {
            ConfirmDeleteDialog(
                edit.usage,
                onConfirm = { viewModel.delete(edit.usage) },
                onCancel = { viewModel.edit(null) },
            )
        }

        null -> {
            Unit
        }
    }
}

@Composable
private fun CategoryRow(
    usage: CategoryUsage,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
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
