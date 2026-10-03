// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.feature.ledger

import androidx.lifecycle.SavedStateHandle
import io.github.jaytech64.depenses.core.data.CategoryRepository
import io.github.jaytech64.depenses.core.data.TransactionEditor
import io.github.jaytech64.depenses.core.model.CategoryGroup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The rows picked by a long press, and what giving them a category or deleting
 * them does. A class of its own only so the ledger's view model stays about
 * what the ledger shows.
 *
 * The ids live in saved state beside the filter, for the lifetime the filter's
 * KDoc explains: a rotation and a trip to Reports keep them, a cold launch does
 * not. [shown] is every row on screen, and an action reaches only the selected
 * ones among them, so it never touches a row you cannot see.
 */
class LedgerSelection internal constructor(
    private val savedState: SavedStateHandle,
    private val editor: TransactionEditor,
    private val categories: CategoryRepository,
    private val scope: CoroutineScope,
    private val shown: () -> Set<Long>,
) {
    internal val rowIds: Flow<Set<Long>> = savedState.getStateFlow(ROW_IDS, LongArray(0)).map { it.toSet() }

    /** Adds [id] to the selection, or takes it out. */
    fun toggle(id: Long) {
        val selected = now()
        select(if (id in selected) selected - id else selected + id)
    }

    fun end() = select(emptySet())

    /** Gives [categoryId], or none, to the selected rows and nothing else, then ends the selection. */
    fun categorise(categoryId: Long?) {
        val selected = now()
        scope.launch {
            editor.categoriseAll(selected, categoryId)
            end()
        }
    }

    /** What a category added for the selection can go in. */
    val groups: Flow<List<CategoryGroup>> = categories.groups()

    /** Adds the category missing from the list, in [groupId], and gives it to the selection. */
    fun categoriseAsNew(
        name: String,
        groupId: Long?,
    ) {
        val selected = now()
        scope.launch {
            categories.named(name, groupId)?.let { editor.categoriseAll(selected, it) }
            end()
        }
    }

    /** Takes the selected rows out of the ledger, the way the form deletes one. */
    fun delete() {
        val selected = now()
        scope.launch {
            editor.removeAll(selected)
            end()
        }
    }

    /**
     * The saved selection, less any row no longer on screen.
     *
     * It reads saved state rather than the screen's state, which lags a write by
     * one emission: two quick taps would otherwise each start from the same
     * selection, and the first would be lost.
     */
    private fun now(): Set<Long> {
        val onScreen = shown()
        return (savedState.get<LongArray>(ROW_IDS) ?: LongArray(0)).filterTo(mutableSetOf()) { it in onScreen }
    }

    private fun select(ids: Set<Long>) {
        savedState[ROW_IDS] = ids.toLongArray()
    }

    private companion object {
        const val ROW_IDS = "selectedRowIds"
    }
}
