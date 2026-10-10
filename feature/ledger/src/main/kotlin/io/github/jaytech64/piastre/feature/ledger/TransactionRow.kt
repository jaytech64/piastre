// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.feature.ledger

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import io.github.jaytech64.piastre.core.designsystem.Formatters
import io.github.jaytech64.piastre.core.designsystem.MARK_SIZE
import io.github.jaytech64.piastre.core.designsystem.MarkCircle
import io.github.jaytech64.piastre.core.model.Money
import io.github.jaytech64.piastre.core.model.Transaction
import io.github.jaytech64.piastre.core.model.TransactionKind
import io.github.jaytech64.piastre.core.model.TransactionSource
import java.util.Locale

/**
 * One ledger row: who, when, how it was paid, and how much.
 *
 * A selected row says so twice, with its tint and a check where its initials
 * were, so being picked never rests on colour alone.
 *
 * [whole] is given for a split shown for one of its categories: the amount is
 * then that category's share, and the whole it is a share of is said under it.
 *
 * [onEdit] is given while the ledger is selecting, when a tap picks the row
 * instead of opening it: the row then ends with the button that still opens it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TransactionRow(
    transaction: Transaction,
    locale: Locale,
    selection: RowSelection,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    dayLabel: String? = null,
    whole: Money? = null,
    onEdit: (() -> Unit)? = null,
) {
    val selected = selection == RowSelection.SELECTED
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .combinedClickable(
                onClickLabel = null,
                onLongClickLabel = onLongClick?.let { stringResource(R.string.ledger_select) },
                onLongClick = onLongClick,
                onClick = onClick,
            ).then(if (selection == RowSelection.NONE) Modifier else Modifier.semantics { this.selected = selected })
            .padding(start = 16.dp, end = if (onEdit == null) 16.dp else EDIT_BUTTON_END, top = 10.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) {
            SelectedMark()
        } else {
            MarkCircle(transaction.mark())
        }

        Column(Modifier.weight(1f)) {
            // A line above today wraps rather than cuts: its day is the point of it,
            // and at a large font size a cut would take the day first.
            val lines = if (dayLabel == null) 1 else Int.MAX_VALUE
            Text(
                transaction.title(),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = lines,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                dayLabel?.let { transaction.upcomingSubtitle(it) } ?: transaction.subtitle(locale),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = lines,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Column(horizontalAlignment = Alignment.End) {
            KindAmount(transaction.kind, transaction.amount, locale)
            whole?.let {
                Text(
                    stringResource(R.string.ledger_split_of, Formatters.money(it, locale)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        onEdit?.let { EditButton(it) }
    }
}

/**
 * Opens the row while a tap on it selects. It keeps its touch target and lays
 * out no taller than the row's mark, so a row does not grow, and the list does
 * not move under the finger, when a selection starts.
 */
@Composable
private fun EditButton(onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.height(ROW_MARK).wrapContentHeight(unbounded = true),
    ) {
        Icon(
            Icons.Default.Edit,
            contentDescription = stringResource(R.string.edit_title),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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

/**
 * The time, or that it recurs, the merchant when the category has taken the
 * title, then what the row is: a transfer, the account the money came from or
 * went into, or failing both that it was typed by hand. Income is not named
 * here: its plus sign and colour already say so.
 *
 * The merchant comes before the account so that at a large font size, where the
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
    return listOfNotNull(whenLabel(locale), placeUnderCategory(), what).joinToString(SEPARATOR)
}

/**
 * For a line above today: its day where the time would be, since there is no day
 * heading over it, then the merchant when the category has taken the title, then
 * that it recurs or where it is paid from.
 */
@Composable
private fun Transaction.upcomingSubtitle(dayLabel: String): String {
    val what =
        when {
            source == TransactionSource.RECURRING -> stringResource(R.string.ledger_recurring)
            kind == TransactionKind.TRANSFER -> stringResource(R.string.ledger_transfer)
            accountId != null -> accountLabel(accountName, accountType, cardLast4)
            else -> stringResource(R.string.ledger_manual_entry)
        }
    return listOfNotNull(dayLabel, placeUnderCategory(), what).joinToString(SEPARATOR)
}

/**
 * The time of day, except on a row a recurring transaction wrote: it fell due on
 * a day, not at an hour, so it says why it is there instead of inventing one.
 */
@Composable
private fun Transaction.whenLabel(locale: Locale): String =
    if (source == TransactionSource.RECURRING) {
        stringResource(R.string.ledger_recurring)
    } else {
        Formatters.time(occurredAt, zoneId, locale)
    }

@Composable
private fun SelectedMark() {
    Surface(Modifier.size(ROW_MARK), shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
        Icon(
            Icons.Default.Check,
            contentDescription = null,
            Modifier.padding(8.dp),
            tint = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

/** The circle a row starts with. */
internal val ROW_MARK = MARK_SIZE

/** What is left of the row's 16 dp margin once the edit button's own inset around its icon is counted. */
private val EDIT_BUTTON_END = 4.dp

internal const val SEPARATOR = " · "
internal const val ARROW = "→"
