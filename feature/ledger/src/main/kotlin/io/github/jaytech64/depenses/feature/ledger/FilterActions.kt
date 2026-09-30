// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.feature.ledger

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.time.LocalDate

/** What the filter controls can ask of the ledger. */
internal class FilterActions(
    val onAccount: (Long?) -> Unit,
    val onCategory: (CategoryChoice?) -> Unit,
    val onMerchant: (Long?) -> Unit,
    val onFrom: (LocalDate?) -> Unit,
    val onTo: (LocalDate?) -> Unit,
    val onAtLeast: (String) -> Unit,
    val onAtMost: (String) -> Unit,
    val onNote: (String) -> Unit,
    val onClearAll: () -> Unit,
)

/** What each filter control does, made once per screen. */
@Composable
internal fun rememberFilterActions(filters: LedgerFilters): FilterActions =
    remember(filters) {
        FilterActions(
            onAccount = filters::onAccountSelected,
            onCategory = filters::onCategorySelected,
            onMerchant = filters::onMerchantSelected,
            onFrom = filters::onFromSelected,
            onTo = filters::onToSelected,
            onAtLeast = filters::onAtLeastTyped,
            onAtMost = filters::onAtMostTyped,
            onNote = filters::onNoteTyped,
            onClearAll = filters::onFiltersCleared,
        )
    }
