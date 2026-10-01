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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jaytech64.depenses.core.designsystem.Formatters
import io.github.jaytech64.depenses.core.model.RecurringTransaction
import io.github.jaytech64.depenses.core.model.TransactionKind
import java.util.Locale

/**
 * Every recurring transaction, the next to be written first. Tapping one opens
 * it in the transaction form, where it can be changed or stopped.
 */
@Composable
fun RecurringListScreen(
    onOpen: (Long) -> Unit,
    viewModel: RecurringListViewModel = hiltViewModel(),
) {
    val schedules by viewModel.schedules.collectAsStateWithLifecycle()
    val locale = LocalConfiguration.current.locales[0]

    Column(Modifier.fillMaxSize()) {
        Text(
            stringResource(R.string.recurring_list_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(16.dp),
        )
        when {
            schedules == null -> {
                Unit
            }

            schedules.orEmpty().isEmpty() -> {
                Text(
                    stringResource(R.string.recurring_list_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            else -> {
                LazyColumn {
                    items(schedules.orEmpty(), key = { it.id }) { schedule ->
                        RecurringRow(schedule, locale, onClick = { onOpen(schedule.id) })
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun RecurringRow(
    schedule: RecurringTransaction,
    locale: Locale,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(schedule.title(), style = MaterialTheme.typography.bodyLarge)
            Text(
                schedule.subtitle(locale),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        KindAmount(schedule.kind, schedule.amount, locale)
    }
}

/** Named as a ledger row names what it writes: its category, else its place. */
@Composable
private fun RecurringTransaction.title(): String = categoryName ?: place()

@Composable
private fun RecurringTransaction.place(): String =
    when (kind) {
        TransactionKind.TRANSFER -> {
            accountLabel(accountName, accountType, null) + " $ARROW " + accountLabel(toAccountName, toAccountType, null)
        }

        else -> {
            merchantName ?: stringResource(R.string.ledger_unknown_merchant)
        }
    }

/**
 * Its rhythm, its merchant when the category has taken the title, and when it
 * is next written — or when it ends. The
 * lines are allowed to wrap: at a large font size the next day is the part that
 * matters most, and it comes last.
 */
@Composable
private fun RecurringTransaction.subtitle(locale: Locale): String {
    val next = nextDue?.let { stringResource(R.string.recurring_list_next, Formatters.date(it, locale)) }
    val ends = recurrence.lastDate?.let { stringResource(R.string.recurring_list_ends, Formatters.date(it, locale)) }
    val placeUnderCategory = categoryName?.let { place() }
    return listOfNotNull(rhythmLabel(recurrence.rhythm, recurrence.firstDate), placeUnderCategory, next, ends)
        .joinToString(SEPARATOR)
}
