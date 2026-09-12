// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.jaytech64.depenses.core.model.Account
import io.github.jaytech64.depenses.core.model.AccountOrigin
import io.github.jaytech64.depenses.core.model.AccountType

/**
 * Names an account, or adds one. Settings and the expense form open this same
 * dialog, so an account added from either place is added the same way.
 *
 * [account] is null when adding. A card Wallet named keeps the bank's digits and
 * kind, and only takes a name; an account you added keeps every field open. Only
 * a new account must be given a name — it would otherwise have nothing to be
 * recognised by — while clearing an existing one's name is how it goes back to
 * showing its digits.
 *
 * [onRemove] is null when the account cannot be removed, and the button is then
 * not offered at all.
 */
@Composable
fun AccountDialog(
    account: Account?,
    onSave: (name: String, type: AccountType, last4: String) -> Unit,
    onDismiss: () -> Unit,
    onRemove: (() -> Unit)? = null,
) {
    var name by remember(account) { mutableStateOf(account?.name.orEmpty()) }
    var last4 by remember(account) { mutableStateOf(account?.last4.orEmpty()) }
    var type by remember(account) { mutableStateOf(account?.type ?: AccountType.CASH) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (account == null) R.string.account_dialog_add_title else R.string.account_dialog_edit_title,
                ),
            )
        },
        text = {
            AccountFields(
                account = account,
                name = name,
                onNameChange = { name = it },
                type = type,
                onTypeChange = { type = it },
                last4 = last4,
                onLast4Change = { typed -> last4 = typed.filter(Char::isDigit).take(LAST4_LENGTH) },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name, type, last4) },
                enabled = account != null || name.isNotBlank(),
            ) {
                Text(stringResource(R.string.account_dialog_save))
            }
        },
        dismissButton = {
            Row {
                onRemove?.let {
                    TextButton(
                        onClick = it,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) {
                        Text(stringResource(R.string.account_dialog_remove))
                    }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.account_dialog_cancel)) }
            }
        },
    )
}

/** The kind, the name, and the digits — the last two locked when Wallet supplied them. */
@Composable
private fun AccountFields(
    account: Account?,
    name: String,
    onNameChange: (String) -> Unit,
    type: AccountType,
    onTypeChange: (AccountType) -> Unit,
    last4: String,
    onLast4Change: (String) -> Unit,
) {
    val fromWallet = account?.origin == AccountOrigin.WALLET

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (fromWallet) {
            Text(
                stringResource(R.string.account_dialog_from_wallet, walletLabel(account)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            AccountTypePicker(type = type, onChange = onTypeChange)
        }
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            label = { Text(stringResource(R.string.account_dialog_name)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            modifier = Modifier.fillMaxWidth(),
        )
        if (fromWallet || type == AccountType.CARD) {
            OutlinedTextField(
                value = last4,
                onValueChange = onLast4Change,
                label = {
                    Text(
                        stringResource(
                            if (fromWallet) R.string.account_dialog_last4_locked else R.string.account_dialog_last4,
                        ),
                    )
                },
                enabled = !fromWallet,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Cash, a card, a bank account: enough to say where money sits without keeping a chart of accounts. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountTypePicker(
    type: AccountType,
    onChange: (AccountType) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        AccountType.entries.forEachIndexed { index, option ->
            SegmentedButton(
                selected = type == option,
                onClick = { onChange(option) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = AccountType.entries.size),
            ) {
                Text(stringResource(accountTypeLabel(option)))
            }
        }
    }
}

/** The words for a kind of account, resolved here because the strings live in this module. */
fun accountTypeLabel(type: AccountType): Int =
    when (type) {
        AccountType.CASH -> R.string.account_type_cash
        AccountType.CARD -> R.string.account_type_card
        AccountType.BANK -> R.string.account_type_bank
    }

/** An account as a list offers it: its name with its digits, or Wallet's own label until it has a name. */
fun accountMenuLabel(account: Account): String =
    account.name?.let { name -> listOfNotNull(name, Formatters.cardMask(account.last4)).joinToString(" ") }
        ?: walletLabel(account)

/** How Wallet itself shows a card: the issuer's product name, then the masked digits. */
fun walletLabel(account: Account): String =
    listOfNotNull(account.issuerLabel, Formatters.cardMask(account.last4)).joinToString(" ")

private const val LAST4_LENGTH = 4
