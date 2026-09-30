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
import java.time.LocalDate

/**
 * The filters that are on, a removable chip each, in one line that scrolls
 * sideways: three long names never push the list further down the screen.
 *
 * Not [enabled] while rows are selected, since clearing a filter then would
 * show rows the selection's action was never meant to reach.
 */
@Composable
internal fun FilterChips(
    filter: LedgerFilter,
    today: LocalDate,
    actions: FilterActions,
    enabled: Boolean,
) {
    val dates = datesLabel(filter, today)
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        filter.account?.let { account ->
            item(key = "account") { RemovableChip(accountLabel(account), enabled) { actions.onAccount(null) } }
        }
        filter.category?.let { category ->
            item(key = "category") { RemovableChip(categoryLabel(category), enabled) { actions.onCategory(null) } }
        }
        filter.merchant?.let { merchant ->
            item(key = "merchant") { RemovableChip(merchant.displayName, enabled) { actions.onMerchant(null) } }
        }
        dates?.let {
            item(key = "dates") {
                RemovableChip(it, enabled) {
                    actions.onFrom(null)
                    actions.onTo(null)
                }
            }
        }
    }
}

@Composable
private fun RemovableChip(
    name: String,
    enabled: Boolean,
    onRemove: () -> Unit,
) {
    InputChip(
        selected = true,
        onClick = onRemove,
        enabled = enabled,
        label = { Text(name, maxLines = 1) },
        trailingIcon = {
            Icon(
                Icons.Default.Close,
                contentDescription = stringResource(R.string.ledger_filter_clear, name),
                Modifier.size(CHIP_ICON),
            )
        },
    )
}

/** What the filter's dates say — `Since Sep 1`, `Sep 1 – 30` — or null while it has none. */
@Composable
internal fun datesLabel(
    filter: LedgerFilter,
    today: LocalDate,
): String? {
    val locale = currentLocale()
    val from = filter.from
    val to = filter.to
    return when {
        from != null && to != null -> Formatters.dateSpan(from, to, today, locale)
        from != null -> stringResource(R.string.ledger_filter_since, Formatters.shortDate(from, today, locale))
        to != null -> stringResource(R.string.ledger_filter_until, Formatters.shortDate(to, today, locale))
        else -> null
    }
}

@Composable
internal fun categoryLabel(choice: CategoryChoice): String =
    when (choice) {
        CategoryChoice.Uncategorised -> stringResource(R.string.ledger_filter_no_category)
        is CategoryChoice.Named -> choice.category.name
    }

/** Material's own dense icon size, which a chip's icon sits at. */
private val CHIP_ICON = 18.dp
