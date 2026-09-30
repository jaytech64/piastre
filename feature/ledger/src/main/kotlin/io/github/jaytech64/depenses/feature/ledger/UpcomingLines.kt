// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.feature.ledger

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.jaytech64.depenses.core.designsystem.Formatters
import java.time.LocalDate

/**
 * What is dated after today, above today and set apart from it: one line saying
 * how many and what they come to, and — once opened — each of them with its own
 * day. Nothing here counts in any figure above it (ADR-0015).
 *
 * A tap opens a line; there is no long press, so a selection, and its total,
 * never holds something that counts nowhere yet.
 */
internal fun LazyListScope.upcomingGroup(
    state: LedgerUiState,
    today: LocalDate,
    labels: Formatters.RelativeDayLabels,
    onToggle: () -> Unit,
    onOpenLine: (UpcomingLine) -> Unit,
) {
    if (state.upcoming.isEmpty()) return
    item(key = UPCOMING_KEY) {
        UpcomingHeader(
            count = state.upcoming.size,
            total = Formatters.signedMoney(state.upcomingSummary.balance, currentLocale()),
            open = state.upcomingOpen,
            enabled = !state.isSelecting,
            onToggle = onToggle,
        )
    }
    if (!state.upcomingOpen) return
    itemsIndexed(state.upcoming, key = { _, line -> "$UPCOMING_KEY-${line.transaction.id}" }) { index, line ->
        val locale = currentLocale()
        // Joined to the line above as one card, so what is to come never reads as
        // the first rows of the list.
        val last = index == state.upcoming.lastIndex
        Box(
            Modifier
                .padding(start = 16.dp, end = 16.dp, bottom = if (last) 8.dp else 0.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceContainerLow,
                    if (last) RoundedCornerShape(bottomStart = CORNER, bottomEnd = CORNER) else RectangleShape,
                ),
        ) {
            TransactionRow(
                transaction = line.transaction,
                locale = locale,
                selection = RowSelection.NONE,
                onClick = { if (!state.isSelecting) onOpenLine(line) },
                onLongClick = null,
                dayLabel = Formatters.dayHeader(line.transaction.localDate, today, labels, locale),
            )
        }
    }
}

/** The line the group collapses to: which way it opens, how many, and what they come to. */
@Composable
private fun UpcomingHeader(
    count: Int,
    total: String,
    open: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    Surface(
        shape = if (open) RoundedCornerShape(topStart = CORNER, topEnd = CORNER) else RoundedCornerShape(CORNER),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant).takeUnless { open },
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = if (open) 0.dp else 8.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(
                    enabled = enabled,
                    role = Role.Button,
                    onClickLabel =
                        stringResource(
                            if (open) R.string.ledger_upcoming_hide else R.string.ledger_upcoming_show,
                        ),
                    onClick = onToggle,
                ).padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (open) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                pluralStringResource(R.plurals.ledger_upcoming, count, count),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            Text(
                total,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private const val UPCOMING_KEY = "upcoming"

private val CORNER = 12.dp
