// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.feature.ledger

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jaytech64.piastre.core.designsystem.ButtonsRow
import io.github.jaytech64.piastre.core.designsystem.ChoiceRow
import io.github.jaytech64.piastre.core.designsystem.LastButton
import io.github.jaytech64.piastre.core.model.Account
import io.github.jaytech64.piastre.core.model.Merchant
import io.github.jaytech64.piastre.core.model.TransactionKind

/**
 * Adds a transaction — money spent or received — or corrects one already in the
 * ledger.
 *
 * Cash and Interac never post a notification, so they are typed. Only the amount
 * is required: a purchase you half-remember is still worth more in the ledger
 * than left out of it.
 */
@Composable
fun ExpenseFormScreen(
    onDone: () -> Unit,
    viewModel: ExpenseFormViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val merchants by viewModel.knownMerchants.collectAsStateWithLifecycle()
    val accounts by viewModel.knownAccounts.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf(Picking.NOTHING) }

    LaunchedEffect(state.done) {
        if (state.done) onDone()
    }

    if (state.loading) return

    EntryForm(
        state = state,
        merchants = merchants,
        accounts = accounts,
        viewModel = viewModel,
        onPick = { picking = it },
        onCancel = onDone,
    )

    FormDialog(picking = picking, state = state, viewModel = viewModel, onClose = { picking = Picking.NOTHING })

    state.backfill?.let {
        BackfillDialog(
            it,
            onConfirm = { viewModel.save(pastDaysAgreed = true) },
            onDismiss = viewModel.repeat::dismissBackfill,
        )
    }
}

