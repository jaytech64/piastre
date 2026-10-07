// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.data

import io.github.jaytech64.depenses.core.model.TransactionKind

/**
 * A split is refused unless it is one the app itself would have written: two
 * parts or more, each a real share, adding up to the amount, on a transaction
 * with no category of its own. Anything else would put a total out by the
 * difference, or count the transaction under a category twice.
 *
 * A part that goes into another account is held to what a save is: an account
 * the file lists, other than the one the transaction came from, on an expense.
 */
internal fun ExportedTransaction.checkParts(accountIds: Set<Long>) {
    if (parts.isEmpty()) return
    val problem =
        when {
            parts.size < 2 -> "a single part"
            parts.any { it.amountMinor <= 0 } -> "a part of nothing"
            parts.sumOf { it.amountMinor } != amountMinor -> "parts that do not add up to its amount"
            categoryId != null -> "both parts and a category of its own"
            else -> movedOnProblem(accountIds) ?: return
        }
    inconsistent("transaction $sourceKey has $problem")
}

private fun ExportedTransaction.movedOnProblem(accountIds: Set<Long>): String? {
    val destinations = parts.mapNotNull { it.toAccountId }
    return when {
        destinations.isEmpty() -> null
        destinations.any { it !in accountIds } -> "a part into an account not in the file"
        kind != TransactionKind.EXPENSE.name -> "a part into another account, and is not an expense"
        accountId == null -> "a part into another account, and no account of its own"
        accountId in destinations -> "a part into the account it came from"
        else -> null
    }
}
