// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.data

import io.github.jaytech64.depenses.core.database.PartWithCategory
import io.github.jaytech64.depenses.core.database.TransactionPartEntity
import io.github.jaytech64.depenses.core.model.Money
import io.github.jaytech64.depenses.core.model.TransactionPart
import io.github.jaytech64.depenses.core.model.divide

/**
 * Where a saved transaction's category goes: onto the row, or divided among
 * parts, and never both. Everything that reads a category relies on that, since
 * a split whose row also carried one would be counted under it a second time.
 */
internal data class SettledCategory(
    val categoryId: Long?,
    val parts: List<TransactionPart>,
)

/**
 * Settles what a save was given. No part is a transaction that is not split and
 * keeps [categoryId]. A single part divides nothing, so the transaction takes
 * that part's category and is not split either. Two or more must divide
 * [amount] exactly: the form keeps Save out of reach until they do, so parts
 * that do not are a caller's mistake and are refused rather than written.
 */
internal fun settleCategory(
    amount: Money,
    categoryId: Long?,
    parts: List<TransactionPart>,
): SettledCategory =
    when (parts.size) {
        0 -> {
            SettledCategory(categoryId, emptyList())
        }

        1 -> {
            SettledCategory(parts.single().categoryId, emptyList())
        }

        else -> {
            require(parts.divide(amount)) { "the parts of a split must add up to its amount" }
            SettledCategory(null, parts)
        }
    }

/** The row [TransactionPartDao.replace] places under its transaction, in the order given. */
internal fun TransactionPart.toEntity() =
    TransactionPartEntity(
        transactionId = 0,
        position = 0,
        amountMinor = amount.minor,
        categoryId = categoryId,
        note = note?.takeIf { it.isNotBlank() }?.trim(),
    )

internal fun PartWithCategory.toDomain(currency: String) =
    TransactionPart(
        amount = Money(part.amountMinor, currency),
        categoryId = part.categoryId,
        categoryName = categoryName,
        note = part.note,
    )
