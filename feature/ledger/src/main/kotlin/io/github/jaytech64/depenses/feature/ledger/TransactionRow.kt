// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.feature.ledger

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.jaytech64.depenses.core.designsystem.Formatters
import io.github.jaytech64.depenses.core.model.PaymentMethod
import io.github.jaytech64.depenses.core.model.Transaction
import io.github.jaytech64.depenses.core.model.TransactionKind
import io.github.jaytech64.depenses.core.model.TransactionSource
import java.util.Locale

/** One ledger row: who, when, how it was paid, and how much. */

@Composable
internal fun TransactionRow(
    transaction: Transaction,
    locale: Locale,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MerchantInitials(transaction.displayMerchant)

        Column(Modifier.weight(1f)) {
            Text(
                transaction.displayMerchant ?: stringResource(R.string.ledger_unknown_merchant),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                transaction.subtitle(locale),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        TransactionAmount(transaction, locale)
    }
}

/** Income says so twice, with a plus and a colour: a misread sign is a misread balance. */
@Composable
private fun TransactionAmount(
    transaction: Transaction,
    locale: Locale,
) {
    if (transaction.kind == TransactionKind.INCOME) {
        Text(
            Formatters.signedMoney(transaction.amount, locale),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.tertiary,
        )
    } else {
        Text(Formatters.money(transaction.amount, locale), style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * The time, then how it was paid — the card by its name, cash — or failing that
 * whether it was income or typed by hand. Enough to tell two purchases at one merchant apart without a
 * detail screen.
 */
@Composable
private fun Transaction.subtitle(locale: Locale): String {
    val method =
        when {
            kind == TransactionKind.INCOME -> stringResource(R.string.ledger_income)
            paymentMethod == PaymentMethod.CASH -> stringResource(R.string.ledger_cash)
            paymentMethod == PaymentMethod.CARD -> Formatters.cardName(cardName, cardLast4)
            source == TransactionSource.MANUAL -> stringResource(R.string.ledger_manual_entry)
            else -> Formatters.cardMask(cardLast4)
        }
    return listOfNotNull(Formatters.time(occurredAt, zoneId, locale), method).joinToString(SEPARATOR)
}

@Composable
private fun MerchantInitials(merchant: String?) {
    Surface(
        Modifier.size(36.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                merchant.initials(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

private fun String?.initials(): String =
    orEmpty()
        .split(' ')
        .filter { it.isNotBlank() }
        .take(2)
        .map { it.first().uppercaseChar() }
        .joinToString("")
        .ifEmpty { "?" }

private const val SEPARATOR = " · "
