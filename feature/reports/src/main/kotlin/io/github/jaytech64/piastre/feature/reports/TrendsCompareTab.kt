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
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.jaytech64.piastre.core.designsystem.ButtonsRow
import io.github.jaytech64.piastre.core.designsystem.Formatters
import io.github.jaytech64.piastre.core.model.Days
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

/**
 * Two spans of days side by side, broken down the way the ranking breaks down
 * one period. It opens on this month so far against the same days of last
 * month, and either span can be any run of days up to today.
 */
@Composable
internal fun CompareTab(
    state: TrendsUiState,
    locale: Locale,
    viewModel: TrendsViewModel,
) {
    val compare = state.compare ?: return
    val today = state.today ?: return
    var picking by remember { mutableStateOf<SpanEnd?>(null) }

    ButtonsRow(Modifier.padding(horizontal = 16.dp)) {
        OutlinedButton(onClick = { viewModel.onShortcut(CompareShortcut.LAST_MONTH) }) {
            Text(stringResource(R.string.trends_this_vs_last_month))
        }
        OutlinedButton(onClick = { viewModel.onShortcut(CompareShortcut.A_YEAR_EARLIER) }) {
            Text(stringResource(R.string.trends_this_vs_a_year_earlier))
        }
    }
    SpanField(R.string.trends_first_span, compare.first, compare.firstDays, today, locale) { picking = SpanEnd.FIRST }
    SpanField(
        R.string.trends_second_span,
        compare.second,
        compare.secondDays,
        today,
        locale,
    ) { picking = SpanEnd.SECOND }

    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        ComparedRow(compare.everything, locale, emphasised = true)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        compare.rows.forEach { row ->
            val groupId = row.groupId
            if (groupId != null && row.categoryId == null) {
                val open = groupId == state.openGroupId
                ComparedRow(row, locale, opens = open, onClick = { viewModel.onGroupTapped(groupId) })
                if (open) {
                    Column(Modifier.padding(start = 16.dp)) {
                        row.categories.forEach { ComparedRow(it, locale) }
                    }
                }
            } else {
                ComparedRow(row, locale)
            }
        }
        if (compare.perDay) {
            Supporting(stringResource(R.string.trends_per_day))
        }
    }
    Footnotes(compare.otherCurrencyCount, state.needsReviewCount)

    picking?.let { end ->
        SpanPicker(
            span = if (end == SpanEnd.FIRST) compare.first else compare.second,
            today = today,
            onPicked = { span ->
                viewModel.onSpanPicked(end, span)
                picking = null
            },
            onCancel = { picking = null },
        )
    }
}

@Composable
private fun SpanField(
    label: Int,
    span: Days,
    days: Long,
    today: LocalDate,
    locale: Locale,
    onOpen: () -> Unit,
) {
    OutlinedCard(
        onClick = onOpen,
        modifier =
            Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    Formatters.dateSpan(span.start, span.endInclusive, today, locale),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Supporting(pluralStringResource(R.plurals.trends_days, days.toInt(), days.toInt()))
            }
            Icon(Icons.Default.DateRange, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * A line of the comparison: the name and the change, then both figures under
 * them, so neither amount is squeezed beside the other at a large font size.
 * [opens] is null for a row that opens nothing, and says whether a group is
 * open otherwise.
 */
@Composable
private fun ComparedRow(
    row: ComparedSpending,
    locale: Locale,
    emphasised: Boolean = false,
    opens: Boolean? = null,
    onClick: (() -> Unit)? = null,
) {
    val name =
        when {
            emphasised -> stringResource(R.string.trends_everything)
            row.name == null -> stringResource(R.string.trends_on_no_category)
            else -> row.name
        }
    Column(
        Modifier
            .fillMaxWidth()
            .then(opensCategories(name, opens, onClick))
            .heightIn(min = 48.dp)
            .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (emphasised) FontWeight.SemiBold else null,
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
            ChangeLabel(row.change)
        }
        Supporting(
            stringResource(
                R.string.trends_from_to,
                Formatters.money(row.first, locale),
                Formatters.money(row.second, locale),
            ),
        )
    }
}

/** A group's row opens and closes its categories; any other row is not a control. */
@Composable
private fun opensCategories(
    name: String,
    opens: Boolean?,
    onClick: (() -> Unit)?,
): Modifier {
    if (onClick == null) return Modifier
    val label = if (opens == true) R.string.reports_hide_categories else R.string.reports_show_categories
    return Modifier.clickable(onClickLabel = stringResource(label, name), role = Role.Button, onClick = onClick)
}

@Composable
private fun ChangeLabel(change: Comparison) {
    val label =
        change.movement()
            ?: stringResource(R.string.trends_new).takeIf { change == Comparison.NothingBefore }
            ?: return
    Text(
        label,
        style = MaterialTheme.typography.bodyMedium,
        color = change.judgedColor(),
        modifier = Modifier.padding(start = 8.dp),
    )
}

/**
 * One calendar that takes both ends, refusing the days after today: a span
 * that has not happened has nothing to compare.
 *
 * The picker speaks UTC millis and a span is plain dates, so the conversion is
 * pinned to UTC at both ends, as the ledger's date picker does.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpanPicker(
    span: Days,
    today: LocalDate,
    onPicked: (Days) -> Unit,
    onCancel: () -> Unit,
) {
    val pickerState =
        rememberDateRangePickerState(
            initialSelectedStartDateMillis = span.start.toUtcMillis(),
            initialSelectedEndDateMillis = minOf(span.endInclusive, today).toUtcMillis(),
            selectableDates = UpTo(today),
        )
    val start = pickerState.selectedStartDateMillis
    val end = pickerState.selectedEndDateMillis
    DatePickerDialog(
        onDismissRequest = onCancel,
        confirmButton = {
            TextButton(
                enabled = start != null,
                onClick = {
                    if (start != null) {
                        val from = start.toUtcLocalDate()
                        onPicked(Days(from, end?.toUtcLocalDate() ?: from))
                    }
                },
            ) { Text(stringResource(R.string.trends_choose)) }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.trends_cancel)) } },
    ) {
        DateRangePicker(
            state = pickerState,
            title = { Text(stringResource(R.string.trends_choose_span), Modifier.padding(start = 24.dp, top = 16.dp)) },
            modifier = Modifier.weight(1f),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
private class UpTo(
    private val today: LocalDate,
) : SelectableDates {
    override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis.toUtcLocalDate() <= today

    override fun isSelectableYear(year: Int): Boolean = year <= today.year
}

private fun LocalDate.toUtcMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun Long.toUtcLocalDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()
