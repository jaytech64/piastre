// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.feature.ledger

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * The part of the form that is about templates. It reads and writes the form's
 * own state, so a save sees one form; it is a class of its own only so the
 * form's view model stays about saving, as [RepeatChoice] does for repeating.
 */
class TemplateChoice internal constructor(
    private val form: MutableStateFlow<ExpenseFormUiState>,
) {
    fun onNameChange(text: String) = form.update { it.copy(templateName = text) }
}
