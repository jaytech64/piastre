// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.feature.ledger

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SelectableDates
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.jaytech64.piastre.core.designsystem.Formatters
import java.time.LocalDate

/**
 * *From* and *To*, each of which can be left as any day: only *From* is
 * "after", only *To* is "before", and both is "between", with no mode to pick
 * first.
 */
@Composable
internal fun DateFields(
    filter: LedgerFilter,
    onPick: (DateEnd) -> Unit,
) {
    FieldPair(
        first = { DateField(R.string.ledger_filter_from, filter.from, { onPick(DateEnd.FROM) }, it) },
        second = { DateField(R.string.ledger_filter_to, filter.to, { onPick(DateEnd.TO) }, it) },
    )
}

@Composable
private fun DateField(
    label: Int,
    date: LocalDate?,
    onOpen: () -> Unit,
    modifier: Modifier,
) {
    val locale = currentLocale()
    OpeningField(
        label = stringResource(label),
        value = date?.let { Formatters.date(it, locale) } ?: stringResource(R.string.ledger_filter_any_day),
        onOpen = onOpen,
        modifier = modifier,
        trailingIcon = { Icon(Icons.Default.DateRange, contentDescription = null) },
    )
}

/**
 * The calendar for one end, which refuses the days beyond the other end, so a
 * span that ends before it starts cannot be built. *Any day* takes the end
 * away again.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DateEndPicker(
    end: DateEnd,
    state: LedgerUiState,
    actions: FilterActions,
    onClose: () -> Unit,
) {
    val today = state.today ?: return
    val filter = state.filter
    val (current, set, selectable) =
        when (end) {
            DateEnd.FROM -> Triple(filter.from, actions.onFrom, Between(first = null, last = filter.to))
            DateEnd.TO -> Triple(filter.to, actions.onTo, Between(first = filter.from, last = null))
        }

    fun pick(date: LocalDate?) {
        set(date)
        onClose()
    }

    DatePickerSheet(
        date = current ?: filter.from ?: filter.to ?: today,
        onPicked = ::pick,
        onCancel = onClose,
        onNone = { pick(null) },
        noneLabel = stringResource(R.string.ledger_filter_any_day),
        confirmLabel = stringResource(android.R.string.ok),
        selectable = selectable,
    )
}

/** The days from [first] to [last], both included; a null end is open. */
@OptIn(ExperimentalMaterial3Api::class)
private class Between(
    private val first: LocalDate?,
    private val last: LocalDate?,
) : SelectableDates {
    override fun isSelectableDate(utcTimeMillis: Long): Boolean {
        val day = utcTimeMillis.toUtcLocalDate()
        return (first == null || day >= first) && (last == null || day <= last)
    }

    override fun isSelectableYear(year: Int): Boolean =
        (first == null || year >= first.year) && (last == null || year <= last.year)
}
