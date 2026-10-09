// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.feature.ledger

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.jaytech64.piastre.core.designsystem.Formatters
import io.github.jaytech64.piastre.core.designsystem.Mark
import io.github.jaytech64.piastre.core.designsystem.categoryHalfMark
import io.github.jaytech64.piastre.core.designsystem.categoryMark
import io.github.jaytech64.piastre.core.designsystem.initials
import io.github.jaytech64.piastre.core.model.AccountType
import io.github.jaytech64.piastre.core.model.Transaction
import io.github.jaytech64.piastre.core.model.TransactionKind
import io.github.jaytech64.piastre.core.model.TransactionPart

/**
 * What the row is for leads: its category, so two bills from one merchant read
 * apart at a glance. A row nobody has given a category leads with its place.
 */
@Composable
internal fun Transaction.title(): String = categoryTitle() ?: place()

/** The place under the category, when the category has taken the title; nothing more to say otherwise. */
@Composable
internal fun Transaction.placeUnderCategory(): String? = categoryTitle()?.let { place() }

/**
 * The category as the title has it. A split has several, so it names each one
 * its parts carry, in the order they were typed; a part with none adds nothing.
 */
private fun Transaction.categoryTitle(): String? =
    if (isSplit) {
        parts
            .mapNotNull { it.categoryName }
            .distinct()
            .joinToString(SEPARATOR)
            .ifEmpty { null }
    } else {
        categoryName
    }

/**
 * What stands in the circle a row starts with: its category's icon, or that
 * category's initials while it has none; else its merchant's initials, or an
 * arrow for a transfer.
 *
 * A split in two shows both of its categories, the first part's in the upper
 * left. With more parts than that, or with two that nobody has given a
 * category, it only shows that it is a split.
 */
internal fun Transaction.mark(): Mark =
    when {
        isSplit -> splitMark()
        else -> categoryName?.let { categoryMark(it, categoryIcon) } ?: Mark.Letters(placeLetters())
    }

private fun Transaction.placeLetters(): String =
    if (kind ==
        TransactionKind.TRANSFER
    ) {
        ARROW
    } else {
        displayMerchant.initials()
    }

private fun Transaction.splitMark(): Mark {
    if (parts.size != 2) return Mark.Split
    val (first, second) = parts
    return when {
        first.categoryId != second.categoryId -> Mark.Halves(first.halfMark(), second.halfMark())
        else -> first.categoryName?.let { categoryMark(it, first.categoryIcon) } ?: Mark.Split
    }
}

private fun TransactionPart.halfMark(): Mark.Single? = categoryName?.let { categoryHalfMark(it, categoryIcon) }

/** Where the money went: the merchant, or — on a transfer — the two accounts of yours it moved between. */
@Composable
private fun Transaction.place(): String =
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
