// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.feature.ledger

import io.github.jaytech64.piastre.core.data.CategoryReach
import io.github.jaytech64.piastre.core.data.RecurringRepository
import io.github.jaytech64.piastre.core.data.TemplateRepository
import io.github.jaytech64.piastre.core.data.TransactionEditor
import io.github.jaytech64.piastre.core.data.TransactionRepository
import io.github.jaytech64.piastre.core.model.Money
import io.github.jaytech64.piastre.core.model.TemplateContent
import io.github.jaytech64.piastre.core.model.Transaction
import java.time.ZoneId

/**
 * What the form is about when it is not a transaction: the recurring transaction
 * [scheduleId], or the template [templateId] — [NEW_TEMPLATE_ID] for one not
 * saved yet. Both null for a transaction.
 */
internal data class FormTarget(
    val scheduleId: Long?,
    val templateId: Long?,
)

/**
 * What the form's Save and Delete write, for each of the things it edits: a new
 * transaction, one already in the ledger, a recurring transaction, and a template.
 */
internal class FormWriter(
    private val transactions: TransactionRepository,
    private val editor: TransactionEditor,
    private val recurring: RecurringRepository,
    private val templates: TemplateRepository,
    private val zoneId: () -> ZoneId,
) {
    /**
     * Writes [state] over [original], or as a new transaction when there is none,
     * then makes it repeat, and keeps it as a template, when either was asked.
     * For a schedule it rewrites what
     * the schedule writes from now on instead, and for a template it writes the
     * template and no transaction.
     */
    suspend fun write(
        state: ExpenseFormUiState,
        original: Transaction?,
        target: FormTarget,
        reach: CategoryReach?,
    ) {
        if (target.templateId != null) {
            writeTemplate(state, target.templateId)
            return
        }
        val amount = state.amount ?: return
        if (target.scheduleId != null) {
            recurring.change(
                id = target.scheduleId,
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
        val rhythm = state.rhythm?.takeIf { state.canRepeat && !state.isSplit }
        // Made to repeat, it becomes its schedule's first row, which only a typed row can.
        val recurring = state.recurring && rhythm == null
        val id = writeTransaction(state, original, amount, reach, recurring)
        state.appliedTemplateId?.let { templates.markUsed(it) }
        if (state.savesAsTemplate) templates.save(state.asTemplate())
        rhythm?.let { this.recurring.repeat(id, it, state.lastDate) }
    }

    /** Adds the transaction on the form, or rewrites [original] with it, and returns its id. */
    private suspend fun writeTransaction(
        state: ExpenseFormUiState,
        original: Transaction?,
        amount: Money,
        reach: CategoryReach?,
        recurring: Boolean,
    ): Long {
        if (original == null) {
            return transactions.addManual(
                amount = amount,
                merchantName = state.merchantText,
                occurredAt = state.occurredAt(zoneId()),
                note = state.note,
                kind = state.kind,
                accountId = state.accountId,
                toAccountId = state.destinationToSave,
                categoryId = state.categoryId,
                reach = reach,
                recurring = recurring,
                parts = state.partsToSave,
            )
        }
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
            recurring = recurring,
            parts = state.partsToSave,
        )
        return original.id
    }

    private suspend fun writeTemplate(
        state: ExpenseFormUiState,
        templateId: Long,
    ) {
        if (templateId == NEW_TEMPLATE_ID) {
            templates.save(state.asTemplate())
        } else {
            templates.change(templateId, state.asTemplate())
        }
    }

    /**
     * Takes [original] out of the ledger; or stops the schedule, keeping the rows
     * it wrote; or removes the template, keeping the transactions it filled.
     */
    suspend fun remove(
        original: Transaction?,
        target: FormTarget,
    ) {
        when {
            target.templateId != null -> templates.delete(target.templateId)
            target.scheduleId != null -> recurring.stop(target.scheduleId)
            original != null -> editor.remove(original.id)
        }
    }
}

/** What the form says, as a template: everything but its day, and of its hour only whether it has one. */
private fun ExpenseFormUiState.asTemplate() =
    TemplateContent(
        name = templateName,
        kind = kind,
        amount = amount,
        merchantName = merchantText,
        accountId = accountId,
        toAccountId = destinationToSave,
        categoryId = categoryId,
        note = note,
        recurring = recurring,
    )
