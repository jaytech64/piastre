// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.feature.reports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.jaytech64.piastre.core.data.BudgetLine
import io.github.jaytech64.piastre.core.designsystem.Formatters
import io.github.jaytech64.piastre.core.model.BudgetTarget
import io.github.jaytech64.piastre.core.model.Category
import io.github.jaytech64.piastre.core.model.CategoryGroup
import io.github.jaytech64.piastre.core.model.Money
import java.text.Collator
import java.time.YearMonth

/**
 * Adds a budget, or changes or stops one, from [month] on. A new one picks what
 * it is on among the groups and categories that have none this month; an
 * existing one keeps its target, and its amount is typed afresh.
 */
@Composable
internal fun BudgetDialog(
    editing: BudgetEditing,
    month: YearMonth,
    categories: List<Category>,
    groups: List<CategoryGroup>,
    taken: Set<BudgetTarget>,
    onSave: (BudgetTarget, Money) -> Unit,
    onStop: (BudgetTarget) -> Unit,
    onDismiss: () -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    val existing = (editing as? BudgetEditing.Existing)?.line
    var target by remember { mutableStateOf(existing?.target) }
    var amount by remember { mutableStateOf(existing?.amount?.let { Formatters.amountInput(it, locale) } ?: "") }
    val parsed = Formatters.parseMoney(amount)?.takeIf { it.minor > 0 }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(existing?.name ?: stringResource(R.string.budget_new)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (existing == null) {
                    TargetMenu(choices(categories, groups, taken, locale), target) { target = it }
                }
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text(stringResource(R.string.budget_each_month)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(R.string.budget_from, Formatters.monthYear(month, locale)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Away from Save, so ending a budget is never a slip of the thumb, and the
                // two buttons below stay on one line at every font size.
                existing?.let { line ->
                    TextButton(
                        onClick = { onStop(line.target) },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text(stringResource(R.string.budget_stop)) }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { target?.let { chosen -> parsed?.let { onSave(chosen, it) } } },
                enabled = target != null && parsed != null,
            ) { Text(stringResource(R.string.budget_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.budget_cancel)) } },
    )
}

/** Groups first, then categories, each A to Z, and only what has no budget this month. */
@Composable
private fun choices(
    categories: List<Category>,
    groups: List<CategoryGroup>,
    taken: Set<BudgetTarget>,
    locale: java.util.Locale,
): List<Pair<BudgetTarget, String>> {
    val collator = Collator.getInstance(locale)
    val groupChoices =
        groups
            .sortedWith(compareBy(collator) { it.name })
            .map { BudgetTarget.Group(it.id) to stringResource(R.string.budget_group_label, it.name) }
    val categoryChoices =
        categories
            .sortedWith(compareBy(collator) { it.name })
            .map { BudgetTarget.Category(it.id) to it.name }
    return (groupChoices + categoryChoices).filter { (choice, _) -> choice !in taken }
}

/** What a new budget is on, picked from a menu: a list of every category would push the amount out of sight. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TargetMenu(
    choices: List<Pair<BudgetTarget, String>>,
    selected: BudgetTarget?,
    onPick: (BudgetTarget) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = choices.firstOrNull { it.first == selected }?.second.orEmpty(),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.budget_on)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { (choice, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onPick(choice)
                        expanded = false
                    },
                )
            }
        }
    }
}
