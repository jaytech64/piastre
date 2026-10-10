// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import io.github.jaytech64.piastre.core.model.CategoryGroupUsage
import java.text.Collator

/**
 * Every group, A to Z the way the screen's language sorts, with how many
 * categories it holds and how many transactions they carry; then the categories
 * in no group, while there are any. A group opens its own page. [onOpen] is given
 * null for the categories in no group.
 */
@Composable
fun CategoriesScreen(
    onOpen: (groupId: Long?) -> Unit,
    viewModel: CategoriesViewModel = hiltViewModel(),
) {
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val adding by viewModel.adding.collectAsStateWithLifecycle()
    val locale = LocalConfiguration.current.locales[0]
    val sorted =
        remember(groups, locale) {
            val (named, ungrouped) = groups.partition { it.group != null }
            named.sortedWith(compareBy(Collator.getInstance(locale)) { it.group?.name }) + ungrouped
        }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            stringResource(R.string.settings_categories_section),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(16.dp),
        )

        sorted.forEach { usage ->
            SettingRow(
                title = usage.group?.name ?: stringResource(R.string.categories_ungrouped),
                body = holds(usage),
                onClick = { onOpen(usage.group?.id) },
            )
        }

        TextButton(onClick = { viewModel.add(true) }, modifier = Modifier.padding(horizontal = 4.dp)) {
            Text(stringResource(R.string.categories_add_group))
        }
    }

    if (adding) GroupDialog(name = null, onSave = viewModel::save, onDismiss = { viewModel.add(false) })
}

/** "3 categories · 14 transactions", or that it holds none yet. */
@Composable
private fun holds(usage: CategoryGroupUsage): String {
    if (usage.categoryCount == 0) return stringResource(R.string.categories_group_empty)
    return listOf(
        pluralStringResource(R.plurals.settings_categories_count, usage.categoryCount, usage.categoryCount),
        pluralStringResource(R.plurals.settings_accounts_transactions, usage.transactionCount, usage.transactionCount),
    ).joinToString(SEPARATOR)
}

private const val SEPARATOR = " · "
