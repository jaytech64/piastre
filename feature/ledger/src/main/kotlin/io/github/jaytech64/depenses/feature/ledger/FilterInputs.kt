// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.feature.ledger

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.jaytech64.depenses.core.designsystem.Formatters
import io.github.jaytech64.depenses.core.designsystem.accountLabel
import io.github.jaytech64.depenses.core.model.Account
import io.github.jaytech64.depenses.core.model.Merchant
import java.text.Collator

/**
 * Every account, each with what it currently holds, so the choice is made against
 * the balances rather than against a list of names.
 *
 * Those balances are the account's whole history, the same figure Settings shows,
 * where the headline is this month alone. The headline under a filter says
 * "· this month" for exactly that reason.
 */
@Composable
internal fun AccountFilterField(
    accounts: List<Account>,
    selected: Account?,
    onPick: (Long?) -> Unit,
) {
    val locale = currentLocale()
    FilterDropdown(
        label = stringResource(R.string.ledger_filter_account),
        value = selected?.let { accountLabel(it) } ?: stringResource(R.string.ledger_filter_all),
    ) { pick ->
        DropdownMenuItem(
            text = { Text(stringResource(R.string.ledger_filter_all)) },
            leadingIcon = { SelectedMark(selected == null) },
            onClick = { pick { onPick(null) } },
        )
        accounts.forEach { account ->
            DropdownMenuItem(
                text = { Text(accountLabel(account), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { SelectedMark(selected?.id == account.id) },
                trailingIcon = { Aside(Formatters.money(account.balance, locale)) },
                onClick = { pick { onPick(account.id) } },
            )
        }
    }
}

/**
 * *No category* first, then each group — itself a choice, showing every category
 * in it — with its categories under it, A to Z, and those in no group last. With
 * no group at all, the categories keep the order the form suggests them in.
 */
@Composable
internal fun CategoryFilterField(
    options: List<FilterOption<CategoryChoice>>,
    groups: List<FilterOption<CategoryChoice.InGroup>>,
    selected: CategoryChoice?,
    onPick: (CategoryChoice?) -> Unit,
) {
    val locale = currentLocale()
    val named = options.mapNotNull { option -> (option.value as? CategoryChoice.Named)?.let { it.category to option } }
    val counts = named.associate { (category, option) -> category.id to option }
    val sections =
        remember(named, groups, locale) {
            categorySections(named.map { it.first }, groups.map { it.value.group }, Collator.getInstance(locale))
        }
    val groupOptions = groups.associateBy { it.value.group.id }

    @Composable
    fun Item(
        option: FilterOption<CategoryChoice>,
        pick: (() -> Unit) -> Unit,
    ) = DropdownMenuItem(
        text = { Text(categoryLabel(option.value), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { SelectedMark(selected == option.value) },
        trailingIcon = { Aside(option.count.toString()) },
        onClick = { pick { onPick(option.value) } },
    )

    FilterDropdown(
        label = stringResource(R.string.ledger_filter_category),
        value = selected?.let { categoryLabel(it) } ?: stringResource(R.string.ledger_filter_all_categories),
    ) { pick ->
        DropdownMenuItem(
            text = { Text(stringResource(R.string.ledger_filter_all_categories)) },
            leadingIcon = { SelectedMark(selected == null) },
            onClick = { pick { onPick(null) } },
        )
        options.filter { it.value == CategoryChoice.Uncategorised }.forEach { Item(it, pick) }
        if (groups.isEmpty()) {
            named.forEach { (_, option) -> Item(option, pick) }
            return@FilterDropdown
        }
        sections.forEach { section ->
            val group = section.group
            if (group == null) {
                MenuHeading(stringResource(R.string.form_category_ungrouped))
            } else {
                groupOptions[group.id]?.let { GroupItem(it, selected, pick, onPick) }
            }
            section.categories.forEach { category -> counts[category.id]?.let { Item(it, pick) } }
        }
    }
}

/** A group in the category menu: a heading that is itself a choice, showing every category under it. */
@Composable
private fun GroupItem(
    option: FilterOption<CategoryChoice.InGroup>,
    selected: CategoryChoice?,
    pick: (() -> Unit) -> Unit,
    onPick: (CategoryChoice?) -> Unit,
) {
    val isSelected = (selected as? CategoryChoice.InGroup)?.group?.id == option.value.group.id
    DropdownMenuItem(
        text = {
            Text(
                option.value.group.name,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingIcon = { SelectedMark(isSelected) },
        trailingIcon = { Aside(option.count.toString()) },
        onClick = { pick { onPick(option.value) } },
    )
}

/** A read-only field whose menu closes itself once something in it is picked. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterDropdown(
    label: String,
    value: String,
    items: @Composable (pick: (() -> Unit) -> Unit) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier =
                Modifier
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            items { choose ->
                choose()
                expanded = false
            }
        }
    }
}

@Composable
internal fun MerchantFilterField(
    selected: Merchant?,
    onOpen: () -> Unit,
) {
    OpeningField(
        label = stringResource(R.string.ledger_filter_merchant),
        value = selected?.displayName ?: stringResource(R.string.ledger_filter_all_merchants),
        onOpen = onOpen,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Looks like the dropdown fields and opens something else — a list, a calendar —
 * instead of a menu. A read-only text field swallows clicks, so the press is
 * read off its interaction source.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OpeningField(
    label: String,
    value: String,
    onOpen: () -> Unit,
    modifier: Modifier,
    trailingIcon: @Composable () -> Unit = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = false) },
) {
    val interactions = remember { MutableInteractionSource() }
    val focus = LocalFocusManager.current
    LaunchedEffect(interactions) {
        interactions.interactions.collect {
            if (it is PressInteraction.Release) {
                // Otherwise the field keeps a focus ring after what it opened has closed.
                focus.clearFocus()
                onOpen()
            }
        }
    }
    OutlinedTextField(
        value = value,
        onValueChange = {},
        readOnly = true,
        singleLine = true,
        label = { Text(label) },
        trailingIcon = trailingIcon,
        interactionSource = interactions,
        modifier = modifier,
    )
}

/**
 * Two fields side by side, one above the other once the font is large enough
 * that half the width would cut a date or an amount short.
 */
@Composable
internal fun FieldPair(
    first: @Composable (Modifier) -> Unit,
    second: @Composable (Modifier) -> Unit,
) {
    if (LocalDensity.current.fontScale > STACKED_ABOVE_FONT_SCALE) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            first(Modifier.fillMaxWidth())
            second(Modifier.fillMaxWidth())
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            first(Modifier.weight(1f))
            second(Modifier.weight(1f))
        }
    }
}

/** Past this, a date such as « 30 sept. 2026 » no longer fits in half a phone's width. */
private const val STACKED_ABOVE_FONT_SCALE = 1.3f
