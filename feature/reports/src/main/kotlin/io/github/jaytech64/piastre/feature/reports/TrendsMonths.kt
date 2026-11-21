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

/** Every month of the window, newest first, so that nothing on the chart is only a bar. */
@Composable
internal fun MonthByMonth(
    year: TrendYear,
    selected: YearMonth,
    locale: Locale,
    onSelect: (YearMonth) -> Unit,
) {
    Column(Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
        Text(
            stringResource(R.string.trends_month_by_month),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        year.months.asReversed().filter { it.spent != null }.forEach { month ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .then(
                        if (month.month == selected) {
                            Modifier.background(
                                MaterialTheme.colorScheme.surfaceContainerHigh,
                                MaterialTheme.shapes.small,
                            )
                        } else {
                            Modifier
                        },
                    ).clickable(
                        onClickLabel =
                            stringResource(
                                R.string.trends_select_month,
                                Formatters.monthYear(month.month, locale),
                            ),
                        role = Role.Button,
                    ) { onSelect(month.month) }
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                MonthFigures(month, locale)
            }
        }
    }
}

/** The month's name and figure, then the year before and the budget beside it. */
@Composable
internal fun MonthFigures(
    month: TrendMonth,
    locale: Locale,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(monthName(month, locale), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(
            month.spent?.let { Formatters.money(it, locale) } ?: stringResource(R.string.trends_not_yet),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
    val spent = month.spent ?: return
    if (spent.minor != 0L) {
        Supporting(pluralStringResource(R.plurals.reports_transactions, month.count, month.count))
    }
    month.comparison?.let { comparison ->
        YearBeforeLine(comparison, Formatters.monthYear(month.month.minusYears(1), locale))
    }
    month.budget?.let { Supporting(stringResource(R.string.trends_budget, Formatters.money(it, locale))) }
}

/**
 * Spending less than a year before is the good direction, as it is under a
 * report's *Dépenses*, so it takes the colour the theme keeps for good news.
 */
@Composable
private fun YearBeforeLine(
    comparison: Comparison,
    yearBefore: String,
) {
    val line =
        comparison.movement()?.let {
            stringResource(R.string.reports_change, it, stringResource(R.string.trends_versus, yearBefore))
        } ?: stringResource(R.string.trends_nothing_in, yearBefore).takeIf { comparison == Comparison.NothingBefore }
            ?: return
    Text(line, style = MaterialTheme.typography.bodySmall, color = comparison.judgedColor())
}