@Composable
private fun EntryForm(
    state: ExpenseFormUiState,
    merchants: List<Merchant>,
    accounts: List<Account>,
    viewModel: ExpenseFormViewModel,
    onPick: (Picking) -> Unit,
    onCancel: () -> Unit,
) {
    // Scrolls because a split-screen window, or the keyboard, can leave less
    // height than the form needs — and Save is the last thing in it.
    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        FormTitle(state, onOpenTemplates = { onPick(Picking.TEMPLATES) })

        if (state.isTemplate) TemplateNameField(state.templateName, viewModel.template::onNameChange)

        KindToggle(kind = state.kind, onChange = viewModel::onKindChange)

        AmountField(state, viewModel::onAmountChange)

        // A transfer moves your own money, so it has no merchant to name.
        if (state.kind != TransactionKind.TRANSFER) {
            OutlinedTextField(
                value = state.merchantText,
                onValueChange = viewModel::onMerchantChange,
                label = { Text(stringResource(merchantLabel(state.kind))) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            MerchantSuggestions(
                merchants = merchants,
                typed = state.merchantText,
                onPick = viewModel::onMerchantChange,
            )
        }

        AccountFields(state, accounts, viewModel, onAddFor = { slot -> onPick(slot.picking()) })

        // After the accounts, because a transfer's category is about where it goes.
        WhatFor(state, accounts, viewModel, onPick)

        // A template says what a transaction is, never when — only whether it has an hour at all.
        if (state.isTemplate) {
            OfferBox(
                label = stringResource(R.string.form_time_recurring),
                checked = state.recurring,
                onChange = viewModel.repeat::onRecurringChange,
            )
        } else {
            WhenSection(state = state, viewModel = viewModel, onPick = onPick)
        }

        OutlinedTextField(
            value = state.note,
            onValueChange = viewModel::onNoteChange,
            label = { Text(stringResource(R.string.manual_note)) },
            modifier = Modifier.fillMaxWidth(),
        )

        if (state.offersSavingAsTemplate) SaveAsTemplate(state, viewModel.template)

        FormActions(
            state = state,
            onSave = { viewModel.save() },
            onCancel = onCancel,
            onDelete = { onPick(Picking.DELETE) },
        )
    }
}

/**
 * What the amount was for: one category, with the way to divide it under the
 * field, or the parts it is divided into.
 */
@Composable
private fun WhatFor(
    state: ExpenseFormUiState,
    accounts: List<Account>,
    viewModel: ExpenseFormViewModel,
    onPick: (Picking) -> Unit,
) {
    if (state.isSplit) {
        SplitSection(
            state,
            accounts,
            viewModel.split,
            viewModel.category,
            onAddCategory = { onPick(Picking.ADD_PART_CATEGORY) },
            onAddAccount = { onPick(Picking.ADD_PART_ACCOUNT) },
        )
        return
    }
    CategorySection(
        state.categoryId,
        viewModel.category,
        offers = !state.isSchedule && !state.isTemplate,
        onAddCategory = { onPick(Picking.ADD_CATEGORY) },
    )
    if (state.offersSplit) {
        TextButton(onClick = viewModel.split::start) { Text(stringResource(R.string.form_split)) }
    }
}

/** Save and Cancel together; Delete kept apart at the far end, and only when there is something to delete. */
@Composable
private fun FormActions(
    state: ExpenseFormUiState,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    ButtonsRow(Modifier.fillMaxWidth(), last = if (state.isEditing) LastButton.FAR_END else LastButton.IN_LINE) {
        Button(onClick = onSave, enabled = state.canSave) {
            Text(stringResource(R.string.manual_save))
        }
        TextButton(onClick = onCancel) { Text(stringResource(R.string.manual_cancel)) }
        if (state.isEditing) {
            TextButton(
                onClick = onDelete,
                enabled = !state.saving,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Text(stringResource(if (state.isSchedule) R.string.recurring_stop else R.string.edit_delete))
            }
        }
    }
}

/**
 * Expense, income or transfer, first on the form because it changes what the rest
 * of it asks: income comes from a source, not a merchant.
 */
@Composable
private fun KindToggle(
    kind: TransactionKind,
    onChange: (TransactionKind) -> Unit,
) {
    ChoiceRow(
        options = TransactionKind.entries,
        selected = kind,
        onSelect = onChange,
        label = { option ->
            stringResource(
                when (option) {
                    TransactionKind.EXPENSE -> R.string.form_kind_expense
                    TransactionKind.INCOME -> R.string.form_kind_income
                    TransactionKind.TRANSFER -> R.string.form_kind_transfer
                },
            )
        },
    )
}

private fun merchantLabel(kind: TransactionKind): Int =
    when (kind) {
        TransactionKind.INCOME -> R.string.form_source
        else -> R.string.manual_merchant
    }

/** Which dialog adding an account from a given slot should open. */
private fun AccountSlot.picking(): Picking =
    when (this) {
        AccountSlot.SOURCE -> Picking.ADD_ACCOUNT
        AccountSlot.DESTINATION -> Picking.ADD_DESTINATION
        AccountSlot.PART -> Picking.ADD_PART_ACCOUNT
    }

/**
 * Names you have used before, filtered as you type.
 *
 * The point is not convenience: picking an existing name is what stops "Marché
 * St-Joseph" and "MARCHE ST JOSEPH" becoming two merchants in the reports.
 */
@Composable
private fun MerchantSuggestions(
    merchants: List<Merchant>,
    typed: String,
    onPick: (String) -> Unit,
) {
    // The name just picked stays in the row on purpose. Dropping it would collapse
    // the row under the finger that tapped it and slide the buttons up into the
    // gap — the kind of layout jump that gets Annuler pressed instead of the note.
    val matches =
        remember(merchants, typed) {
            merchants
                .filter { typed.isBlank() || it.displayName.contains(typed, ignoreCase = true) }
                .take(MAX_SUGGESTIONS)
        }
    if (matches.isEmpty()) return

    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        matches.forEach { merchant ->
            SuggestionChip(
                onClick = { onPick(merchant.displayName) },
                label = { Text(merchant.displayName) },
            )
        }
    }
}

private const val MAX_SUGGESTIONS = 6
