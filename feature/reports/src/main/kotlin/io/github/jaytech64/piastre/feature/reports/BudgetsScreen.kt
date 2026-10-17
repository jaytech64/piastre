// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.feature.reports

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jaytech64.piastre.core.data.BudgetLine
import io.github.jaytech64.piastre.core.designsystem.Formatters
import io.github.jaytech64.piastre.core.model.BudgetTarget
import io.github.jaytech64.piastre.core.model.Money
import io.github.jaytech64.piastre.core.model.leftPerDay
import java.text.Collator
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

/**
 * One month's budgets, stepped like a report: each group with its own budget and
 * then its categories', and last the categories in no group. Only the month you
 * are in can be changed. Without Premium the page has nothing to show and says
 * so; Reports leads to the Premium page instead of here.
 */
@Composable
fun BudgetsScreen(viewModel: BudgetsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val month = state.month ?: return
    val today = state.today ?: return
    val budgets = state.budgets ?: return
    val locale = LocalConfiguration.current.locales[0]
    var editing by remember { mutableStateOf<BudgetEditing?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MonthHeader(month, locale, state.canStepForward, viewModel::onStepBack, viewModel::onStepForward)

        if (!state.premiumOn) {
            Quiet(stringResource(R.string.budgets_premium_off))
            return@Column
        }

        if (budgets.lines.isEmpty()) Quiet(stringResource(R.string.budgets_none_this_month))

        BudgetList(budgets.lines, month, today, locale, editable = state.editable) {
            editing =
                BudgetEditing.Existing(it)
        }

        Footer(budgets.otherCurrencyCount, state.editable) { editing = BudgetEditing.New }
    }

    editing?.let { open ->
        BudgetDialog(
            editing = open,
            month = month,
            categories = state.categories,
            groups = state.groups,
            taken = budgets.lines.map { it.target }.toSet(),
            onSave = { target, amount ->
                viewModel.save(target, amount)
                editing = null
            },
            onStop = { target ->
                viewModel.stop(target)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

/** A row of the page: a budget, indented under its group, or the name of a group that has no budget of its own. */
private sealed interface PageRow {
    data class Line(
        val line: BudgetLine,
        val indented: Boolean,
    ) : PageRow

    data class GroupName(
        val name: String,
    ) : PageRow
}

/**
 * Groups A to Z, each followed by its categories' budgets, then the categories
 * in no group. A group budgeted only through its categories still heads them,
 * by name, so an indented row always says what it is under.
 */
private fun ordered(
    lines: List<BudgetLine>,
    locale: Locale,
): List<PageRow> {
    val collator = Collator.getInstance(locale)
    val (groupLines, categoryLines) = lines.partition { it.target is BudgetTarget.Group }
    val byGroup = categoryLines.groupBy { it.group?.id }
    val groups = (groupLines.mapNotNull { it.group } + categoryLines.mapNotNull { it.group }).distinctBy { it.id }
    val inGroups =
        groups.sortedWith(compareBy(collator) { it.name }).flatMap { group ->
            val own = groupLines.firstOrNull { it.group?.id == group.id }
            val head = own?.let { PageRow.Line(it, indented = false) } ?: PageRow.GroupName(group.name)
            val members = byGroup[group.id].orEmpty().sortedWith(compareBy(collator) { it.name })
            listOf(head) + members.map { PageRow.Line(it, indented = true) }
        }
    val loose =
        byGroup[null]
            .orEmpty()
            .sortedWith(
                compareBy(collator) {
                    it.name
                },
            ).map { PageRow.Line(it, indented = false) }
    return inGroups + loose
}

@Composable
private fun BudgetRow(
    line: BudgetLine,
    indented: Boolean,
    perDay: Money?,
    locale: Locale,
    onClick: (() -> Unit)?,
) {
    val over = line.spent.minor > line.amount.minor
    Column(
        Modifier
            .fillMaxWidth()
            .then(onClick?.let { Modifier.clickable(role = Role.Button, onClick = it) } ?: Modifier)
            .padding(start = if (indented) 32.dp else 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            line.name,
            style =
                if (line.target is BudgetTarget.Group) {
                    MaterialTheme.typography.titleMedium
                } else {
                    MaterialTheme.typography.bodyLarge
                },
        )
        val spent = Formatters.money(line.spent, locale)
        Text(
            stringResource(R.string.budget_spent_of, spent, Formatters.money(line.amount, locale)),
            style = MaterialTheme.typography.bodyMedium,
        )
        BudgetBar(line)
        val left =
            if (over) {
                stringResource(R.string.budget_over, Formatters.money(-line.left, locale))
            } else {
                stringResource(R.string.budget_left, Formatters.money(line.left, locale)) +
                    (perDay?.let { stringResource(R.string.budget_left_per_day, Formatters.money(it, locale)) } ?: "")
            }
        Text(
            left,
            style = MaterialTheme.typography.bodySmall,
            color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Every budget of the month, in the order [ordered] gives; [onOpen] is null in a month that can no longer change. */
@Composable
private fun BudgetList(
    lines: List<BudgetLine>,
    month: YearMonth,
    today: LocalDate,
    locale: Locale,
    editable: Boolean,
    onOpen: (BudgetLine) -> Unit,
) {
    ordered(lines, locale).forEach { row ->
        when (row) {
            is PageRow.GroupName -> {
                Text(
                    row.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            is PageRow.Line -> {
                BudgetRow(
                    line = row.line,
                    indented = row.indented,
                    perDay = leftPerDay(row.line.left, month, today),
                    locale = locale,
                    onClick = if (editable) ({ onOpen(row.line) }) else null,
                )
            }
        }
    }
}

@Composable
private fun MonthHeader(
    month: YearMonth,
    locale: Locale,
    canStepForward: Boolean,
    onStepBack: () -> Unit,
    onStepForward: () -> Unit,
) {
    Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onStepBack) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.budgets_previous))
        }
        Text(
            Formatters.monthYear(month, locale),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onStepForward, enabled = canStepForward) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.budgets_next))
        }
    }
}

/** What the budgets leave out, and the way to add one, or why a past month cannot. */
@Composable
private fun Footer(
    otherCurrencyCount: Int,
    editable: Boolean,
    onAdd: () -> Unit,
) {
    if (otherCurrencyCount > 0) {
        Quiet(pluralStringResource(R.plurals.budgets_other_currency, otherCurrencyCount, otherCurrencyCount))
    }
    if (editable) {
        TextButton(onClick = onAdd, modifier = Modifier.padding(horizontal = 4.dp)) {
            Text(stringResource(R.string.budgets_add))
        }
    } else {
        Quiet(stringResource(R.string.budgets_past_month))
    }
}

@Composable
private fun Quiet(text: String) =
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
