// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.feature.reports

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.jaytech64.depenses.core.designsystem.Formatters
import io.github.jaytech64.depenses.core.model.Money
import io.github.jaytech64.depenses.core.model.ReportPeriod
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/**
 * The month the period sits in, each day shaded by what it cost.
 *
 * Tapping a day narrows the whole screen to it, which is what stands in for a
 * date picker: every day of every month is one tap from here, and the shading is
 * what tells you which one is worth tapping.
 */
@Composable
internal fun MonthHeatGrid(
    month: YearMonth,
    totals: Map<LocalDate, Money>,
    selected: ReportPeriod,
    today: LocalDate?,
    locale: Locale,
    onDaySelected: (LocalDate) -> Unit,
) {
    val heaviest = totals.values.maxOfOrNull { it.minor } ?: 0L

    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            Formatters.monthYear(month, locale),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )

        WeekdayHeadings(locale)

        month.weeks().forEach { week ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(CELL_GAP)) {
                week.forEach { date ->
                    DayCell(
                        date = date,
                        spent = date?.let(totals::get),
                        heaviest = heaviest,
                        isSelected = date != null && date in selected,
                        isFuture = date != null && today != null && date > today,
                        locale = locale,
                        onDaySelected = onDaySelected,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        HeatLegend()
    }
}

@Composable
private fun WeekdayHeadings(locale: Locale) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(CELL_GAP)) {
        ReportPeriod.WEEK_STARTS_ON.weekFromHere().forEach { day ->
            Text(
                day.getDisplayName(TextStyle.NARROW_STANDALONE, locale),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun DayCell(
    date: LocalDate?,
    spent: Money?,
    heaviest: Long,
    isSelected: Boolean,
    isFuture: Boolean,
    locale: Locale,
    onDaySelected: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cell =
        modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(CELL_CORNER))

    if (date == null) {
        Box(cell)
        return
    }

    // A day that has not happened is not a day that cost nothing, so it carries
    // no cell colour at all rather than the colour of zero.
    val background =
        when {
            isFuture -> Color.Transparent
            else -> MaterialTheme.colorScheme.primary.copy(alpha = heatAlpha(spent?.minor ?: 0L, heaviest))
        }

    val outlined =
        if (isSelected) {
            cell.border(SELECTED_OUTLINE, MaterialTheme.colorScheme.primary, RoundedCornerShape(CELL_CORNER))
        } else {
            cell
        }

    Box(
        outlined
            .background(background)
            .clickable(enabled = !isFuture) { onDaySelected(date) }
            .semantics {
                contentDescription =
                    date.dayOfMonth.toString() + (spent?.let { ", " + Formatters.money(it, locale) } ?: "")
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            date.dayOfMonth.toString(),
            style = MaterialTheme.typography.labelSmall,
            color =
                if (isFuture) MaterialTheme.colorScheme.outlineVariant else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun HeatLegend() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.reports_heat_less),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        (listOf(HEAT_EMPTY) + HEAT_ALPHAS).forEach { alpha ->
            Box(
                Modifier
                    .size(LEGEND_SWATCH)
                    .clip(RoundedCornerShape(LEGEND_CORNER))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha)),
            )
        }
        Text(
            stringResource(R.string.reports_heat_more),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Three levels of shading plus an empty one, scaled to the month's own heaviest
 * day.
 *
 * Self-scaling is what keeps a frugal month legible and a heavy one from
 * saturating. The cost is that the colour is not comparable between months — the
 * same forty-dollar Tuesday is the darkest cell in July and the palest in
 * December — which is why the legend states a direction and never a number.
 * Fixed dollar thresholds would be comparable and would need a number nobody can
 * pick for someone else.
 */
private fun heatAlpha(
    spent: Long,
    heaviest: Long,
): Float {
    if (spent <= 0L || heaviest <= 0L) return HEAT_EMPTY
    val level = ((spent * HEAT_LEVELS + heaviest - 1) / heaviest).coerceIn(1L, HEAT_LEVELS)
    return HEAT_ALPHAS[level.toInt() - 1]
}

/**
 * The month laid out as rows of seven, starting on [ReportPeriod.WEEK_STARTS_ON].
 *
 * Days outside the month are null rather than the neighbouring month's dates: a
 * report of September has nothing to say about the 31st of August, and a cell
 * that could be tapped into another month would contradict the heading above it.
 */
private fun YearMonth.weeks(): List<List<LocalDate?>> {
    val first = atDay(1)
    val blanks = Math.floorMod(first.dayOfWeek.value - ReportPeriod.WEEK_STARTS_ON.value, DAYS_IN_WEEK)
    val cells = List(blanks) { null } + (1..lengthOfMonth()).map { atDay(it) }
    return cells.chunked(DAYS_IN_WEEK).map { week -> week + List(DAYS_IN_WEEK - week.size) { null } }
}

private fun DayOfWeek.weekFromHere(): List<DayOfWeek> = List(DAYS_IN_WEEK) { plus(it.toLong()) }

private const val DAYS_IN_WEEK = 7

private const val HEAT_LEVELS = 3L

private const val HEAT_EMPTY = 0.08f

/** Three steps, wide enough apart to be told apart on a phone in daylight. */
private val HEAT_ALPHAS = listOf(0.25f, 0.55f, 0.90f)

private val CELL_GAP = 4.dp
private val CELL_CORNER = 6.dp
private val SELECTED_OUTLINE = 1.5.dp
private val LEGEND_SWATCH = 12.dp
private val LEGEND_CORNER = 3.dp
