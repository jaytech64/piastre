// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.model

/** Where an account came from: a Wallet notification that named it, or you. */
enum class AccountOrigin { WALLET, USER }

/**
 * What kind of place money sits in.
 *
 * Cash is an account rather than a special case, which is what lets a transfer
 * move money between a wallet and a card the way it moves it between two cards.
 */
enum class AccountType { CASH, CARD, BANK }

/** An account money sits in or moves through, as the ledger knows it. */
data class Account(
    val id: Long,
    /** The name you gave it, or null until you do. A cash account is usually left unnamed. */
    val name: String?,
    val type: AccountType,
    /** The issuer's product name as Wallet reports it, e.g. "Carte d'accès Desrives". Null unless it is a card. */
    val issuerLabel: String?,
    val last4: String?,
    val origin: AccountOrigin,
    /** Confirmed transactions on it, either side of a transfer included. */
    val transactionCount: Int,
    /** What it held before any transaction the ledger knows about; null when nobody said. */
    val openingBalance: Money?,
    /** The opening balance moved by every confirmed transaction on it, in the home currency. */
    val balance: Money,
    /** The category every transfer into it is given, or null. */
    val categoryId: Long? = null,
)
