// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.data

import io.github.jaytech64.depenses.core.database.CardDao
import io.github.jaytech64.depenses.core.database.CardEntity
import io.github.jaytech64.depenses.core.database.CardWithCount
import io.github.jaytech64.depenses.core.model.Card
import io.github.jaytech64.depenses.core.model.CardOrigin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The cards you pay with: the ones Wallet names in its notifications, found or
 * created as purchases arrive, and the ones you add for a card Wallet never sees.
 */
@Singleton
class CardRepository
    @Inject
    constructor(
        private val cards: CardDao,
    ) {
        /**
         * The card a Wallet notification names, created the first time it is seen.
         *
         * Null when the notification carried no digits: without them one card
         * cannot be told from another, and guessing would merge two.
         */
        suspend fun resolve(
            issuerLabel: String?,
            last4: String?,
        ): Long? {
            if (last4.isNullOrBlank()) return null
            cards.walletCard(issuerLabel, last4)?.let { return it.id }
            cards.insert(CardEntity(name = null, issuerLabel = issuerLabel, last4 = last4, origin = CardOrigin.WALLET))
            return cards.walletCard(issuerLabel, last4)?.id
        }

        fun all(): Flow<List<Card>> = cards.allWithCounts().map { rows -> rows.map(CardWithCount::toDomain) }

        /** A blank name clears it, and the card goes back to showing its digits. */
        suspend fun rename(
            id: Long,
            name: String?,
        ) = cards.rename(id, name.cleaned())

        suspend fun add(
            name: String?,
            last4: String?,
        ): Long =
            cards.insert(
                CardEntity(
                    name = name.cleaned(),
                    issuerLabel = null,
                    last4 = last4.cleaned(),
                    origin = CardOrigin.USER,
                ),
            )

        /** Changes the name and digits of a card you added. A Wallet card only takes [rename]. */
        suspend fun updateAdded(
            id: Long,
            name: String?,
            last4: String?,
        ) = cards.updateUserCard(id, name.cleaned(), last4.cleaned())

        /** False when a transaction still uses the card, which then stays. */
        suspend fun remove(id: Long): Boolean = cards.deleteIfUnused(id) == 1
    }

private fun String?.cleaned(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

private fun CardWithCount.toDomain(): Card =
    Card(
        id = card.id,
        name = card.name,
        issuerLabel = card.issuerLabel,
        last4 = card.last4,
        origin = card.origin,
        purchaseCount = purchaseCount,
    )
