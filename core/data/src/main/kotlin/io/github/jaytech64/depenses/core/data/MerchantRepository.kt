// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.data

import io.github.jaytech64.depenses.core.database.MerchantAliasEntity
import io.github.jaytech64.depenses.core.database.MerchantDao
import io.github.jaytech64.depenses.core.database.MerchantEntity
import io.github.jaytech64.depenses.core.model.AliasOrigin
import io.github.jaytech64.depenses.core.model.MatchType
import io.github.jaytech64.depenses.core.model.Merchant
import io.github.jaytech64.depenses.core.model.MerchantNormaliser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves a payment network's merchant string to a merchant you can rank.
 *
 * The alias is looked up before the normaliser runs, so a merchant you have
 * renamed or re-pointed by hand keeps winning over what the rules would have
 * inferred: a correction made once is never re-derived away.
 */
@Singleton
class MerchantRepository
    @Inject
    constructor(
        private val merchants: MerchantDao,
    ) {
        suspend fun resolve(raw: String?): Long? {
            if (raw.isNullOrBlank()) return null
            merchants.byExactAlias(raw)?.let { return it.id }

            val name = MerchantNormaliser.normalise(raw) ?: return null
            val merchant = findOrCreate(name.displayName, name.normalisedName) ?: return null

            // Recorded even though the rules could re-derive it: the alias is what
            // makes renaming this merchant later a one-row edit rather than a pass
            // over every transaction.
            merchants.insertAlias(
                MerchantAliasEntity(
                    merchantId = merchant.id,
                    pattern = raw,
                    matchType = MatchType.EXACT,
                    createdBy = AliasOrigin.AUTO,
                ),
            )
            return merchant.id
        }

        fun all(): Flow<List<Merchant>> =
            merchants.all().map { rows ->
                rows.map { Merchant(id = it.id, displayName = it.displayName, normalisedName = it.normalisedName) }
            }

        /**
         * The insert ignores a conflict rather than failing, so the re-read is what
         * returns the row — either the one just written or the one another caller
         * wrote first. Both answers are correct; there is only ever one merchant per
         * normalised name.
         */
        private suspend fun findOrCreate(
            displayName: String,
            normalisedName: String,
        ): MerchantEntity? {
            merchants.byNormalisedName(normalisedName)?.let { return it }
            merchants.insert(MerchantEntity(displayName = displayName, normalisedName = normalisedName))
            return merchants.byNormalisedName(normalisedName)
        }
    }
