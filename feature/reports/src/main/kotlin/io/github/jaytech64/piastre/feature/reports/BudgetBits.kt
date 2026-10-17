// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.feature.reports

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.jaytech64.piastre.core.data.BudgetLine

/**
 * The way to the month's budgets, in the month view alone. Without Premium it
 * stays where it is and leads to the Premium page instead: a locked feature
 * shows its door, not a blank.
 */
@Composable
internal fun BudgetsRow(
    state: ReportsUiState,
    onClick: () -> Unit,
) {
    val body =
        when {
            !state.premiumOn -> stringResource(R.string.budgets_row_premium)
            state.budgets.isEmpty() -> stringResource(R.string.budgets_row_none)
            else -> pluralStringResource(R.plurals.budgets_row_count, state.budgets.size, state.budgets.size)
        }
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(stringResource(R.string.budgets_title), style = MaterialTheme.typography.bodyLarge)
        Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** How much of a budget has gone, in the error colour once it is passed. */
@Composable
internal fun BudgetBar(
    line: BudgetLine,
    modifier: Modifier = Modifier,
    thin: Boolean = false,
) {
    val over = line.spent.minor > line.amount.minor
    LinearProgressIndicator(
        progress = {
            if (line.amount.minor <=
                0
            ) {
                1f
            } else {
                (line.spent.minor.toFloat() / line.amount.minor).coerceIn(0f, 1f)
            }
        },
        modifier = modifier.fillMaxWidth().then(if (thin) Modifier.height(2.dp) else Modifier),
        color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
        trackColor = MaterialTheme.colorScheme.surfaceVariant,
        drawStopIndicator = {},
    )
}
