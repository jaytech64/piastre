// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.feature.ledger

/** What the filter controls can ask of the ledger. */
internal class FilterActions(
    val onAccount: (Long?) -> Unit,
    val onCategory: (CategoryChoice?) -> Unit,
    val onMerchant: (Long?) -> Unit,
    val onClearAll: () -> Unit,
)
