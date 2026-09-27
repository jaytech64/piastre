// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.feature.ledger

import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jaytech64.depenses.core.designsystem.ButtonsRow
import io.github.jaytech64.depenses.core.designsystem.ChoiceRow
import io.github.jaytech64.depenses.core.designsystem.Formatters
import io.github.jaytech64.depenses.core.designsystem.LastButton
import io.github.jaytech64.depenses.core.model.Account
import io.github.jaytech64.depenses.core.model.Merchant
import io.github.jaytech64.depenses.core.model.TransactionKind
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.Locale

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
        Text(
            stringResource(if (state.isEditing) R.string.edit_title else R.string.manual_title),
            style = MaterialTheme.typography.titleLarge,
        )

        KindToggle(kind = state.kind, onChange = viewModel::onKindChange)

        OutlinedTextField(
            value = state.amountText,
            onValueChange = viewModel::onAmountChange,
            label = { Text(stringResource(R.string.manual_amount)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            isError = state.amountText.isNotBlank() && state.amount == null,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

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
        CategorySection(state.categoryId, viewModel.category, onAddCategory = { onPick(Picking.ADD_CATEGORY) })

        WhenSection(state = state, viewModel = viewModel, onPick = onPick)

        OutlinedTextField(
            value = state.note,
            onValueChange = viewModel::onNoteChange,
            label = { Text(stringResource(R.string.manual_note)) },
            modifier = Modifier.fillMaxWidth(),
        )

        FormActions(
            state = state,
            onSave = { viewModel.save() },
            onCancel = onCancel,
            onDelete = { onPick(Picking.DELETE) },
        )
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
                Text(stringResource(R.string.edit_delete))
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
    }

/** Defaults to now, because that is when almost every cash purchase is entered. */
@Composable
internal fun WhenRow(
    state: ExpenseFormUiState,
    onDateChange: (LocalDate) -> Unit,
    onPick: (Picking) -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]

    // The time goes under the date, whole, when the two no longer fit side by side.
    ButtonsRow(Modifier.fillMaxWidth()) {
        DateStepper(
            date = state.date,
            onStep = onDateChange,
            onOpen = { onPick(Picking.DATE) },
        )
        OutlinedButton(onClick = { onPick(Picking.TIME) }) {
            Text(Formatters.time(state.time.toInstantOn(state.date), ZoneOffset.UTC, locale))
        }
    }
}

/**
 * The date as an outlined pill with a chevron either side: the chevrons step a
 * day at a time, for the purchase entered the morning after, and the date itself
 * opens the calendar for anything further away.
 *
 * The outline is drawn 4 dp in from the top and bottom so the pill looks as tall
 * as the neighbouring [OutlinedButton] while each target keeps its 48 dp touch
 * height.
 */
@Composable
private fun DateStepper(
    date: LocalDate,
    onStep: (LocalDate) -> Unit,
    onOpen: () -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    val tint = MaterialTheme.colorScheme.primary

    Box(contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .matchParentSize()
                .padding(vertical = 4.dp)
                .border(ButtonDefaults.outlinedButtonBorder(), ButtonDefaults.outlinedShape),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onStep(date.minusDays(1)) }) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = stringResource(R.string.form_previous_day),
                    tint = tint,
                )
            }
            TextButton(onClick = onOpen) {
                Text(Formatters.date(date, locale))
            }
            IconButton(onClick = { onStep(date.plusDays(1)) }) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = stringResource(R.string.form_next_day),
                    tint = tint,
                )
            }
        }
    }
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

private fun LocalTime.toInstantOn(date: LocalDate): Instant = date.atTime(this).toInstant(ZoneOffset.UTC)

private const val MAX_SUGGESTIONS = 6
