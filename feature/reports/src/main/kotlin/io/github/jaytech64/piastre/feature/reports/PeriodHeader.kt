// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.feature.reports

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
import androidx.compose.material3.HorizontalDivider
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
import io.github.jaytech64.piastre.core.designsystem.BalanceFigure
import io.github.jaytech64.piastre.core.designsystem.BalanceFigures
import io.github.jaytech64.piastre.core.designsystem.ChoiceRow
import io.github.jaytech64.piastre.core.designsystem.Formatters
import io.github.jaytech64.piastre.core.model.PeriodUnit
import io.github.jaytech64.piastre.core.model.ReportPeriod
import java.time.LocalDate
import java.util.Locale

/** The period picker, the period you are on, and what its balance is made of. */
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
        BalanceCard(state, locale)
    }
}

@Composable
private fun UnitPicker(
    selected: PeriodUnit,
    onUnitSelected: (PeriodUnit) -> Unit,
) {
    ChoiceRow(
        options = PeriodUnit.entries,
        selected = selected,
        onSelect = onUnitSelected,
        label = { stringResource(it.labelRes) },
    )
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

/**
 * The ledger's header, for the period: the balance, then what came in and what
 * was spent. The count and the comparison sit under what was spent, which is the
 * figure the ranking and the calendar below break down (ADR-0009).
 */
@Composable
private fun BalanceCard(
    state: ReportsUiState,
    locale: Locale,
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.reports_balance),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(Formatters.signedMoney(state.balance, locale), style = MaterialTheme.typography.displaySmall)

            if (state.isEmpty) {
                Text(
                    stringResource(R.string.reports_nothing),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
            BalanceFigures(
                listOf(
                    BalanceFigure(
                        label = stringResource(R.string.reports_income),
                        amount = Formatters.money(state.income, locale),
                        supporting =
                            listOfNotNull(
                                state.incomeComparison?.let { comparison ->
                                    { ComparisonLine(comparison, state.period.unit, judged = false) }
                                },
                            ),
                    ),
                    BalanceFigure(
                        label = stringResource(R.string.reports_expenses),
                        amount = Formatters.money(state.total, locale),
                        supporting =
                            listOfNotNull(
                                { SpendingCount(state.spendingCount) },
                                state.comparison?.let { comparison ->
                                    { ComparisonLine(comparison, state.period.unit, judged = true) }
                                },
                            ),
                    ),
                ),
            )
        }
    }
}

@Composable
private fun SpendingCount(count: Int) {
    Text(
        if (count == 0) {
            stringResource(R.string.reports_nothing_spent)
        } else {
            pluralStringResource(R.plurals.reports_transactions, count, count)
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * When [judged], spending less than last time is the good direction, so it takes
 * the colour the theme reserves for good news and an upward swing takes the
 * warning one. Income is not judged: a month with a third paycheque reads ▲ 50 %
 * with no raise behind it, and a colour for good news beside one for bad would
 * make the same arrow mean two things on one card.
 */
@Composable
private fun ComparisonLine(
    comparison: Comparison,
    unit: PeriodUnit,
    judged: Boolean,
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
                    when {
                        !judged -> MaterialTheme.colorScheme.onSurfaceVariant
                        comparison.percent >= 0 -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.tertiary
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
