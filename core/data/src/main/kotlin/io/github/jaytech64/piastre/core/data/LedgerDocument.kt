// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.core.data

import io.github.jaytech64.piastre.core.model.TransactionKind
import io.github.jaytech64.piastre.core.model.TransactionSource
import kotlinx.serialization.Serializable

/**
 * Bumped whenever a field is removed or changes meaning. A reader that does not
 * recognise the number refuses rather than guesses.
 *
 * Version 2 dropped `paymentMethod`, because cash became an account, and renamed
 * the card list to accounts: a version-1 reader must refuse the file rather than
 * guess at what it no longer says. Fields added since — an account's opening
 * balance, its place in your order, categories and their groups, recurring transactions, and
 * templates — left it at 2, and default to null or empty for a file written before them. A reader
 * from before recurring transactions refuses a file holding a row one wrote, on
 * its source, rather than guessing what that source means.
 *
 * Version 3 lets a transaction be divided into parts, and on such a transaction
 * a null `categoryId` stopped meaning that nobody has said what it was for. A
 * version-2 reader skips keys it does not know, so it would have imported a
 * split as an uncategorised purchase without a word; the new number makes it
 * refuse instead. Nothing was removed, so a version-2 file is still read.
 *
 * Version 4 lets a part go into another account, which changes what the part
 * is worth and what that account holds. A version-3 reader would have imported
 * it as an ordinary part and left the account short without a word, so it is
 * made to refuse too. Nothing was removed, so a version-3 file is still read.
 *
 * Version 5 gives a merchant a category for its incomes apart from the one for
 * its expenses, and a merchant's `categoryId` stopped meaning both. A version-4
 * reader would have given the expense category to incomes too, so it is made to
 * refuse; an older file is still read, its one category going where
 * [ExportedMerchant.categoryFor] says.
 */
internal const val LEDGER_FORMAT_VERSION = 5

/** The first version in which a merchant's `categoryId` is for its expenses alone. */
internal const val MERCHANT_CATEGORY_BY_KIND_VERSION = 5

/** The versions [decodeExport] reads: this one, and those before it, which only lack what was added since. */
internal val READABLE_FORMAT_VERSIONS = setOf(2, 3, 4, LEDGER_FORMAT_VERSION)

/**
 * The JSON export, and what an import reads back.
 *
 * Ids are references inside the file, so merchants, aliases and accounts can be
 * reattached on the way back in; they are never database ids on the other side.
 * `sourcePackage` with `sourceKey` are kept because together they are the
 * identity an import matches on to avoid duplicating a purchase.
 */
@Serializable
internal data class ExportDocument(
    val version: Int,
    val exportedAtUtc: String,
    val merchants: List<ExportedMerchant>,
    val aliases: List<ExportedAlias>,
    val accounts: List<ExportedAccount>,
    val transactions: List<ExportedTransaction>,
    val categories: List<ExportedCategory> = emptyList(),
    val categoryGroups: List<ExportedCategoryGroup> = emptyList(),
    val recurring: List<ExportedRecurring> = emptyList(),
    val templates: List<ExportedTemplate> = emptyList(),
    val budgets: List<ExportedBudget> = emptyList(),
)

/**
 * A recurring transaction. It arrived within version 2, so a file written before
 * it has none.
 *
 * [uuid] is its identity on any phone: an import that finds it already there adds
 * nothing. [nextDue] travels with it, so a phone that imports it goes on from the
 * day the exporting one had reached rather than writing again the days whose rows
 * are in the file. Days are ISO dates, `2026-09-25`, so the file reads as it means.
 */
@Serializable
internal data class ExportedRecurring(
    val id: Long,
    val uuid: String,
    val amountMinor: Long,
    val currency: String,
    val kind: String,
    val merchantId: Long?,
    val merchantName: String?,
    val merchantRaw: String?,
    val accountId: Long?,
    val toAccountId: Long?,
    val categoryId: Long?,
    val note: String?,
    val rhythm: String,
    val firstDate: String,
    val lastDate: String?,
    val nextDue: String?,
)

/**
 * A template. It arrived within version 2, so a file written before it has none.
 *
 * [uuid] is its identity on any phone: an import that finds it already there
 * leaves yours as it is. [amountMinor] is null when the template holds no amount.
 * [recurring] arrived later, and a file written before it has templates that are not.
 */
@Serializable
internal data class ExportedTemplate(
    val id: Long,
    val uuid: String,
    val name: String,
    val kind: String,
    val amountMinor: Long?,
    val currency: String,
    val merchantId: Long?,
    val merchantName: String?,
    val merchantRaw: String?,
    val accountId: Long?,
    val toAccountId: Long?,
    val categoryId: Long?,
    val note: String?,
    val lastUsedAtUtcMillis: Long?,
    val recurring: Boolean = false,
)

/**
 * A category you named. It arrived within version 2, so a file written before it
 * has none; [groupId] arrived later still, and is null in a file written before groups.
 *
 * [icon] arrived within version 4 and is the icon's name, null in a file written
 * before icons. It did not take a new version: a reader that skips it shows the
 * category's initials, and every amount in the file means what it meant.
 */
