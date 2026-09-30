// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.feature.ledger

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType

/**
 * *At least* and *At most*, each of which can be left empty, read the way the
 * form reads an amount: `12,50` and `12.50` alike.
 *
 * The text is held here as well as in the view model, which only echoes it back
 * after a round through the ledger's flows: a field fed from that echo loses
 * keystrokes typed faster than it arrives.
 */
@Composable
internal fun AmountFields(
    state: LedgerUiState,
    actions: FilterActions,
) {
    var atLeast by rememberSaveable { mutableStateOf(state.typedAtLeast) }
    var atMost by rememberSaveable { mutableStateOf(state.typedAtMost) }

    FieldPair(
        first = { modifier ->
            AmountField(R.string.ledger_filter_at_least, atLeast, state.amountsCrossed, modifier) {
                atLeast = it
                actions.onAtLeast(it)
            }
        },
        second = { modifier ->
            AmountField(R.string.ledger_filter_at_most, atMost, isError = false, modifier) {
                atMost = it
                actions.onAtMost(it)
            }
        },
    )
}

@Composable
private fun AmountField(
    label: Int,
    text: String,
    isError: Boolean,
    modifier: Modifier,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = text,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        isError = isError,
        supportingText =
            if (isError) {
                { Text(stringResource(R.string.ledger_filter_amounts_crossed)) }
            } else {
                null
            },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
        singleLine = true,
        modifier = modifier,
    )
}
