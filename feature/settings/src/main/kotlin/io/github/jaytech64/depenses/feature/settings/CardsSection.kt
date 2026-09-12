// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jaytech64.depenses.core.designsystem.CardDialog
import io.github.jaytech64.depenses.core.designsystem.walletLabel
import io.github.jaytech64.depenses.core.model.Card
import io.github.jaytech64.depenses.core.model.CardOrigin

/**
 * Every card you pay with, named or still waiting for a name. The name given
 * here is what the ledger shows beside each purchase made with the card.
 */
@Composable
internal fun CardsSection(viewModel: CardsViewModel = hiltViewModel()) {
    val cards by viewModel.cards.collectAsStateWithLifecycle()
    val removalRefused by viewModel.removalRefused.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<CardEdit?>(null) }

    SectionHeader(stringResource(R.string.settings_cards_section))

    if (cards.isEmpty()) {
        Text(
            stringResource(R.string.settings_cards_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
    cards.forEach { card -> CardRow(card, onClick = { editing = CardEdit.Existing(card) }) }

    TextButton(onClick = { editing = CardEdit.New }, modifier = Modifier.padding(horizontal = 4.dp)) {
        Text(stringResource(R.string.settings_cards_add))
    }

    if (removalRefused) {
        Text(
            stringResource(R.string.settings_cards_still_used),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }

    editing?.let { edit ->
        val card = (edit as? CardEdit.Existing)?.card
        CardDialog(
            card = card,
            onSave = { name, last4 ->
                viewModel.save(card, name, last4)
                editing = null
            },
            onDismiss = { editing = null },
            onRemove =
                card?.takeIf { it.purchaseCount == 0 }?.let { removable ->
                    {
                        viewModel.remove(removable)
                        editing = null
                    }
                },
        )
    }
}

private sealed interface CardEdit {
    data object New : CardEdit

    data class Existing(
        val card: Card,
    ) : CardEdit
}

/**
 * A named card leads with its name; one still waiting for a name leads with how
 * Wallet shows it, and says it can be named.
 */
@Composable
private fun CardRow(
    card: Card,
    onClick: () -> Unit,
) {
    val name = card.name
    val digits = walletLabel(card)
    val purchases = pluralStringResource(R.plurals.settings_cards_purchases, card.purchaseCount, card.purchaseCount)
    val addedByHand = stringResource(R.string.settings_cards_added).takeIf { card.origin == CardOrigin.USER }

    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (name != null) {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            Text(
                listOfNotNull(digits.ifEmpty { null }, purchases, addedByHand).joinToString(SEPARATOR),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(digits, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                listOf(stringResource(R.string.settings_cards_unnamed), purchases).joinToString(SEPARATOR),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

private const val SEPARATOR = " · "
