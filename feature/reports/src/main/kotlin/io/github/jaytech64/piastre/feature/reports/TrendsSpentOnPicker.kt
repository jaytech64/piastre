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

/**
 * Everything, then each group followed by its categories, then the categories
 * in no group, then what has no category: the order the ranking and the
 * Budgets page already put them in.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SpentOnPicker(
    state: TrendsUiState,
    locale: Locale,
    onPick: (SpentOn) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val choices = choicesOf(state.groups, state.categories, locale)

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.padding(horizontal = 16.dp),
    ) {
        OutlinedTextField(
            value = choices.firstOrNull { it.on == state.on }?.let { labelOf(it) } ?: "",
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.trends_on)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier =
                Modifier
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { choice ->
                DropdownMenuItem(
                    text = {
                        Text(labelOf(choice), modifier = Modifier.padding(start = if (choice.indented) 16.dp else 0.dp))
                    },
                    onClick = {
                        onPick(choice.on)
                        expanded = false
                    },
                )
            }
        }
    }
}

private data class SpentOnChoice(
    val on: SpentOn,
    /** Null for everything and for what has no category, whose names are the screen's words. */
    val name: String?,
    val indented: Boolean = false,
)

private fun choicesOf(
    groups: List<CategoryGroup>,
    categories: List<Category>,
    locale: Locale,
): List<SpentOnChoice> {
    val collator = Collator.getInstance(locale)
    val inGroups =
        groups.sortedWith(compareBy(collator) { it.name }).flatMap { group ->
            listOf(SpentOnChoice(SpentOn.OneGroup(group.id), group.name)) +
                categories
                    .filter { it.groupId == group.id }
                    .sortedWith(compareBy(collator) { it.name })
                    .map { SpentOnChoice(SpentOn.OneCategory(it.id), it.name, indented = true) }
        }
    val alone =
        categories
            .filter { it.groupId == null }
            .sortedWith(compareBy(collator) { it.name })
            .map { SpentOnChoice(SpentOn.OneCategory(it.id), it.name) }
    return listOf(SpentOnChoice(SpentOn.Everything, null)) + inGroups + alone + SpentOnChoice(SpentOn.NoCategory, null)
}

@Composable
private fun labelOf(choice: SpentOnChoice): String =
    when (choice.on) {
        SpentOn.Everything -> stringResource(R.string.trends_on_everything)
        SpentOn.NoCategory -> stringResource(R.string.trends_on_no_category)
        is SpentOn.OneGroup -> stringResource(R.string.trends_on_group, choice.name.orEmpty())
        is SpentOn.OneCategory -> choice.name.orEmpty()
    }
