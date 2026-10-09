// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.feature.reports

import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.jaytech64.piastre.core.designsystem.Formatters
import io.github.jaytech64.piastre.core.model.Money
import io.github.jaytech64.piastre.core.model.ReportPeriod
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

    val level = heatLevel(spent?.minor ?: 0L, heaviest)

    // A day that has not happened is not a day that cost nothing, so an empty
    // future day carries no cell colour at all rather than the colour of zero.
    // A future day that already has spending on it — a manual entry dated ahead,
    // which the ledger allows — is drawn like any other, because the total above
    // has counted it and a blank cell under a total that includes it would be
    // the grid contradicting the headline.
    val blank = isFuture && level == 0
    val background =
        if (blank) Color.Transparent else MaterialTheme.colorScheme.primary.copy(alpha = HEAT_ALPHAS[level])

    Box(
        cell
            // The period is marked by dimming everything outside it rather than by
            // ringing what is inside it. A ring has to contrast with the cell it
            // sits on, and the heaviest cells are filled with the very colour a
            // ring would be drawn in — while a pale ring at a cell's edge is
            // indistinguishable from the gap between two cells. Dimming reads on
            // every step of the ramp and in both themes, and says the true thing:
            // these are the days the numbers above are about.
            .alpha(if (isSelected) 1f else OUTSIDE_THE_PERIOD)
            .background(background)
            .clickable { onDaySelected(date) }
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
                if (blank) MaterialTheme.colorScheme.outlineVariant else MaterialTheme.colorScheme.onSurface,
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
        HEAT_ALPHAS.forEach { alpha ->
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
private fun heatLevel(
    spent: Long,
    heaviest: Long,
): Int {
    if (spent <= 0L || heaviest <= 0L) return 0
    return ((spent * HEAT_LEVELS + heaviest - 1) / heaviest).coerceIn(1L, HEAT_LEVELS).toInt()
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

/** The empty step, then three wide enough apart to be told apart on a phone in daylight. */
private val HEAT_ALPHAS = listOf(0.08f, 0.25f, 0.55f, 0.90f)

/** Faint enough to recede, strong enough that the month's shape is still readable behind the period. */
private const val OUTSIDE_THE_PERIOD = 0.35f

private val CELL_GAP = 4.dp
private val CELL_CORNER = 6.dp
private val LEGEND_SWATCH = 12.dp
private val LEGEND_CORNER = 3.dp
