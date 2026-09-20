// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.feature.ledger

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.jaytech64.depenses.core.designsystem.accountLabel
import io.github.jaytech64.depenses.core.model.Account
import io.github.jaytech64.depenses.core.model.TransactionKind

/**
 * One account slot on the form: what an expense came from, what income went
 * into, or either end of a transfer — with a way to add the account that is
 * missing without leaving.
 *
 * [readOnly] carries the account of a captured purchase: the notification
 * already named it, so it is shown rather than offered.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AccountField(
    label: Int,
    selectedId: Long?,
    accounts: List<Account>,
    onPick: (Long?) -> Unit,
    onAddAccount: () -> Unit,
    allowNone: Boolean = true,
    readOnly: String? = null,
) {
    if (readOnly != null) {
        OutlinedTextField(
            value = readOnly,
            onValueChange = {},
            readOnly = true,
            enabled = false,
            label = { Text(stringResource(label)) },
            modifier = Modifier.fillMaxWidth(),
        )
        return
    }

    var expanded by remember { mutableStateOf(false) }

    fun pick(id: Long?) {
        onPick(id)
        expanded = false
    }

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value =
                accounts.firstOrNull { it.id == selectedId }?.let { accountLabel(it) }
                    ?: stringResource(R.string.form_account_none),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier =
                Modifier
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (allowNone) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.form_account_none)) },
                    onClick = { pick(null) },
                )
            }
            accounts.forEach { account ->
                DropdownMenuItem(text = { Text(accountLabel(account)) }, onClick = { pick(account.id) })
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.form_add_account)) },
                onClick = {
                    expanded = false
                    onAddAccount()
                },
            )
        }
    }
}

/**
 * The account slots this kind of transaction needs: the account an expense came
 * from or income went into, or both ends of a transfer. Income asks "into" so the
 * direction is on the form, the way a transfer says from and to.
 */
@Composable
internal fun AccountFields(
    state: ExpenseFormUiState,
    accounts: List<Account>,
    viewModel: ExpenseFormViewModel,
    onAddFor: (AccountSlot) -> Unit,
) {
    val captured = state.capturedAccountLabel.takeIf { state.isCaptured }
    when (state.kind) {
        TransactionKind.INCOME -> {
            AccountField(
                label = R.string.form_into,
                selectedId = state.accountId,
                accounts = accounts,
                onPick = { viewModel.onAccountChange(AccountSlot.SOURCE, it) },
                onAddAccount = { onAddFor(AccountSlot.SOURCE) },
                readOnly = captured,
            )
        }

        TransactionKind.EXPENSE -> {
            AccountField(
                label = R.string.form_account,
                selectedId = state.accountId,
                accounts = accounts,
                onPick = { viewModel.onAccountChange(AccountSlot.SOURCE, it) },
                onAddAccount = { onAddFor(AccountSlot.SOURCE) },
                readOnly = captured,
            )
        }

        TransactionKind.TRANSFER -> {
            AccountField(
                label = R.string.form_from,
                selectedId = state.accountId,
                accounts = accounts,
                onPick = { viewModel.onAccountChange(AccountSlot.SOURCE, it) },
                onAddAccount = { onAddFor(AccountSlot.SOURCE) },
                allowNone = false,
                readOnly = captured,
            )
            AccountField(
                label = R.string.form_to,
                selectedId = state.toAccountId,
                accounts = accounts,
                onPick = { viewModel.onAccountChange(AccountSlot.DESTINATION, it) },
                onAddAccount = { onAddFor(AccountSlot.DESTINATION) },
                allowNone = false,
            )
        }
    }
}
