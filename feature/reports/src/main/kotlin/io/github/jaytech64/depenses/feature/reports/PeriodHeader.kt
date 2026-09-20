// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.feature.reports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.jaytech64.depenses.core.designsystem.Formatters
import io.github.jaytech64.depenses.core.model.PeriodUnit
import io.github.jaytech64.depenses.core.model.ReportPeriod
import java.time.LocalDate
import java.util.Locale

/** The period picker, the period you are on, and what it cost. */
@Composable
internal fun PeriodHeader(
    state: ReportsUiState,
    locale: Locale,
    onUnitSelected: (PeriodUnit) -> Unit,
    onStepBack: () -> Unit,
    onStepForward: () -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        UnitPicker(state.period.unit, onUnitSelected)
        PeriodStepper(state, locale, onStepBack, onStepForward)
        SpendingCard(state)
    }
}

@Composable
private fun UnitPicker(
    selected: PeriodUnit,
    onUnitSelected: (PeriodUnit) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        PeriodUnit.entries.forEachIndexed { index, unit ->
            SegmentedButton(
                selected = unit == selected,
                onClick = { onUnitSelected(unit) },
                shape = SegmentedButtonDefaults.itemShape(index, PeriodUnit.entries.size),
            ) {
                Text(stringResource(unit.labelRes))
            }
        }
    }
}

@Composable
private fun PeriodStepper(
    state: ReportsUiState,
    locale: Locale,
    onStepBack: () -> Unit,
    onStepForward: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onStepBack) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = stringResource(R.string.reports_previous),
            )
        }
        Text(
            state.period.label(state.today, locale),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onStepForward, enabled = state.canStepForward) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = stringResource(R.string.reports_next),
            )
        }
    }
}

@Composable
private fun SpendingCard(state: ReportsUiState) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(Formatters.money(state.total), style = MaterialTheme.typography.headlineMedium)

            if (state.isEmpty) {
                Text(
                    stringResource(R.string.reports_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            // "dépensé" is load-bearing: the ledger's balance for the same month
            // counts income too, and the two numbers differ by every paycheque.
            Text(
                stringResource(R.string.reports_spent) + " · " +
                    pluralStringResource(R.plurals.reports_purchases, state.purchaseCount, state.purchaseCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ComparisonLine(state.comparison, state.period.unit)
        }
    }
}

/**
 * Spending less than last time is the good direction, so it takes the colour the
 * theme reserves for good news and an upward swing takes the warning one.
 */
@Composable
private fun ComparisonLine(
    comparison: Comparison,
    unit: PeriodUnit,
) {
    when (comparison) {
        Comparison.NothingEither -> {
            Unit
        }

        Comparison.NothingBefore -> {
            Text(
                stringResource(unit.nothingBeforeRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        is Comparison.Change -> {
            Text(
                (if (comparison.percent >= 0) "▲ " else "▼ ") +
                    stringResource(
                        R.string.reports_change,
                        kotlin.math.abs(comparison.percent),
                        stringResource(unit.versusRes),
                    ),
                style = MaterialTheme.typography.bodySmall,
                color =
                    if (comparison.percent >= 0) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.tertiary
                    },
            )
        }
    }
}

/**
 * A day names itself the way the ledger's day headings do, a week states both of
 * its ends, and a month carries its year.
 *
 * The week label repeats the month on both ends — "28 septembre – 4 octobre" is
 * the case the short form gets wrong, and that is twelve weeks a year.
 */
@Composable
private fun ReportPeriod.label(
    today: LocalDate?,
    locale: Locale,
): String =
    when (unit) {
        PeriodUnit.DAY -> {
            Formatters.dayHeader(
                date = start,
                today = today ?: start,
                labels =
                    Formatters.RelativeDayLabels(
                        today = stringResource(R.string.reports_today),
                        yesterday = stringResource(R.string.reports_yesterday),
                    ),
                locale = locale,
            )
        }

        PeriodUnit.WEEK -> {
            stringResource(
                R.string.reports_week_range,
                Formatters.dayAndMonth(start, locale),
                Formatters.dayAndMonth(endInclusive, locale),
            )
        }

        PeriodUnit.MONTH -> {
            Formatters.monthYear(month, locale)
        }
    }

private val PeriodUnit.labelRes: Int
    get() =
        when (this) {
            PeriodUnit.DAY -> R.string.reports_period_day
            PeriodUnit.WEEK -> R.string.reports_period_week
            PeriodUnit.MONTH -> R.string.reports_period_month
        }

private val PeriodUnit.versusRes: Int
    get() =
        when (this) {
            PeriodUnit.DAY -> R.string.reports_vs_day
            PeriodUnit.WEEK -> R.string.reports_vs_week
            PeriodUnit.MONTH -> R.string.reports_vs_month
        }

private val PeriodUnit.nothingBeforeRes: Int
    get() =
        when (this) {
            PeriodUnit.DAY -> R.string.reports_vs_nothing_day
            PeriodUnit.WEEK -> R.string.reports_vs_nothing_week
            PeriodUnit.MONTH -> R.string.reports_vs_nothing_month
        }
