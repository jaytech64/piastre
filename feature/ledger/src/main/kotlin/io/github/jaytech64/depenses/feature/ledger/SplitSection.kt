// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.feature.ledger

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jaytech64.depenses.core.designsystem.ButtonsRow
import io.github.jaytech64.depenses.core.designsystem.Formatters
import io.github.jaytech64.depenses.core.designsystem.LastButton
import io.github.jaytech64.depenses.core.model.Account
import io.github.jaytech64.depenses.core.model.Money
import java.text.Collator

/**
 * The parts a split's amount is divided into, where the category field is on a
 * transaction that is not split: each part's category, amount and note, then
 * what is still left to divide, which is what Save waits on. A part added as a
 * transfer leads with the account it goes into, the way a transfer's category
 * comes after its accounts.
 */
@Composable
internal fun SplitSection(
    state: ExpenseFormUiState,
    accounts: List<Account>,
    split: SplitParts,
    picker: CategoryPicker,
    onAddCategory: () -> Unit,
    onAddAccount: () -> Unit,
) {
    val categories by picker.known.collectAsStateWithLifecycle()
    val recent by picker.recent.collectAsStateWithLifecycle()
    val groups by picker.groups.collectAsStateWithLifecycle()
    val locale = currentLocale()
    val sections =
        remember(categories, groups, locale) { categorySections(categories, groups, Collator.getInstance(locale)) }

    Text(stringResource(R.string.form_split_title), style = MaterialTheme.typography.titleSmall)

    state.parts.forEach { part ->
        key(part.key) {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (part.isTransfer) {
                        AccountField(
                            label = R.string.form_to,
                            selectedId = part.toAccountId,
                            accounts = accounts,
                            onPick = { id ->
                                split.onDestinationChange(
                                    part.key,
                                    id,
                                    accounts.firstOrNull { it.id == id }?.categoryId,
                                )
                            },
                            onAddAccount = {
                                split.awaitAccountFor(part.key)
                                onAddAccount()
                            },
                            allowNone = false,
                        )
                    }
                    CategoryField(
                        selectedId = part.categoryId,
                        categories = categories,
                        recent = recent,
                        sections = sections,
                        onPick = { split.onCategoryChange(part.key, it) },
                        onAddCategory = {
                            split.awaitCategoryFor(part.key)
                            onAddCategory()
                        },
                    )
                    PartAmount(
                        part,
                        onChange = { split.onAmountChange(part.key, it) },
                        onRemove = { split.remove(part.key) },
                    )
                    OutlinedTextField(
                        value = part.note,
                        onValueChange = { split.onNoteChange(part.key, it) },
                        label = { Text(stringResource(R.string.manual_note)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }

    state.leftToSplit?.let { LeftToSplit(it) }

    ButtonsRow(Modifier.fillMaxWidth(), last = LastButton.FAR_END) {
        TextButton(onClick = split::add) { Text(stringResource(R.string.form_split_add)) }
        if (state.offersTransferParts) {
            TextButton(onClick = split::addTransfer) { Text(stringResource(R.string.form_split_add_transfer)) }
        }
        TextButton(onClick = split::stop) { Text(stringResource(R.string.form_split_stop)) }
    }
}

/** The part's amount takes the width; taking the part away sits at its end, out of the way of typing. */
@Composable
private fun PartAmount(
    part: PartDraft,
    onChange: (String) -> Unit,
    onRemove: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = part.amountText,
            onValueChange = onChange,
            label = { Text(stringResource(R.string.manual_amount)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            isError = part.amountText.isNotBlank() && part.amount == null,
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onRemove) {
            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.form_split_remove))
        }
    }
}

/** Says nothing once the parts add up: what is left, or what they exceed the amount by, is the only news. */
@Composable
private fun LeftToSplit(left: Money) {
    if (left.minor == 0L) return
    val locale = currentLocale()
    Text(
        if (left.minor > 0) {
            stringResource(R.string.form_split_left, Formatters.money(left, locale))
        } else {
            stringResource(R.string.form_split_over, Formatters.money(-left, locale))
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
    )
}
