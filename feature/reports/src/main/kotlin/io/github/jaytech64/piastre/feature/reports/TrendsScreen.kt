// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.feature.reports

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jaytech64.piastre.core.data.SpentOn
import io.github.jaytech64.piastre.core.designsystem.Formatters
import io.github.jaytech64.piastre.core.model.Category
import io.github.jaytech64.piastre.core.model.CategoryGroup
import io.github.jaytech64.piastre.core.model.PeriodUnit
import io.github.jaytech64.piastre.core.model.ReportPeriod
import java.text.Collator
import java.time.YearMonth
import java.util.Locale

/**
 * Spending over time, in two tabs: a year of one thing month by month, and
 * two spans of days side by side. A month opens its transactions through
 * [onOpenTransactions], as a report's ranking does.
 *
 * Without Premium the page says so and shows nothing; Reports leads to the
 * Premium page instead of here.
 */
@Composable
fun TrendsScreen(
    onOpenTransactions: (SpendingOf, ReportPeriod) -> Unit,
    viewModel: TrendsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val locale = currentLocale()
    if (state.today == null) return

    Column(Modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = state.tab.ordinal) {
            TrendsTab.entries.forEach { tab ->
                Tab(
                    selected = tab == state.tab,
                    onClick = { viewModel.onTabSelected(tab) },
                    text = { Text(stringResource(tab.labelRes)) },
                )
            }
        }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!state.premiumOn) {
                Quiet(stringResource(R.string.trends_premium_off))
                return@Column
            }
            when (state.tab) {
                TrendsTab.YEAR -> YearTab(state, locale, viewModel, onOpenTransactions)
                TrendsTab.COMPARE -> CompareTab(state, locale, viewModel)
            }
        }
    }
}

@Composable
private fun YearTab(
    state: TrendsUiState,
    locale: Locale,
    viewModel: TrendsViewModel,
    onOpenTransactions: (SpendingOf, ReportPeriod) -> Unit,
) {
    val year = state.year ?: return
    val selected = state.selectedMonth ?: return

    SpentOnPicker(state, locale, viewModel::onSpentOnSelected)
    YearStepper(year, locale, viewModel::onStepBack, viewModel::onStepForward)
    Average(year, locale)
    TrendChart(year, selected.month, locale, viewModel::onMonthSelected)
    TrendLegend(year)
    SelectedMonth(selected, locale, state.on.asSpendingOf(), onOpenTransactions)
    MonthByMonth(year, selected.month, locale, viewModel::onMonthSelected)
    Footnotes(year.otherCurrencyCount, state.needsReviewCount)
}

@Composable
private fun YearStepper(
    year: TrendYear,
    locale: Locale,
    onStepBack: () -> Unit,
    onStepForward: () -> Unit,
) {
    Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onStepBack, enabled = year.canStepBack) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = stringResource(R.string.trends_previous_year),
            )
        }
        Text(
            stringResource(
                R.string.reports_week_range,
                Formatters.shortMonthYear(year.months.first().month, locale),
                Formatters.shortMonthYear(year.end, locale),
            ),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onStepForward, enabled = year.canStepForward) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = stringResource(R.string.trends_next_year),
            )
        }
    }
}

@Composable
private fun Average(
    year: TrendYear,
    locale: Locale,
) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        Text(
            stringResource(R.string.trends_average),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val average = year.average
        if (average == null) {
            Text(
                stringResource(R.string.trends_no_complete_month),
                style = MaterialTheme.typography.titleMedium,
            )
        } else {
            Text(Formatters.money(average, locale), style = MaterialTheme.typography.displaySmall)
            Text(
                pluralStringResource(R.plurals.trends_average_over, year.completeMonths, year.completeMonths),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The month a bar was tapped on, written out, with the way into its transactions. */
@Composable
private fun SelectedMonth(
    month: TrendMonth,
    locale: Locale,
    of: SpendingOf?,
    onOpenTransactions: (SpendingOf, ReportPeriod) -> Unit,
) {
    Card(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            MonthFigures(month, locale)
            if (of != null && month.spent != null) {
                Text(
                    stringResource(R.string.trends_see_transactions),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Button) {
                                onOpenTransactions(of, ReportPeriod(PeriodUnit.MONTH, month.month.atDay(1)))
                            }.heightIn(min = 48.dp)
                            .padding(vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
internal fun changeColor(percent: Int) =
    if (percent >= 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary

@Composable
internal fun Supporting(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Quiet(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}

/** What a month's transactions can be opened on; all spending has no single filter that shows it. */
private fun SpentOn.asSpendingOf(): SpendingOf? =
    when (this) {
        SpentOn.Everything -> null
        is SpentOn.OneCategory -> SpendingOf.OneCategory(categoryId)
        is SpentOn.OneGroup -> SpendingOf.OneGroup(groupId)
        SpentOn.NoCategory -> SpendingOf.NoCategory
    }

private val TrendsTab.labelRes: Int
    get() =
        when (this) {
            TrendsTab.YEAR -> R.string.trends_tab_year
            TrendsTab.COMPARE -> R.string.trends_tab_compare
        }
