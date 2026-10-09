// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.feature.reports

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import io.github.jaytech64.piastre.core.designsystem.Formatters
import io.github.jaytech64.piastre.core.model.Money
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
 *
 * A group's row is taken by opening and closing it, so everything that leads to
 * transactions instead carries an arrow: a category, the line under an open
 * group that stands for the whole of it, and the line for what has no category.
 */
@Composable
internal fun CategoryRanking(
    state: ReportsUiState,
    locale: Locale,
    onGroupTapped: (Long) -> Unit,
    onOpenTransactions: (SpendingOf) -> Unit,
) {
    if (state.ranking.isEmpty() && state.uncategorised.minor == 0L) return

    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            stringResource(R.string.reports_top_categories),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        state.ranking.forEach { ranked ->
            val groupId = ranked.groupId
            val categoryId = ranked.categoryId
            when {
                groupId != null -> {
                    GroupRow(
                        ranked,
                        groupId,
                        open = groupId == state.openGroupId,
                        locale,
                        onGroupTapped,
                        onOpenTransactions,
                    )
                }

                categoryId != null -> {
                    LeadingRow(
                        name = ranked.name,
                        amount = ranked.total,
                        share = ranked.share,
                        locale = locale,
                        onClickLabel = stringResource(R.string.reports_see_transactions, ranked.name),
                        onClick = { onOpenTransactions(SpendingOf.OneCategory(categoryId)) },
                    )
                }
            }
        }
        if (state.uncategorised.minor != 0L) {
            Uncategorised(state.uncategorised, locale) { onOpenTransactions(SpendingOf.NoCategory) }
        }
    }
}

@Composable
private fun GroupRow(
    group: RankedSpending,
    groupId: Long,
    open: Boolean,
    locale: Locale,
    onGroupTapped: (Long) -> Unit,
    onOpenTransactions: (SpendingOf) -> Unit,
) {
    LeadingRow(
        name = group.name,
        amount = group.total,
        share = group.share,
        locale = locale,
        onClickLabel =
            stringResource(
                if (open) R.string.reports_hide_categories else R.string.reports_show_categories,
                group.name,
            ),
        onClick = { onGroupTapped(groupId) },
        opens = open,
    )
    if (!open) return

    Column(Modifier.padding(start = 16.dp)) {
        group.categories.forEach { category ->
            CategoryRow(category, locale) { onOpenTransactions(SpendingOf.OneCategory(category.categoryId)) }
        }
        Text(
            stringResource(R.string.reports_see_all_transactions, group.name),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { onOpenTransactions(SpendingOf.OneGroup(groupId)) }
                    .heightIn(min = 48.dp)
                    .padding(vertical = 12.dp),
        )
    }
}

/**
 * A row at the head of the ranking. [opens] is null for a category, which leads
 * to its transactions, and says whether a group is open otherwise.
 */
@Composable
private fun LeadingRow(
    name: String,
    amount: Money,
    share: Float,
    locale: Locale,
    onClickLabel: String,
    onClick: () -> Unit,
    opens: Boolean? = null,
) {
    Column(
        Modifier
            .clickable(onClickLabel = onClickLabel, role = Role.Button, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (opens != null) {
                    Icon(
                        if (opens) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                Formatters.money(amount, locale),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 8.dp),
            )
            if (opens == null) LeadsToTransactions()
        }
        ShareBar(share)
    }
}

@Composable
private fun CategoryRow(
    category: CategoryShare,
    locale: Locale,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .clickable(
                onClickLabel = stringResource(R.string.reports_see_transactions, category.name),
                role = Role.Button,
                onClick = onClick,
            ).padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
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
            LeadsToTransactions()
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
private fun Uncategorised(
    amount: Money,
    locale: Locale,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .clickable(
                onClickLabel = stringResource(R.string.reports_see_uncategorised),
                role = Role.Button,
                onClick = onClick,
            ).heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.reports_no_category, Formatters.money(amount, locale)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LeadsToTransactions()
    }
}

@Composable
private fun LeadsToTransactions() {
    Icon(
        Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ShareBar(share: Float) {
    LinearProgressIndicator(
        progress = { share },
        modifier = Modifier.fillMaxWidth(),
        drawStopIndicator = {},
    )
}
