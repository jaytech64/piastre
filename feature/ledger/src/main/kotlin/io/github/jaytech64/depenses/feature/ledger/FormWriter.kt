// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.feature.ledger

import io.github.jaytech64.depenses.core.data.CategoryReach
import io.github.jaytech64.depenses.core.data.RecurringRepository
import io.github.jaytech64.depenses.core.data.TransactionEditor
import io.github.jaytech64.depenses.core.data.TransactionRepository
import io.github.jaytech64.depenses.core.model.Transaction
import java.time.ZoneId

/**
 * What the form's Save and Delete write, for each of the three things it edits:
 * a new transaction, one already in the ledger, and a recurring transaction.
 */
internal class FormWriter(
    private val transactions: TransactionRepository,
    private val editor: TransactionEditor,
    private val recurring: RecurringRepository,
    private val zoneId: () -> ZoneId,
) {
    /**
     * Writes [state] over [original], or as a new transaction when there is none,
     * then makes it repeat when that was asked. For a schedule, [scheduleId], it
     * rewrites what the schedule writes from now on instead.
     */
    suspend fun write(
        state: ExpenseFormUiState,
        original: Transaction?,
        scheduleId: Long?,
        reach: CategoryReach?,
    ) {
        val amount = state.amount ?: return
        if (scheduleId != null) {
            recurring.change(
                id = scheduleId,
                amount = amount,
                kind = state.kind,
                merchantName = state.merchantText,
                accountId = state.accountId,
                toAccountId = state.destinationToSave,
                categoryId = state.categoryId,
                note = state.note,
                rhythm = state.rhythm ?: return,
                nextDate = state.date,
                lastDate = state.lastDate,
            )
            return
        }
        val id =
            if (original == null) {
                transactions.addManual(
                    amount = amount,
                    merchantName = state.merchantText,
                    occurredAt = state.occurredAt(zoneId()),
                    note = state.note,
                    kind = state.kind,
                    accountId = state.accountId,
                    toAccountId = state.destinationToSave,
                    categoryId = state.categoryId,
                    reach = reach,
                )
            } else {
                editor.edit(
                    id = original.id,
                    amount = amount,
                    kind = state.kind,
                    merchantName = state.merchantText,
                    occurredAt = state.occurredAtLike(original),
                    note = state.note,
                    accountId = state.accountId,
                    toAccountId = state.destinationToSave,
                    categoryId = state.categoryId,
                    reach = reach,
                )
                original.id
            }
        state.rhythm?.takeIf { state.canRepeat }?.let { recurring.repeat(id, it, state.lastDate) }
    }

    /** Takes [original] out of the ledger, or stops schedule [scheduleId], keeping the rows it wrote. */
    suspend fun remove(
        original: Transaction?,
        scheduleId: Long?,
    ) {
        when {
            scheduleId != null -> recurring.stop(scheduleId)
            original != null -> editor.remove(original.id)
        }
    }
}
