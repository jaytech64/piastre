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
import io.github.jaytech64.depenses.core.designsystem.cardMenuLabel
import io.github.jaytech64.depenses.core.model.Card
import io.github.jaytech64.depenses.core.model.Payment

/**
 * What an expense was paid with: nothing said, cash, or one of your cards — and
 * a way to add the card that is missing without leaving the form.
 *
 * A captured purchase shows its card instead of offering a choice: the
 * notification already said which card it was.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PaidWithField(
    state: ExpenseFormUiState,
    cards: List<Card>,
    onPick: (Payment?) -> Unit,
    onAddCard: () -> Unit,
) {
    if (state.isCaptured) {
        OutlinedTextField(
            value = state.capturedCardLabel.orEmpty(),
            onValueChange = {},
            readOnly = true,
            enabled = false,
            label = { Text(stringResource(R.string.form_paid_with)) },
            modifier = Modifier.fillMaxWidth(),
        )
        return
    }

    var expanded by remember { mutableStateOf(false) }

    fun pick(payment: Payment?) {
        onPick(payment)
        expanded = false
    }

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = paymentLabel(state.payment, cards),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.form_paid_with)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier =
                Modifier
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.form_paid_with_none)) }, onClick = { pick(null) })
            DropdownMenuItem(
                text = { Text(stringResource(R.string.form_paid_with_cash)) },
                onClick = { pick(Payment.Cash) },
            )
            cards.forEach { card ->
                DropdownMenuItem(text = { Text(cardMenuLabel(card)) }, onClick = { pick(Payment.ByCard(card.id)) })
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.form_add_card)) },
                onClick = {
                    expanded = false
                    onAddCard()
                },
            )
        }
    }
}

@Composable
private fun paymentLabel(
    payment: Payment?,
    cards: List<Card>,
): String =
    when (payment) {
        null -> stringResource(R.string.form_paid_with_none)
        Payment.Cash -> stringResource(R.string.form_paid_with_cash)
        is Payment.ByCard -> cards.firstOrNull { it.id == payment.cardId }?.let(::cardMenuLabel).orEmpty()
    }
