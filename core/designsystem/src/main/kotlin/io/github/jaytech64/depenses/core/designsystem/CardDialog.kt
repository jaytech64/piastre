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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import io.github.jaytech64.depenses.core.model.Card
import io.github.jaytech64.depenses.core.model.CardOrigin

/**
 * Names a card, or adds one. Settings and the expense form open this same dialog,
 * so a card added from either place is added the same way.
 *
 * [card] is null when adding. A Wallet card's digits are the bank's and stay
 * locked; a card you added keeps both fields open. Only a new card must be given
 * a name — it would otherwise have nothing to be recognised by — while clearing
 * an existing card's name is how it goes back to showing its digits.
 *
 * [onRemove] is null when the card cannot be removed, and the button is then not
 * offered at all.
 */
@Composable
fun CardDialog(
    card: Card?,
    onSave: (name: String, last4: String) -> Unit,
    onDismiss: () -> Unit,
    onRemove: (() -> Unit)? = null,
) {
    var name by remember(card) { mutableStateOf(card?.name.orEmpty()) }
    var last4 by remember(card) { mutableStateOf(card?.last4.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (card == null) R.string.card_dialog_add_title else R.string.card_dialog_edit_title))
        },
        text = {
            CardFields(
                card = card,
                name = name,
                onNameChange = { name = it },
                last4 = last4,
                onLast4Change = { typed -> last4 = typed.filter(Char::isDigit).take(LAST4_LENGTH) },
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(name, last4) }, enabled = card != null || name.isNotBlank()) {
                Text(stringResource(R.string.card_dialog_save))
            }
        },
        dismissButton = {
            Row {
                onRemove?.let {
                    TextButton(
                        onClick = it,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) {
                        Text(stringResource(R.string.card_dialog_remove))
                    }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.card_dialog_cancel)) }
            }
        },
    )
}

/** The name, and the digits — locked when Wallet supplied them. */
@Composable
private fun CardFields(
    card: Card?,
    name: String,
    onNameChange: (String) -> Unit,
    last4: String,
    onLast4Change: (String) -> Unit,
) {
    val fromWallet = card?.origin == CardOrigin.WALLET
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (fromWallet) {
            Text(
                stringResource(R.string.card_dialog_from_wallet, walletLabel(card)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            label = { Text(stringResource(R.string.card_dialog_name)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = last4,
            onValueChange = onLast4Change,
            label = {
                Text(
                    stringResource(
                        if (fromWallet) R.string.card_dialog_last4_locked else R.string.card_dialog_last4,
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

/** A card as a list offers it: its name with its digits, or Wallet's own label until it has a name. */
fun cardMenuLabel(card: Card): String =
    card.name?.let { name -> listOfNotNull(name, Formatters.cardMask(card.last4)).joinToString(" ") }
        ?: walletLabel(card)

/** How Wallet itself shows the card: the issuer's product name, then the masked digits. */
fun walletLabel(card: Card): String = listOfNotNull(card.issuerLabel, Formatters.cardMask(card.last4)).joinToString(" ")

private const val LAST4_LENGTH = 4
