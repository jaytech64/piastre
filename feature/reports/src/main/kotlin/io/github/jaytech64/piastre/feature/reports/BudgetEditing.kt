// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.feature.reports

import io.github.jaytech64.piastre.core.data.BudgetLine

/** A budget being added, or one being changed or stopped. */
internal sealed interface BudgetEditing {
    data object New : BudgetEditing

    data class Existing(
        val line: BudgetLine,
    ) : BudgetEditing
}
