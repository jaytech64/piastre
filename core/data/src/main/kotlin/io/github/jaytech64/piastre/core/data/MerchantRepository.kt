// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.core.data

import io.github.jaytech64.piastre.core.database.MerchantAliasEntity
import io.github.jaytech64.piastre.core.database.MerchantDao
import io.github.jaytech64.piastre.core.database.MerchantEntity
import io.github.jaytech64.piastre.core.model.AliasOrigin
import io.github.jaytech64.piastre.core.model.MatchType
import io.github.jaytech64.piastre.core.model.Merchant
import io.github.jaytech64.piastre.core.model.MerchantNormaliser
import io.github.jaytech64.piastre.core.model.TransactionKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
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

        /** The category a [kind] transaction recorded at [merchantId] is given, or null. */
        suspend fun categoryOf(
            merchantId: Long?,
            kind: TransactionKind,
        ): Long? = merchantId?.let { merchants.byId(it)?.categoryFor(kind) }

        /**
         * The merchant a name belongs to, created when there is none yet. One already
         * here keeps the name it has: bringing a file in must not rename a merchant
         * you have already named.
         */
        suspend fun named(displayName: String): Long? {
            val name = MerchantNormaliser.normalise(displayName) ?: return null
            return findOrCreate(name.displayName, name.normalisedName)?.id
        }

        /**
         * Every merchant, the ones you have used most for [kind] lately first, so the
         * places you go every week are the ones the form offers before you type.
         *
         * "Lately" is the [RECENT_DAYS] up to [today], so a store you stopped going to
         * gives up its place within a season rather than holding it for years.
         */
        fun byUse(
            kind: TransactionKind,
            today: LocalDate,
        ): Flow<List<Merchant>> =
            merchants.byUse(kind, today.minusDays(RECENT_DAYS).toEpochDay().toInt()).map { rows ->
                rows.map(MerchantEntity::toDomain)
            }

        /** Every merchant, under the name it has now, whether or not anything was bought there. */
        fun all(): Flow<List<Merchant>> = merchants.all().map { rows -> rows.map(MerchantEntity::toDomain) }

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

        private companion object {
            const val RECENT_DAYS = 90L
        }
    }

private fun MerchantEntity.toDomain() =
    Merchant(
        id = id,
        displayName = displayName,
        normalisedName = normalisedName,
        expenseCategoryId = expenseCategoryId,
        incomeCategoryId = incomeCategoryId,
    )

/** Makes [categoryId] what every [kind] transaction recorded at merchant [id] is given; null stops it. */
internal suspend fun MerchantDao.setCategory(
    id: Long,
    kind: TransactionKind,
    categoryId: Long?,
) = when (kind) {
    TransactionKind.EXPENSE -> setExpenseCategory(id, categoryId)
    TransactionKind.INCOME -> setIncomeCategory(id, categoryId)
    TransactionKind.TRANSFER -> error("A transfer names no merchant to give its category")
}
