// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.feature.ledger

import io.github.jaytech64.depenses.core.model.Rhythm
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalDate

/**
 * The form's *Repeats* and *Ends* fields. It reads and writes the form's own
 * state, so a save sees one form; it is a class of its own only so the form's
 * view model stays about saving, as [CategoryPicker] does for the category.
 */
class RepeatChoice internal constructor(
    private val form: MutableStateFlow<ExpenseFormUiState>,
) {
    fun onRhythmChange(rhythm: Rhythm?) = form.update { it.copy(rhythm = rhythm) }

    fun onLastDateChange(date: LocalDate?) = form.update { it.copy(lastDate = date) }

    fun dismissBackfill() = form.update { it.copy(backfill = null) }

    /**
     * True when saving would write rows for days up to [today] besides the one on
     * the form, and the question has been put on screen instead.
     */
    internal fun asksAboutPastDays(today: LocalDate): Boolean {
        val state = form.value
        val backfill =
            state.recurrence
                ?.takeIf { state.canRepeat }
                ?.let { Backfill.of(it.datesBetween(after = state.date, through = today)) }
                ?: return false
        form.update { it.copy(backfill = backfill) }
        return true
    }
}

/** The rows a repeating save would write for days already past: how many, and the first and last day. */
data class Backfill(
    val count: Int,
    val first: LocalDate,
    val last: LocalDate,
) {
    companion object {
        fun of(days: List<LocalDate>): Backfill? =
            days.takeIf { it.isNotEmpty() }?.let { Backfill(it.size, it.first(), it.last()) }
    }
}
