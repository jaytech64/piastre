// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.feature.reports

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jaytech64.piastre.core.model.PeriodUnit
import io.github.jaytech64.piastre.core.model.ReportPeriod
import java.time.YearMonth
import java.util.Locale

/**
 * One scroll: the period, what its balance is made of, the ways into budgets,
 * trends and merchants, where the spending went, and the month it sits in. A
 * category, a group or what has no category opens its transactions through
 * [onOpenTransactions], which is all this screen knows of where they are shown.
 *
 * The calendar is last rather than on a screen of its own because it is a
 * breakdown of what the header says was spent — and because it doubles as the day picker,
 * which is why nothing here needs a date-range control.
 */
@Composable
fun ReportsScreen(
    onOpenTransactions: (SpendingOf, ReportPeriod) -> Unit,
    onOpenBudgets: (YearMonth) -> Unit,
    onOpenTrends: (groupId: Long?) -> Unit,
    onOpenMerchants: (YearMonth) -> Unit,
    onOpenPremium: () -> Unit,
    viewModel: ReportsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val locale = currentLocale()

    // Which period you are on is a function of today, so nothing here means
    // anything until the clock has been read. A frame of nothing beats a frame
    // labelled with the first of January 1970.
    if (state.today == null) return

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PeriodHeader(
            state = state,
            locale = locale,
            onUnitSelected = viewModel::onUnitSelected,
            onStepBack = viewModel::onStepBack,
            onStepForward = viewModel::onStepForward,
        )

        if (state.period.unit == PeriodUnit.MONTH) {
            BudgetsRow(state) { if (state.premiumOn) onOpenBudgets(state.period.month) else onOpenPremium() }
        }
        TrendsRow(state.premiumOn) { if (state.premiumOn) onOpenTrends(null) else onOpenPremium() }
        MerchantsRow(state.premiumOn) {
            if (state.premiumOn) onOpenMerchants(state.period.month) else onOpenPremium()
        }

        CategoryRanking(
            state = state,
            locale = locale,
            onGroupTapped = viewModel::onGroupTapped,
            onOpenTransactions = { of -> onOpenTransactions(of, state.period) },
            onOpenTrends = { groupId -> if (state.premiumOn) onOpenTrends(groupId) else onOpenPremium() },
        )

        MonthHeatGrid(
            month = state.period.month,
            totals = state.monthTotals,
            selected = state.period,
            today = state.today,
            locale = locale,
            onDaySelected = viewModel::onDaySelected,
        )

        Footnotes(state.otherCurrencyCount, state.needsReviewCount)
    }
}

/**
 * The way into trends, under the period for every unit: a year of months does
 * not depend on which day, week or month the report is on.
 */
@Composable
internal fun TrendsRow(
    premiumOn: Boolean,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(stringResource(R.string.trends_title), style = MaterialTheme.typography.bodyLarge)
        Text(
            stringResource(if (premiumOn) R.string.trends_row else R.string.trends_row_premium),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The way into the merchant report, which opens on the month the report is on
 * whatever its unit, since a merchant's average means little over a day.
 */
@Composable
internal fun MerchantsRow(
    premiumOn: Boolean,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(stringResource(R.string.merchants_title), style = MaterialTheme.typography.bodyLarge)
        Text(
            stringResource(if (premiumOn) R.string.merchants_row else R.string.merchants_row_premium),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * What the numbers above leave out, on the same screen as the numbers.
 *
 * Two things can make a total understate: a transaction in a currency no query
 * here counts, and a purchase still waiting for a human. ADR-0002 settled that a total
 * which omits rows has to say so beside itself, and this is that line. Neither
 * sentence is drawn when its count is zero, which is nearly always.
 */
@Composable
internal fun Footnotes(
    otherCurrencyCount: Int,
    needsReviewCount: Int,
) {
    if (otherCurrencyCount == 0 && needsReviewCount == 0) return

    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (otherCurrencyCount > 0) {
            Footnote(pluralStringResource(R.plurals.reports_other_currency, otherCurrencyCount, otherCurrencyCount))
        }
        if (needsReviewCount > 0) {
            Footnote(pluralStringResource(R.plurals.reports_awaiting_review, needsReviewCount, needsReviewCount))
        }
    }
}

@Composable
private fun Footnote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Compose's configuration rather than [Locale.getDefault]: the two differ the
 * moment the user sets a per-app language, and the formatter must follow what is
 * actually on screen.
 */
@Composable
internal fun currentLocale(): Locale = LocalConfiguration.current.locales[0]
