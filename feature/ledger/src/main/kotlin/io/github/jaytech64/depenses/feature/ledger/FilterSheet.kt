// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.feature.ledger

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.jaytech64.depenses.core.designsystem.ButtonsRow
import io.github.jaytech64.depenses.core.designsystem.Formatters
import io.github.jaytech64.depenses.core.designsystem.LastButton
import io.github.jaytech64.depenses.core.designsystem.accountLabel
import io.github.jaytech64.depenses.core.model.Account
import io.github.jaytech64.depenses.core.model.Merchant
import io.github.jaytech64.depenses.core.model.containsTyped
import kotlinx.coroutines.launch

/**
 * Every filter in one place, each applied the moment it is picked, the way the
 * account menu always was — there is no *Apply* to forget.
 *
 * The merchant list takes the whole sheet rather than a dropdown, because there
 * can be hundreds of merchants and finding one means typing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FilterSheet(
    state: LedgerUiState,
    actions: FilterActions,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var choosingMerchant by rememberSaveable { mutableStateOf(false) }

    fun close() {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (choosingMerchant) {
                MerchantList(
                    options = state.merchants,
                    selected = state.filter.merchant,
                    onPick = {
                        actions.onMerchant(it)
                        choosingMerchant = false
                    },
                    onBack = { choosingMerchant = false },
                )
            } else {
                FilterFields(state, actions, onChooseMerchant = { choosingMerchant = true }, onDone = ::close)
            }
        }
    }
}

@Composable
private fun FilterFields(
    state: LedgerUiState,
    actions: FilterActions,
    onChooseMerchant: () -> Unit,
    onDone: () -> Unit,
) {
    ButtonsRow(Modifier.fillMaxWidth(), last = if (state.filter.isActive) LastButton.FAR_END else LastButton.IN_LINE) {
        Text(stringResource(R.string.ledger_filters_title), style = MaterialTheme.typography.titleLarge)
        if (state.filter.isActive) {
            TextButton(onClick = actions.onClearAll) { Text(stringResource(R.string.ledger_filters_clear_all)) }
        }
    }

    AccountFilterField(state.accounts, state.filter.account, actions.onAccount)
    CategoryFilterField(state.categories, state.filter.category, actions.onCategory)
    MerchantFilterField(state.filter.merchant, onChooseMerchant)

    ButtonsRow(Modifier.fillMaxWidth(), last = LastButton.FAR_END) {
        Button(onClick = onDone) { Text(stringResource(R.string.ledger_filters_done)) }
    }
}

/**
 * Every account, each with what it currently holds, so the choice is made against
 * the balances rather than against a list of names.
 *
 * Those balances are the account's whole history, the same figure Settings shows,
 * where the headline is this month alone. The headline under a filter says
 * "· this month" for exactly that reason.
 */
@Composable
private fun AccountFilterField(
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

@Composable
private fun CategoryFilterField(
    options: List<FilterOption<CategoryChoice>>,
    selected: CategoryChoice?,
    onPick: (CategoryChoice?) -> Unit,
) {
    FilterDropdown(
        label = stringResource(R.string.ledger_filter_category),
        value = selected?.let { categoryLabel(it) } ?: stringResource(R.string.ledger_filter_all_categories),
    ) { pick ->
        DropdownMenuItem(
            text = { Text(stringResource(R.string.ledger_filter_all_categories)) },
            leadingIcon = { SelectedMark(selected == null) },
            onClick = { pick { onPick(null) } },
        )
        options.forEach { option ->
            DropdownMenuItem(
                text = { Text(categoryLabel(option.value), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { SelectedMark(selected == option.value) },
                trailingIcon = { Aside(option.count.toString()) },
                onClick = { pick { onPick(option.value) } },
            )
        }
    }
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

/**
 * Looks like the other two fields and opens the merchant list instead of a menu.
 * A read-only text field swallows clicks, so the press is read off its
 * interaction source.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MerchantFilterField(
    selected: Merchant?,
    onOpen: () -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    LaunchedEffect(interactions) {
        interactions.interactions.collect { if (it is PressInteraction.Release) onOpen() }
    }
    OutlinedTextField(
        value = selected?.displayName ?: stringResource(R.string.ledger_filter_all_merchants),
        onValueChange = {},
        readOnly = true,
        label = { Text(stringResource(R.string.ledger_filter_merchant)) },
        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = false) },
        interactionSource = interactions,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun MerchantList(
    options: List<FilterOption<Merchant>>,
    selected: Merchant?,
    onPick: (Long?) -> Unit,
    onBack: () -> Unit,
) {
    var typed by rememberSaveable { mutableStateOf("") }
    val found = remember(options, typed) { options.filter { it.value.displayName.containsTyped(typed) } }

    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.ledger_filter_back))
        }
        Text(stringResource(R.string.ledger_filter_merchant), style = MaterialTheme.typography.titleLarge)
    }
    OutlinedTextField(
        value = typed,
        onValueChange = { typed = it },
        singleLine = true,
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        placeholder = { Text(stringResource(R.string.ledger_filter_merchant_search)) },
        modifier = Modifier.fillMaxWidth(),
    )
    LazyColumn {
        item(key = "all") {
            ChoiceLine(stringResource(R.string.ledger_filter_all_merchants), selected == null, aside = null) {
                onPick(null)
            }
        }
        items(found, key = { it.value.id }) { option ->
            ChoiceLine(option.value.displayName, selected?.id == option.value.id, aside = option.count.toString()) {
                onPick(option.value.id)
            }
        }
    }
}

/** One line of a list you pick from: the mark, the name, and a figure beside it. */
@Composable
internal fun ChoiceLine(
    name: String,
    selected: Boolean,
    aside: String?,
    color: Color = Color.Unspecified,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SelectedMark(selected)
        Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = color)
        aside?.let { Aside(it) }
    }
}

@Composable
private fun Aside(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Always occupies the slot, so the names line up whichever row is checked. */
@Composable
private fun SelectedMark(selected: Boolean) {
    if (selected) {
        Icon(
            Icons.Default.Check,
            contentDescription = null,
            Modifier.size(MARK_SIZE),
            tint = MaterialTheme.colorScheme.primary,
        )
    } else {
        Spacer(Modifier.size(MARK_SIZE))
    }
}

/** Material's standard icon size, so a checked line and an unchecked one start their names at the same place. */
private val MARK_SIZE = 24.dp
