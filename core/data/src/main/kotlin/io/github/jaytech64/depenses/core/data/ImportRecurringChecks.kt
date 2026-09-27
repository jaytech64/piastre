// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.data

import io.github.jaytech64.depenses.core.model.Rhythm
import io.github.jaytech64.depenses.core.model.TransactionKind
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * Every recurring transaction in the file is whole and points only at what the
 * file lists, and none appears twice; returns their ids.
 */
internal fun ExportDocument.checkRecurring(
    accountIds: Set<Long>,
    merchantIds: Set<Long>,
): Set<Long> {
    val ids = recurring.map { it.id }.toSet()
    if (ids.size != recurring.size) inconsistent("two recurring transactions share an id")
    if (recurring.map { it.uuid }.toSet().size != recurring.size) inconsistent("a recurring transaction appears twice")
    recurring.forEach {
        it.checkCodes()
        it.checkReferences(accountIds, merchantIds)
    }
    return ids
}

private fun ExportedRecurring.checkCodes() {
    val problem =
        when {
            rhythm !in Rhythm.entries.names() -> "rhythm '$rhythm'"
            kind !in TransactionKind.entries.names() -> "kind '$kind'"
            currency.length != CURRENCY_CODE_LENGTH -> "currency '$currency'"
            listOfNotNull(firstDate, lastDate, nextDue).any { day(it) == null } -> "a date that is not a day"
            endsBeforeItStarts -> "an end before its start"
            else -> return
        }
    inconsistent("recurring transaction $id has $problem")
}

private fun ExportedRecurring.checkReferences(
    accountIds: Set<Long>,
    merchantIds: Set<Long>,
) {
    val problem =
        when {
            accountId != null && accountId !in accountIds -> "an account not in the file"
            toAccountId != null && toAccountId !in accountIds -> "a destination account not in the file"
            merchantId != null && merchantId !in merchantIds -> "a merchant not in the file"
            else -> return
        }
    inconsistent("recurring transaction $id has $problem")
}

private val ExportedRecurring.endsBeforeItStarts: Boolean
    get() {
        val first = day(firstDate) ?: return false
        val last = lastDate?.let(::day) ?: return false
        return last < first
    }

private fun day(text: String): LocalDate? =
    try {
        LocalDate.parse(text)
    } catch (expected: DateTimeParseException) {
        null
    }
