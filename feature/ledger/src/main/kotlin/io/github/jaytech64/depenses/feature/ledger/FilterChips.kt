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

/** The name of each filter that is on, in the order account, category, merchant. */
@Composable
internal fun filterNames(filter: LedgerFilter): List<String> =
    listOfNotNull(
        filter.account?.let { accountLabel(it) },
        filter.category?.let { categoryLabel(it) },
        filter.merchant?.displayName,
    )

/**
 * The filters that are on, a removable chip each, in one line that scrolls
 * sideways: three long names never push the list further down the screen.
 */
@Composable
internal fun FilterChips(
    filter: LedgerFilter,
    actions: FilterActions,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        filter.account?.let { account ->
            item(key = "account") { RemovableChip(accountLabel(account)) { actions.onAccount(null) } }
        }
        filter.category?.let { category ->
            item(key = "category") { RemovableChip(categoryLabel(category)) { actions.onCategory(null) } }
        }
        filter.merchant?.let { merchant ->
            item(key = "merchant") { RemovableChip(merchant.displayName) { actions.onMerchant(null) } }
        }
    }
}

@Composable
private fun RemovableChip(
    name: String,
    onRemove: () -> Unit,
) {
    InputChip(
        selected = true,
        onClick = onRemove,
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

@Composable
internal fun categoryLabel(choice: CategoryChoice): String =
    when (choice) {
        CategoryChoice.Uncategorised -> stringResource(R.string.ledger_filter_no_category)
        is CategoryChoice.Named -> choice.category.name
    }

/** Material's own dense icon size, which a chip's icon sits at. */
private val CHIP_ICON = 18.dp
