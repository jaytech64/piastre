// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.feature.ledger

import io.github.jaytech64.depenses.core.data.TemplateRepository
import io.github.jaytech64.depenses.core.designsystem.Formatters
import io.github.jaytech64.depenses.core.model.TransactionTemplate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/**
 * The part of the form that is about templates. It reads and writes the form's
 * own state, so a save sees one form; it is a class of its own only so the
 * form's view model stays about saving, as [RepeatChoice] does for repeating.
 *
 * [following] is what the form does after its merchant changes — take that
 * merchant's category and last account while you have chosen neither — since a
 * template can change the merchant too.
 */
class TemplateChoice internal constructor(
    private val form: MutableStateFlow<ExpenseFormUiState>,
    templates: TemplateRepository,
    private val following: (ExpenseFormUiState) -> ExpenseFormUiState,
    scope: CoroutineScope,
    sharing: SharingStarted,
) {
    /** Every template, the one last used first. */
    val all: StateFlow<List<TransactionTemplate>> = templates.all().stateIn(scope, sharing, emptyList())

    fun onNameChange(text: String) = form.update { it.copy(templateName = text) }

    fun apply(template: TransactionTemplate) = form.update { following(it.applying(template)) }
}

/**
 * The form once [template] has filled it. What the template holds replaces what
 * was there, and what it leaves empty stays as typed: an amount entered before
 * picking a template with none is kept. The kind is always the template's. The
 * day, the time and whether it repeats are never touched.
 *
 * An account or a category the template gives counts as chosen, so the merchant
 * it names does not then put its own in their place.
 */
internal fun ExpenseFormUiState.applying(template: TransactionTemplate): ExpenseFormUiState =
    copy(
        kind = template.kind,
        amountText = template.amount?.let(Formatters::amountInput) ?: amountText,
        merchantText = template.merchantName ?: merchantText,
        accountId = template.accountId ?: accountId,
        accountChosen = accountChosen || template.accountId != null,
        toAccountId = template.toAccountId ?: toAccountId,
        categoryId = template.categoryId ?: categoryId,
        categoryChosen = categoryChosen || template.categoryId != null,
        note = template.note ?: note,
        appliedTemplateId = template.id,
    )
