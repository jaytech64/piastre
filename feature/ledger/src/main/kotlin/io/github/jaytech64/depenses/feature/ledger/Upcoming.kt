// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.feature.ledger

import androidx.lifecycle.SavedStateHandle
import io.github.jaytech64.depenses.core.model.Transaction
import kotlinx.coroutines.flow.StateFlow

/**
 * A line in the group above today: a transaction dated after today, which
 * counts nowhere until its day (ADR-0015).
 */
data class UpcomingLine(
    val transaction: Transaction,
)

/**
 * Whether the group above today is open. Kept in saved state, as the filter is,
 * so it stays open through a rotation and a trip to Reports and back, while a
 * cold launch always opens the ledger on what has happened.
 */
class UpcomingGroup internal constructor(
    private val savedState: SavedStateHandle,
) {
    internal val open: StateFlow<Boolean> = savedState.getStateFlow(UPCOMING_OPEN, false)

    fun toggle() {
        savedState[UPCOMING_OPEN] = !open.value
    }

    private companion object {
        const val UPCOMING_OPEN = "upcomingOpen"
    }
}
