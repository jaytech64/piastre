// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.feature.reports

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.jaytech64.depenses.core.designsystem.Formatters
import java.util.Locale

/**
 * Where the period's money went, heaviest first: groups, and beside them the
 * categories that are in no group. A group opens in place onto its categories,
 * so the rest of the ranking stays on screen to compare against.
 *
 * Every bar is a share of the period's spending, a group's and a category's
 * alike, so the categories under an open group add up to the bar above them.
 * What went out with no category cannot be ranked, and the line under the
 * ranking says how much that was rather than leaving a gap unexplained.
 */
@Composable
internal fun CategoryRanking(
    state: ReportsUiState,
    locale: Locale,
    onGroupTapped: (Long) -> Unit,
) {
    if (state.ranking.isEmpty() && state.uncategorised.minor == 0L) return

    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            stringResource(R.string.reports_top_categories),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        state.ranking.forEach { ranked ->
            RankedRow(
                ranked,
                open = ranked.groupId != null && ranked.groupId == state.openGroupId,
                locale,
                onGroupTapped,
            )
        }
        if (state.uncategorised.minor != 0L) {
            Text(
                stringResource(R.string.reports_no_category, Formatters.money(state.uncategorised, locale)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun RankedRow(
    ranked: RankedSpending,
    open: Boolean,
    locale: Locale,
    onGroupTapped: (Long) -> Unit,
) {
    val groupId = ranked.groupId
    val opening =
        if (groupId == null) {
            Modifier
        } else {
            Modifier.clickable(
                onClickLabel =
                    stringResource(
                        if (open) R.string.reports_hide_categories else R.string.reports_show_categories,
                        ranked.name,
                    ),
                role = Role.Button,
                onClick = { onGroupTapped(groupId) },
            )
        }

    Column(
        opening.heightIn(min = 48.dp).padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    ranked.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (groupId != null) {
                    Icon(
                        if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                Formatters.money(ranked.total, locale),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        ShareBar(ranked.share)
    }
    if (open) {
        Column(
            Modifier.padding(start = 16.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ranked.categories.forEach { category -> CategoryRow(category, locale) }
        }
    }
}

@Composable
private fun CategoryRow(
    category: CategoryShare,
    locale: Locale,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(
                category.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                Formatters.money(category.total, locale),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        ShareBar(category.share)
        Text(
            pluralStringResource(R.plurals.reports_transactions, category.count, category.count),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ShareBar(share: Float) {
    LinearProgressIndicator(
        progress = { share },
        modifier = Modifier.fillMaxWidth(),
        drawStopIndicator = {},
    )
}
