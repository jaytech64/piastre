// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.feature.reports

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.jaytech64.piastre.core.designsystem.ButtonsRow
import io.github.jaytech64.piastre.core.designsystem.Formatters
import java.time.YearMonth
import java.util.Locale

/**
 * Twelve bars, one a month, drawn to one scale with the year before, the
 * budget and the average, so a bar taller than its neighbour is more money and
 * nothing else.
 *
 * The bars are drawn on a canvas and read through a row of boxes laid over
 * them, one a month: each is what a tap selects and what TalkBack says, so the
 * chart is never only a picture. The months before the ledger began are one
 * box saying so, and nothing to tap.
 */
@Composable
internal fun TrendChart(
    year: TrendYear,
    selected: YearMonth,
    locale: Locale,
    onSelect: (YearMonth) -> Unit,
) {
    val colors =
        ChartColors(
            bar = MaterialTheme.colorScheme.primary,
            selected = MaterialTheme.colorScheme.tertiary,
            yearBefore = MaterialTheme.colorScheme.primary.copy(alpha = YEAR_BEFORE_ALPHA),
            budget = MaterialTheme.colorScheme.onSurface,
            average = MaterialTheme.colorScheme.onSurfaceVariant,
            baseline = MaterialTheme.colorScheme.outlineVariant,
        )
    val months = year.months
    val notYet = months.count { it.spent == null }

    Column(Modifier.padding(horizontal = 16.dp)) {
        Box(Modifier.fillMaxWidth().height(CHART_HEIGHT)) {
            Canvas(Modifier.matchParentSize()) { drawYear(year, selected, colors) }
            Row(Modifier.matchParentSize()) {
                if (notYet > 0) NotYet(notYet)
                months.drop(notYet).forEach { month ->
                    MonthBox(month, month.month == selected, locale, onSelect)
                }
            }
        }
        Row(Modifier.fillMaxWidth().clearAndSetSemantics {}) {
            months.forEach { month ->
                Text(
                    Formatters.monthInitial(month.month, locale),
                    style = MaterialTheme.typography.labelSmall,
                    color =
                        if (month.month ==
                            selected
                        ) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** What each mark means, said under the chart; a mark that draws nothing is not named. */
@Composable
internal fun TrendLegend(year: TrendYear) {
    ButtonsRow(Modifier.padding(horizontal = 16.dp), gap = 16.dp) {
        if (year.months.any { (it.yearBefore?.minor ?: 0L) > 0L }) {
            LegendItem(stringResource(R.string.trends_legend_year_before)) { color ->
                drawRect(color.copy(alpha = YEAR_BEFORE_ALPHA))
            }
        }
        if (year.months.any { it.running }) {
            LegendItem(stringResource(R.string.trends_so_far)) { color ->
                drawRect(color, style = Stroke(width = 1.5.dp.toPx(), pathEffect = dashes()))
            }
        }
        if (year.months.any { it.budget != null }) {
            LegendItem(stringResource(R.string.trends_legend_budget)) { color ->
                drawLine(color, Offset(0f, center.y), Offset(size.width, center.y), strokeWidth = 2.dp.toPx())
            }
        }
        if (year.average != null) {
            LegendItem(stringResource(R.string.trends_legend_average)) { color ->
                drawLine(color, Offset(0f, center.y), Offset(size.width, center.y), 1.dp.toPx(), pathEffect = dashes())
            }
        }
    }
}

@Composable
private fun LegendItem(
    label: String,
    swatch: DrawScope.(Color) -> Unit,
) {
    val color = MaterialTheme.colorScheme.primary
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Canvas(Modifier.size(SWATCH_WIDTH, SWATCH_HEIGHT)) { swatch(color) }
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.NotYet(months: Int) {
    val label = stringResource(R.string.trends_not_yet)
    Box(
        Modifier
            .weight(months.toFloat())
            .fillMaxHeight()
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        if (months >= NOT_YET_LABELLED_FROM) {
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.MonthBox(
    month: TrendMonth,
    isSelected: Boolean,
    locale: Locale,
    onSelect: (YearMonth) -> Unit,
) {
    val name = monthName(month, locale)
    val spoken =
        month.spent?.let { stringResource(R.string.trends_bar, name, Formatters.money(it, locale)) }
            ?: stringResource(R.string.trends_bar_not_yet, name)
    Box(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .clickable(
                onClickLabel = stringResource(R.string.trends_select_month, Formatters.monthYear(month.month, locale)),
                role = Role.Button,
            ) { onSelect(month.month) }
            .semantics {
                contentDescription = spoken
                selected = isSelected
            },
    )
}

/** A month named in full, saying so when it is still running. */
@Composable
internal fun monthName(
    month: TrendMonth,
    locale: Locale,
): String {
    val name = Formatters.monthYear(month.month, locale)
    return if (month.running) stringResource(R.string.trends_month_so_far, name) else name
}

private data class ChartColors(
    val bar: Color,
    val selected: Color,
    val yearBefore: Color,
    val budget: Color,
    val average: Color,
    val baseline: Color,
)

/**
 * Each month's slot holds a thin bar for the year before and a wide one for
 * the month itself, side by side, with the budget a tick across both. The
 * scale tops out a little above the highest mark so a budget tick at the top
 * is never drawn on the edge.
 */
private fun DrawScope.drawYear(
    year: TrendYear,
    selected: YearMonth,
    colors: ChartColors,
) {
    val months = year.months
    val highest =
        months
            .flatMap { listOfNotNull(it.spent?.minor, it.yearBefore?.minor, it.budget?.minor) }
            .plus(listOfNotNull(year.average?.minor))
            .maxOrNull()
            ?.coerceAtLeast(1L) ?: 1L
    val slot = size.width / months.size
    val baseline = size.height

    fun heightOf(minor: Long) = baseline - minor.toFloat() / highest * size.height * HEADROOM

    drawLine(colors.baseline, Offset(0f, baseline), Offset(size.width, baseline), strokeWidth = 1.dp.toPx())
    months.forEachIndexed { index, month ->
        val left = index * slot
        month.yearBefore?.let { before ->
            val top = heightOf(before.minor)
            drawRect(
                colors.yearBefore,
                Offset(left + slot * YEAR_BEFORE_OFFSET, top),
                Size(slot * YEAR_BEFORE_WIDTH, baseline - top),
            )
        }
        month.spent?.let { spent ->
            val top = heightOf(spent.minor)
            val color = if (month.month == selected) colors.selected else colors.bar
            val at = Offset(left + slot * BAR_OFFSET, top)
            val barSize = Size(slot * BAR_WIDTH, baseline - top)
            if (month.running) {
                drawRect(color, at, barSize, style = Stroke(width = 1.5.dp.toPx(), pathEffect = dashes()))
            } else {
                drawRect(color, at, barSize)
            }
        }
        month.budget?.let { budget ->
            val at = heightOf(budget.minor)
            drawLine(
                colors.budget,
                Offset(left + slot * TICK_INSET, at),
                Offset(left + slot * (1 - TICK_INSET), at),
                strokeWidth = 2.dp.toPx(),
            )
        }
    }
    year.average?.let { average ->
        val at = heightOf(average.minor)
        drawLine(colors.average, Offset(0f, at), Offset(size.width, at), 1.dp.toPx(), pathEffect = dashes())
    }
}

private fun DrawScope.dashes() = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))

private val CHART_HEIGHT = 160.dp
private val SWATCH_WIDTH = 14.dp
private val SWATCH_HEIGHT = 10.dp
private const val YEAR_BEFORE_ALPHA = 0.35f
private const val HEADROOM = 0.92f
private const val YEAR_BEFORE_OFFSET = 0.12f
private const val YEAR_BEFORE_WIDTH = 0.16f
private const val BAR_OFFSET = 0.32f
private const val BAR_WIDTH = 0.52f
private const val TICK_INSET = 0.06f

/** Fewer months than this are too narrow for the words, and say them to TalkBack alone. */
private const val NOT_YET_LABELLED_FROM = 3
