// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.feature.ledger

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.jaytech64.depenses.core.designsystem.Formatters
import io.github.jaytech64.depenses.core.model.AccountType
import io.github.jaytech64.depenses.core.model.Money
import io.github.jaytech64.depenses.core.model.Transaction
import io.github.jaytech64.depenses.core.model.TransactionKind
import io.github.jaytech64.depenses.core.model.TransactionSource
import java.util.Locale

/**
 * One ledger row: who, when, how it was paid, and how much.
 *
 * A selected row says so twice, with its tint and a check where its initials
 * were, so being picked never rests on colour alone.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TransactionRow(
    transaction: Transaction,
    locale: Locale,
    selection: RowSelection,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val selected = selection == RowSelection.SELECTED
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .combinedClickable(
                onClickLabel = null,
                onLongClickLabel = stringResource(R.string.ledger_select),
                onLongClick = onLongClick,
                onClick = onClick,
            ).then(if (selection == RowSelection.NONE) Modifier else Modifier.semantics { this.selected = selected })
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) {
            SelectedMark()
        } else {
            MerchantInitials(if (transaction.kind == TransactionKind.TRANSFER) ARROW else transaction.displayMerchant)
        }

        Column(Modifier.weight(1f)) {
            Text(
                transaction.title(),
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

        KindAmount(transaction.kind, transaction.amount, locale)
    }
}

/**
 * Income says so twice, with a plus and a colour: a misread sign is a misread
 * balance. A transfer is dimmed and unsigned — the money left one account of
 * yours and arrived in another, so neither sign would be honest.
 */
@Composable
internal fun KindAmount(
    kind: TransactionKind,
    amount: Money,
    locale: Locale,
) {
    when (kind) {
        TransactionKind.INCOME -> {
            Text(
                Formatters.signedMoney(amount, locale),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }

        TransactionKind.TRANSFER -> {
            Text(
                Formatters.money(amount, locale),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        TransactionKind.EXPENSE -> {
            Text(Formatters.money(amount, locale), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** A transfer is between two accounts of yours, and that pair is the only thing worth reading. */
@Composable
private fun Transaction.title(): String =
    when (kind) {
        TransactionKind.TRANSFER -> {
            accountLabel(accountName, accountType, cardLast4) + " $ARROW " +
                accountLabel(toAccountName, toAccountType, toAccountLast4)
        }

        else -> {
            displayMerchant ?: stringResource(R.string.ledger_unknown_merchant)
        }
    }

/** An account as a row names it: your name for it, else its digits, else what kind it is. */
@Composable
internal fun accountLabel(
    name: String?,
    type: AccountType?,
    last4: String?,
): String =
    name
        ?: Formatters.cardMask(last4)
        ?: stringResource(if (type == AccountType.CASH) R.string.ledger_cash else R.string.ledger_account)

/**
 * The time, the category when there is one, then what the row is: a transfer,
 * the account the money came from or went into, or failing both that it was
 * typed by hand. Income is not named here: its plus sign and colour already say
 * so. Enough to tell two purchases at one merchant apart without a detail screen.
 *
 * The category comes before the account so that at a large font size, where the
 * line runs out of room, it is the account that is cut short.
 */
@Composable
private fun Transaction.subtitle(locale: Locale): String {
    val what =
        when {
            kind == TransactionKind.TRANSFER -> stringResource(R.string.ledger_transfer)
            accountId != null -> accountLabel(accountName, accountType, cardLast4)
            source == TransactionSource.MANUAL -> stringResource(R.string.ledger_manual_entry)
            else -> Formatters.cardMask(cardLast4)
        }
    return listOfNotNull(Formatters.time(occurredAt, zoneId, locale), categoryName, what).joinToString(SEPARATOR)
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

@Composable
private fun SelectedMark() {
    Surface(Modifier.size(36.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
        Icon(
            Icons.Default.Check,
            contentDescription = null,
            Modifier.padding(8.dp),
            tint = MaterialTheme.colorScheme.onPrimary,
        )
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

internal const val SEPARATOR = " · "
internal const val ARROW = "→"