@Serializable
internal data class ExportedCategory(
    val id: Long,
    val name: String,
    val groupId: Long? = null,
    val icon: String? = null,
)

/**
 * A budget set, changed or stopped on a category or group of the file from [from]
 * on, as `2026-10`; [amountMinor] is null for a stop. It arrived within version 4
 * and did not take a new version: a reader that skips it loses the budgets, and
 * every amount in the file means what it meant.
 */
@Serializable
internal data class ExportedBudget(
    val targetKind: String,
    val targetId: Long,
    val from: String,
    val amountMinor: Long?,
    val currency: String,
)

/** A group categories are arranged under. It arrived within version 2, so a file written before it has none. */
@Serializable
internal data class ExportedCategoryGroup(
    val id: Long,
    val name: String,
)

/**
 * An account, with the name you gave it — which is data about you, so it leaves with the rest.
 *
 * [openingBalanceMinor] arrived within version 2 and stays null when none was given, so that a
 * reader can tell a balance nobody stated from one set to zero.
 *
 * [sortOrder] is where you put the account in your list, which you set, so it leaves with the
 * rest. It arrived within version 2 too, and is null in a file written before it.
 *
 * [categoryId] is the category every transfer into the account is given, when you set one.
 */
@Serializable
internal data class ExportedAccount(
    val id: Long,
    val name: String?,
    val type: String,
    val issuerLabel: String?,
    val last4: String?,
    val origin: String,
    val openingBalanceMinor: Long? = null,
    val sortOrder: Int? = null,
    val categoryId: Long? = null,
)

/**
 * [categoryId] is the category every expense there is given, and
 * [incomeCategoryId] every income, when you set one. Before version 5 there was
 * one [categoryId] for both, and no [incomeCategoryId].
 */
@Serializable
internal data class ExportedMerchant(
    val id: Long,
    val displayName: String,
    val normalisedName: String,
    val categoryId: Long? = null,
    val incomeCategoryId: Long? = null,
)

/**
 * The category, in the file, this merchant gives a [kind] transaction.
 *
 * A file before version 5 has one category for both kinds, so it goes where
 * schema 21's migration sends a merchant's: to incomes when only the file's
 * incomes there carry it, and to expenses otherwise. A captured row is no
 * evidence, its category having been copied from the merchant.
 */
internal fun ExportedMerchant.categoryFor(
    kind: TransactionKind,
    document: ExportDocument,
): Long? {
    if (document.version >= MERCHANT_CATEGORY_BY_KIND_VERSION) {
        return when (kind) {
            TransactionKind.EXPENSE -> categoryId
            TransactionKind.INCOME -> incomeCategoryId
            TransactionKind.TRANSFER -> null
        }
    }
    val carriers =
        document.transactions
            .filter {
                it.merchantId == id &&
                    it.categoryId == categoryId &&
                    it.source != TransactionSource.NOTIFICATION.name
            }.map { it.kind }
            .toSet()
    val forIncome = carriers == setOf(TransactionKind.INCOME.name)
    return when (kind) {
        TransactionKind.EXPENSE -> categoryId.takeUnless { forIncome }
        TransactionKind.INCOME -> categoryId.takeIf { forIncome }
        TransactionKind.TRANSFER -> null
    }
}

@Serializable
internal data class ExportedAlias(
    val id: Long,
    val merchantId: Long,
    val pattern: String,
    val matchType: String,
    val createdBy: String,
)

@Serializable
internal data class ExportedTransaction(
    val id: Long,
    val amountMinor: Long,
    val currency: String,
    val occurredAtUtcMillis: Long,
    val zoneId: String,
    val merchantId: Long?,
    val merchantName: String?,
    val merchantRaw: String?,
    val source: String,
    val status: String,
    val confidence: Float,
    val parserId: String?,
    val parserVersion: Int?,
    val sourcePackage: String,
    val sourceKey: String,
    val cardLabel: String?,
    val cardLast4: String?,
    val note: String?,
    val kind: String,
    val accountId: Long?,
    val accountName: String?,
    val toAccountId: Long?,
    val toAccountName: String?,
    val categoryId: Long? = null,
    val categoryName: String? = null,
    /** The recurring transaction in the file this belongs to, when it does. */
    val recurringId: Long? = null,
    /** What the amount is divided into, in order; empty unless it is split, and then [categoryId] is null. */
    val parts: List<ExportedPart> = emptyList(),
)

/**
 * One share of a split transaction's amount, in that transaction's currency, and
 * what it was for. [toAccountId] is the account in the file the share went into,
 * when it is a transfer.
 */
@Serializable
internal data class ExportedPart(
    val amountMinor: Long,
    val categoryId: Long? = null,
    val categoryName: String? = null,
    val note: String? = null,
    val toAccountId: Long? = null,
    val toAccountName: String? = null,
)
