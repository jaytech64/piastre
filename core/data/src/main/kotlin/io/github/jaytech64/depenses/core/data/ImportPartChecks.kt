// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.data

/**
 * A split is refused unless it is one the app itself would have written: two
 * parts or more, each a real share, adding up to the amount, on a transaction
 * with no category of its own. Anything else would put a total out by the
 * difference, or count the transaction under a category twice.
 */
internal fun ExportedTransaction.checkParts() {
    if (parts.isEmpty()) return
    val problem =
        when {
            parts.size < 2 -> "a single part"
            parts.any { it.amountMinor <= 0 } -> "a part of nothing"
            parts.sumOf { it.amountMinor } != amountMinor -> "parts that do not add up to its amount"
            categoryId != null -> "both parts and a category of its own"
            else -> return
        }
    inconsistent("transaction $sourceKey has $problem")
}
