// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.feature.settings

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.jaytech64.piastre.core.model.Account

/**
 * An account being dragged to a new place in the Settings list.
 *
 * The list reorders under the finger as the dragged row passes the middle of its
 * neighbour, but nothing is written until the finger lifts: one move per drag,
 * rather than a burst of writes and re-emitted lists while the row is in the air.
 *
 * The order the drag produced is kept on screen until the stored order comes back
 * ([settled]), so the row does not jump back to where it started for the moment
 * the write takes.
 */
@Stable
internal class AccountReorder(
    private val onMove: (Account, Int) -> Unit,
) {
    private var pending by mutableStateOf<List<Account>?>(null)

    var draggedId by mutableStateOf<Long?>(null)
        private set

    /** How far the dragged row sits from its slot in the list, in pixels. */
    var offset by mutableFloatStateOf(0f)
        private set

    private val heights = mutableMapOf<Long, Int>()
    private var startIndex = NOT_DRAGGING

    fun shown(stored: List<Account>): List<Account> = pending ?: stored

    fun measured(
        id: Long,
        height: Int,
    ) {
        heights[id] = height
    }

    fun start(
        stored: List<Account>,
        id: Long,
    ) {
        val list = shown(stored)
        startIndex = list.indexOfFirst { it.id == id }
        if (startIndex == NOT_DRAGGING) return
        pending = list
        draggedId = id
        offset = 0f
    }

    fun drag(dy: Float) {
        val list = pending?.toMutableList() ?: return
        var index = list.indexOfFirst { it.id == draggedId }
        if (index == NOT_DRAGGING) return
        offset += dy
        while (index < list.lastIndex && offset > heightOf(list[index + 1]) / 2f) {
            offset -= heightOf(list[index + 1])
            list.add(index + 1, list.removeAt(index))
            index++
        }
        while (index > 0 && offset < -heightOf(list[index - 1]) / 2f) {
            offset += heightOf(list[index - 1])
            list.add(index - 1, list.removeAt(index))
            index--
        }
        pending = list
    }

    fun end() {
        val list = pending
        val index = list?.indexOfFirst { it.id == draggedId } ?: NOT_DRAGGING
        draggedId = null
        offset = 0f
        if (list == null || index == NOT_DRAGGING || index == startIndex) {
            pending = null
        } else {
            onMove(list[index], index)
        }
        startIndex = NOT_DRAGGING
    }

    /** The stored order changed; show it, unless a drag is still in the air. */
    fun settled() {
        if (draggedId == null) pending = null
    }

    private fun heightOf(account: Account): Int = heights[account.id] ?: 0

    private companion object {
        const val NOT_DRAGGING = -1
    }
}
