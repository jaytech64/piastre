// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.feature.ledger

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType

/** What the form is about, and — on a new transaction — the way to the templates that can fill it. */
@Composable
internal fun FormTitle(
    state: ExpenseFormUiState,
    onOpenTemplates: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(state.title()),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
        )
        if (state.offersTemplates) {
            IconButton(onClick = onOpenTemplates) {
                Icon(
                    painterResource(R.drawable.ic_template),
                    contentDescription = stringResource(R.string.templates_title),
                )
            }
        }
    }
}

@Composable
internal fun TemplateNameField(
    name: String,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = name,
        onValueChange = onChange,
        label = { Text(stringResource(R.string.template_name)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Marked wrong only once something is typed that is not an amount: empty is not an error, it is not done yet. */
@Composable
internal fun AmountField(
    state: ExpenseFormUiState,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = state.amountText,
        onValueChange = onChange,
        label = { Text(stringResource(if (state.isTemplate) R.string.template_amount else R.string.manual_amount)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        isError = state.amountText.isNotBlank() && state.amount == null,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun ExpenseFormUiState.title(): Int =
    when {
        isTemplate -> R.string.template_title
        isSchedule -> R.string.recurring_edit_title
        isEditing -> R.string.edit_title
        else -> R.string.manual_title
    }
