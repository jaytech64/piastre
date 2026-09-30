// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.feature.ledger

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
