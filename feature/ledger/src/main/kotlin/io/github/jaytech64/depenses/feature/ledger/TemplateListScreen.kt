// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.feature.ledger

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jaytech64.depenses.core.model.TransactionKind
import io.github.jaytech64.depenses.core.model.TransactionTemplate

/**
 * Every template, the one last used first. Tapping one opens it in the
 * transaction form, where it can be changed or deleted; [onNew] opens that form
 * empty.
 */
@Composable
fun TemplateListScreen(
    onOpen: (Long) -> Unit,
    onNew: () -> Unit,
    viewModel: TemplateListViewModel = hiltViewModel(),
) {
    val templates = viewModel.templates.collectAsStateWithLifecycle().value ?: return

    Column(Modifier.fillMaxSize()) {
        Text(
            stringResource(R.string.templates_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(16.dp),
        )
        if (templates.isEmpty()) {
            Text(
                stringResource(R.string.templates_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        LazyColumn {
            items(templates, key = { it.id }) { template ->
                TemplateRow(template, onClick = { onOpen(template.id) })
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            }
            item {
                TextButton(onClick = onNew, modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) {
                    Text(stringResource(R.string.templates_new))
                }
            }
        }
    }
}

/** A template as a line: its name, what it holds under it, and its amount when it has one. */
@Composable
internal fun TemplateRow(
    template: TransactionTemplate,
    onClick: () -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    val holds = template.holds()

    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(template.name, style = MaterialTheme.typography.bodyLarge)
            if (holds.isNotEmpty()) {
                Text(
                    holds,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        template.amount?.let { KindAmount(template.kind, it, locale) }
    }
}

/**
 * What a template fills in besides its amount: the two accounts of a transfer,
 * or the merchant and the account, then the category. The line is allowed to
 * wrap, and is left out when the template holds none of them.
 */
@Composable
private fun TransactionTemplate.holds(): String {
    val account = accountId?.let { accountLabel(accountName, accountType, null) }
    val place =
        if (kind == TransactionKind.TRANSFER) {
            val destination = toAccountId?.let { accountLabel(toAccountName, toAccountType, null) }
            listOfNotNull(account, destination).takeIf { it.isNotEmpty() }?.joinToString(" $ARROW ")
        } else {
            listOfNotNull(merchantName, account).takeIf { it.isNotEmpty() }?.joinToString(SEPARATOR)
        }
    return listOfNotNull(place, categoryName).joinToString(SEPARATOR)
}
