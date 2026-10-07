// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.model

/**
 * One share of a split transaction's amount, and what that share was for.
 *
 * A split is still one transaction: the amount, the account, the day and the
 * merchant are the transaction's, and every balance counts it whole. Its parts
 * only say which categories the amount went to, so only what reads a category
 * reads them (ADR-0018).
 *
 * [categoryId] is null for a share nobody has said anything about yet.
 *
 * A part with [toAccountId] is a transfer: that share left the transaction's
 * account for this one, as the capital of a mortgage payment does while its
 * interest is spent (ADR-0019). It keeps a category like any other part.
 */
data class TransactionPart(
    val amount: Money,
    val categoryId: Long? = null,
    val categoryName: String? = null,
    val note: String? = null,
    val toAccountId: Long? = null,
    val toAccountName: String? = null,
    val toAccountLast4: String? = null,
    val toAccountType: AccountType? = null,
) {
    val isTransfer: Boolean get() = toAccountId != null
}

/**
 * What these parts leave of [amount] still to divide: zero once they add up,
 * and negative by what they exceed it.
 */
fun List<TransactionPart>.leftOf(amount: Money): Money =
    Money(amount.minor - sumOf { it.amount.minor }, amount.currency)

/**
 * Whether these parts are a split of [amount]: at least two, each a real share
 * in the amount's own currency, adding up to it to the minor unit. A single
 * part divides nothing, and one of zero says nothing.
 */
fun List<TransactionPart>.divide(amount: Money): Boolean =
    size >= FEWEST_PARTS &&
        all { it.amount.currency == amount.currency && it.amount.minor > 0 } &&
        leftOf(amount).minor == 0L

/**
 * Whether the parts that go into another account can, on a transaction of
 * [kind] from [accountId]. Only a share of an expense moves on: a transfer
 * already has where it goes, and a share of income landing elsewhere is income
 * into two accounts, not a transfer. It leaves an account that is known for a
 * different one, since a transfer into the account it came from is a typo.
 */
fun List<TransactionPart>.canMoveOnFrom(
    kind: TransactionKind,
    accountId: Long?,
): Boolean =
    none { it.isTransfer } ||
        (kind == TransactionKind.EXPENSE && accountId != null && none { it.toAccountId == accountId })

private const val FEWEST_PARTS = 2
