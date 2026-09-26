// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.data

import kotlinx.serialization.Serializable

/**
 * Bumped whenever a field is removed or changes meaning. A reader that does not
 * recognise the number refuses rather than guesses.
 *
 * Version 2 dropped `paymentMethod`, because cash became an account, and renamed
 * the card list to accounts: a version-1 reader must refuse the file rather than
 * guess at what it no longer says. Fields added since — an account's opening
 * balance, its place in your order, and categories — left it at 2, and default to
 * null or empty for a file written before them.
 */
internal const val LEDGER_FORMAT_VERSION = 2

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
)

/** A category you named. It arrived within version 2, so a file written before it has none. */
@Serializable
internal data class ExportedCategory(
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

/** [categoryId] is the category every purchase there is given, when you set one. */
@Serializable
internal data class ExportedMerchant(
    val id: Long,
    val displayName: String,
    val normalisedName: String,
    val categoryId: Long? = null,
)

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
)
