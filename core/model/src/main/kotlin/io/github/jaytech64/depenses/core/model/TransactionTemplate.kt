// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.model

/**
 * A transaction you type often, kept to fill the form with. It holds what the
 * transaction is and nothing about when, and writes nothing by itself.
 *
 * [amount] is null when it is different every time.
 */
data class TransactionTemplate(
    val id: Long,
    val name: String,
    val kind: TransactionKind,
    val amount: Money?,
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
)

/** What a template is made of, as typed: what [TransactionTemplate] holds before it has an id. */
data class TemplateContent(
    val name: String,
    val kind: TransactionKind,
    val amount: Money?,
    val merchantName: String?,
    val accountId: Long?,
    val toAccountId: Long?,
    val categoryId: Long?,
    val note: String?,
)
