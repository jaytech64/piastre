// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.model

import java.time.LocalDate

/**
 * A transaction that comes back on the days [recurrence] falls on, written into
 * the ledger as each one comes.
 *
 * [nextDue] is the next day a transaction will be written for, or null once the
 * recurrence has ended.
 */
data class RecurringTransaction(
    val id: Long,
    val amount: Money,
    val kind: TransactionKind,
    val merchantId: Long?,
    val merchantName: String?,
    val accountId: Long?,
    val accountName: String?,
    val accountType: AccountType?,
    val toAccountId: Long?,
    val toAccountName: String?,
    val toAccountType: AccountType?,
    val categoryId: Long?,
    val categoryName: String?,
    val note: String?,
    val recurrence: Recurrence,
    val nextDue: LocalDate?,
)
