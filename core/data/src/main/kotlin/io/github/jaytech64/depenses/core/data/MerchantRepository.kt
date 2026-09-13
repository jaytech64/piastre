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

        /**
         * Points a raw merchant string at a merchant you named, and remembers it.
         *
         * The alias is written with [AliasOrigin.USER] and replaces whatever the
         * normaliser had inferred, so the next notification carrying the same string
         * resolves the way you corrected it rather than the way the rules read it.
         * Correcting one purchase is how the app learns a merchant.
         *
         * The merchant is also renamed to what you typed. Matching folds case and
         * accents, so `MCDONALD'S` and `McDonald's` are one merchant — and without
         * this, retyping the capitalisation would find that merchant and silently
         * keep the shouted name the notification arrived with.
         */
        suspend fun assign(
            raw: String?,
            displayName: String,
        ): Long? {
            val name = MerchantNormaliser.normalise(displayName) ?: return null
            val merchant = findOrCreate(name.displayName, name.normalisedName) ?: return null
            if (merchant.displayName != name.displayName) {
                merchants.renameMerchant(merchant.id, name.displayName)
            }
            if (!raw.isNullOrBlank()) {
                merchants.upsertAlias(
                    MerchantAliasEntity(
                        merchantId = merchant.id,
                        pattern = raw,
                        matchType = MatchType.EXACT,
                        createdBy = AliasOrigin.USER,
                    ),
                )
            }
            return merchant.id
        }

        /**
         * The merchant a name belongs to, created when there is none yet. One already
         * here keeps the name it has: bringing a file in must not rename a merchant
         * you have already named.
         */
        suspend fun named(displayName: String): Long? {
            val name = MerchantNormaliser.normalise(displayName) ?: return null
            return findOrCreate(name.displayName, name.normalisedName)?.id
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
