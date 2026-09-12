// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.feature.ledger

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jaytech64.depenses.core.designsystem.Formatters
import io.github.jaytech64.depenses.core.model.Money
import io.github.jaytech64.depenses.core.model.Transaction
import io.github.jaytech64.depenses.core.model.TransactionSource
import java.time.LocalDate
import java.util.Locale

@Composable
fun LedgerScreen(
    onReviewClick: () -> Unit,
    onAddClick: () -> Unit,
    onTransactionClick: (Long) -> Unit,
    viewModel: LedgerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val today = state.today

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TodayHeader(
                balance = state.balanceToday,
                needsReview = state.needsReview,
                onReviewClick = onReviewClick,
            )

            when {
                state.isEmpty -> EmptyLedger()
                today != null -> DayList(state.days, today, onTransactionClick)
            }
        }

        FloatingActionButton(
            onClick = onAddClick,
            modifier =
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
        ) {
            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.manual_add))
        }
    }
}

@Composable
private fun TodayHeader(
    balance: Money,
    needsReview: Int,
    onReviewClick: () -> Unit,
) {
    Card(
        Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.ledger_balance_today),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(Formatters.signedMoney(balance), style = MaterialTheme.typography.headlineMedium)

            // Shown only when there is something to say. Totals exclude unreviewed
            // purchases, so without this a low number would be a silent lie — and
            // saying it without offering the way to fix it would only be half honest.
            if (needsReview > 0) {
                AssistChip(
                    onClick = onReviewClick,
                    label = { Text(pluralStringResource(R.plurals.ledger_needs_review, needsReview, needsReview)) },
                    colors = AssistChipDefaults.assistChipColors(labelColor = MaterialTheme.colorScheme.tertiary),
                )
            }
        }
    }
}

@Composable
private fun DayList(
    days: List<LedgerDay>,
    today: LocalDate,
    onTransactionClick: (Long) -> Unit,
) {
    val locale = currentLocale()
    val labels =
        Formatters.RelativeDayLabels(
            today = stringResource(R.string.ledger_today),
            yesterday = stringResource(R.string.ledger_yesterday),
        )

    LazyColumn(contentPadding = PaddingValues(bottom = ADD_BUTTON_CLEARANCE)) {
        days.forEach { day ->
            item(key = day.date.toEpochDay()) {
                DayHeader(
                    label = Formatters.dayHeader(day.date, today, labels, locale),
                    total = day.total,
                )
            }
            items(day.transactions, key = { it.id }) { transaction ->
                TransactionRow(transaction, locale, onClick = { onTransactionClick(transaction.id) })
            }
        }
    }
}

@Composable
private fun DayHeader(
    label: String,
    total: Money,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Text(
            Formatters.signedMoney(total),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyLedger() {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.ledger_empty_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.size(8.dp))
        Text(
            stringResource(R.string.ledger_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Compose's configuration rather than [Locale.getDefault]: the two differ the
 * moment the user sets a per-app language, and the formatter must follow what is
 * actually on screen.
 */
@Composable
private fun currentLocale(): Locale = LocalConfiguration.current.locales[0]

/**
 * Room below the last row for the floating add button: its 56 dp height, its
 * 16 dp margin, and 16 dp of air. Without it the newest-first list ends with the
 * oldest purchase's amount hidden under the button, unreachable by scrolling.
 */
private val ADD_BUTTON_CLEARANCE = 88.dp
