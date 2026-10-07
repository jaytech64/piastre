// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.feature.ledger

import io.github.jaytech64.depenses.core.designsystem.Formatters
import io.github.jaytech64.depenses.core.model.Money
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * One part of a split as typed so far. The amount is kept as text for the reason
 * the form's own is; [key] only tells the parts apart while they are on screen.
 */
data class PartDraft(
    val key: Long,
    val amountText: String = "",
    val categoryId: Long? = null,
    val note: String = "",
) {
    /** A share is more than nothing, so zero and a refund's minus are not an amount here. */
    val amount: Money? get() = Formatters.parseMoney(amountText)?.takeIf { it.minor > 0 }
}

/**
 * The parts the form's amount is divided into. It reads and writes the form's
 * own state, as [RepeatChoice] and [CategoryPicker] do, so a save sees one form.
 *
 * While there are parts the form has no category of its own, repeats nothing and
 * is not kept as a template: a split is one transaction's, by hand (ADR-0018).
 */
class SplitParts internal constructor(
    private val form: MutableStateFlow<ExpenseFormUiState>,
) {
    private var awaitingCategory: Long? = null

    /**
     * Starts dividing the amount. The category already chosen becomes the first
     * part's, with its amount still to type: the form cannot know how much of the
     * whole was for it.
     */
    fun start() =
        form.update { state ->
            if (state.isSplit) return@update state
            state.copy(
                parts = listOf(PartDraft(key = 0, categoryId = state.categoryId)),
                categoryId = null,
                categoryChosen = true,
                reachOthers = false,
                fromNowOnChoice = null,
                rhythm = null,
                lastDate = null,
                savesAsTemplate = false,
            )
        }

    /** Adds a part already holding what is left to divide, so the last one is never typed. */
    fun add() =
        form.update { state ->
            val left = state.leftToSplit?.takeIf { it.minor > 0 }
            val next =
                PartDraft(
                    key = state.parts.maxOf { it.key } + 1,
                    amountText = left?.let(Formatters::amountInput).orEmpty(),
                )
            state.copy(parts = state.parts + next)
        }

    /**
     * Takes a part away. One part divides nothing, so when a single one would be
     * left the split ends and the transaction takes that part's category.
     */
    fun remove(key: Long) =
        form.update { state ->
            val remaining = state.parts.filterNot { it.key == key }
            if (remaining.size > 1) state.copy(parts = remaining) else state.unsplitWith(remaining.firstOrNull())
        }

    /** Ends the split: the transaction is ordinary again, under its first part's category. */
    fun stop() = form.update { it.unsplitWith(it.parts.firstOrNull()) }

    fun onAmountChange(
        key: Long,
        text: String,
    ) = change(key) { it.copy(amountText = text) }

    fun onCategoryChange(
        key: Long,
        categoryId: Long?,
    ) = change(key) { it.copy(categoryId = categoryId) }

    fun onNoteChange(
        key: Long,
        text: String,
    ) = change(key) { it.copy(note = text) }

    /** Remembers which part the category about to be added from its list is for. */
    fun awaitCategoryFor(key: Long) {
        awaitingCategory = key
    }

    /**
     * Where a category just added goes: onto the part that asked for it when
     * [forPart], and otherwise nowhere but the form's own field, which is the
     * picker's default.
     */
    fun takingCategory(forPart: Boolean): ((Long) -> Unit)? =
        awaitingCategory?.takeIf { forPart }?.let { key -> { categoryId -> onCategoryChange(key, categoryId) } }

    private fun change(
        key: Long,
        change: (PartDraft) -> PartDraft,
    ) = form.update { state -> state.copy(parts = state.parts.map { if (it.key == key) change(it) else it }) }
}

private fun ExpenseFormUiState.unsplitWith(part: PartDraft?): ExpenseFormUiState =
    copy(parts = emptyList(), categoryId = part?.categoryId, categoryChosen = true)
