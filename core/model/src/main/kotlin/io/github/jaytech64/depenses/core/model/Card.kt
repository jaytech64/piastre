// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.model

/** Where a card came from: a Wallet notification that named it, or you. */
enum class CardOrigin { WALLET, USER }

/** A card you pay with, as the ledger knows it. */
data class Card(
    val id: Long,
    /** The name you gave it, or null until you do. */
    val name: String?,
    /** The issuer's product name as Wallet reports it, e.g. "Carte d'accès Desrives". Null for a card you added. */
    val issuerLabel: String?,
    val last4: String?,
    val origin: CardOrigin,
    /** Confirmed transactions made with it. */
    val purchaseCount: Int,
)

/** What a manual entry says it was paid with. */
sealed interface Payment {
    data object Cash : Payment

    data class ByCard(
        val cardId: Long,
    ) : Payment
}
