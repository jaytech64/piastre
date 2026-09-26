// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.data

import io.github.jaytech64.depenses.core.database.AccountDao
import io.github.jaytech64.depenses.core.database.CategoryDao
import io.github.jaytech64.depenses.core.database.CategoryEntity
import io.github.jaytech64.depenses.core.database.CategoryWithCounts
import io.github.jaytech64.depenses.core.database.ExpenseTransactionEntity
import io.github.jaytech64.depenses.core.database.MerchantDao
import io.github.jaytech64.depenses.core.model.Category
import io.github.jaytech64.depenses.core.model.CategoryName
import io.github.jaytech64.depenses.core.model.CategoryUsage
import io.github.jaytech64.depenses.core.model.TransactionKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What a category chosen on one transaction also does, as the form offered it.
 *
 * [others] gives it to the rest of the merchant's transactions of the same kind
 * that have none — or, on a transfer, to the other transfers into the same
 * account. [fromNowOn] makes it what that merchant or account gives everything
 * recorded there from now on; false, while the merchant already gives this very
 * category, stops it.
 */
data class CategoryReach(
    val others: Boolean,
    val fromNowOn: Boolean,
)

/**
 * What a category spreads along: the merchant for a purchase or an income, and
 * for a transfer, which has no merchant, the account it goes into.
 */
sealed interface CategoryAnchor {
    data class AtMerchant(
        val merchantId: Long,
    ) : CategoryAnchor

    data class IntoAccount(
        val accountId: Long,
    ) : CategoryAnchor
}

@Singleton
class CategoryRepository
    @Inject
    constructor(
        private val categories: CategoryDao,
        private val merchants: MerchantDao,
        private val accounts: AccountDao,
    ) {
        /**
         * Every category, the ones you have used most for [kind] lately first — the
         * order the merchant suggestions follow, over the same [RECENT_DAYS].
         */
        fun byUse(
            kind: TransactionKind,
            today: LocalDate,
        ): Flow<List<Category>> =
            categories
                .byUse(kind, today.minusDays(RECENT_DAYS).toEpochDay().toInt())
                .map { rows -> rows.map(CategoryEntity::toDomain) }

        /** Every category with what uses it, for Settings to sort in the screen's own language. */
        fun all(): Flow<List<CategoryUsage>> =
            categories.allWithCounts().map { rows ->
                rows.map(CategoryWithCounts::toDomain)
            }

        /**
         * The category a typed name belongs to, created when there is none yet;
         * null for a name with nothing in it. Typing `epicerie` picks the Épicerie
         * you already have and keeps its spelling.
         */
        suspend fun named(typed: String): Long? {
            val name = CategoryName.of(typed) ?: return null
            categories.byNormalisedName(name.normalisedName)?.let { return it.id }
            categories.insert(CategoryEntity(name = name.displayName, normalisedName = name.normalisedName))
            return categories.byNormalisedName(name.normalisedName)?.id
        }

        /** False, changing nothing, when the name is blank or another category already answers to it. */
        suspend fun rename(
            id: Long,
            typed: String,
        ): Boolean {
            val name = CategoryName.of(typed) ?: return false
            return categories.rename(id, name.displayName, name.normalisedName) == 1
        }

        suspend fun delete(id: Long) = categories.delete(id)

        /** The category [anchor] gives what is recorded there, or null. */
        suspend fun defaultAt(anchor: CategoryAnchor): Long? =
            when (anchor) {
                is CategoryAnchor.AtMerchant -> merchants.byId(anchor.merchantId)?.categoryId
                is CategoryAnchor.IntoAccount -> accounts.categoryOf(anchor.accountId)
            }

        /**
         * How many of [anchor]'s other [kind] transactions have no category, not
         * counting [exceptId] — exactly what [CategoryReach.others] would change.
         */
        fun uncategorisedAt(
            anchor: CategoryAnchor,
            kind: TransactionKind,
            exceptId: Long?,
        ): Flow<Int> =
            when (anchor) {
                is CategoryAnchor.AtMerchant -> categories.uncategorisedAtMerchant(anchor.merchantId, kind, exceptId)
                is CategoryAnchor.IntoAccount -> categories.uncategorisedTransfersInto(anchor.accountId, exceptId)
            }

        /**
         * Carries the category just saved on [saved] as far as [reach] says: nowhere
         * when [reach] is null, which is what a screen that offered nothing passes.
         * The caller runs this in the same database transaction as the save, so the
         * transaction and everything it reached land together.
         */
        internal suspend fun spreadFrom(
            saved: ExpenseTransactionEntity,
            reach: CategoryReach?,
        ) {
            val categoryId = saved.categoryId ?: return
            val anchor = anchorFor(saved.kind, saved.merchantId, saved.toAccountId) ?: return
            if (reach == null) return
            spread(saved.id, saved.kind, anchor, categoryId, reach)
        }

        private suspend fun spread(
            transactionId: Long,
            kind: TransactionKind,
            anchor: CategoryAnchor,
            categoryId: Long,
            reach: CategoryReach,
        ) {
            if (reach.others) {
                when (anchor) {
                    is CategoryAnchor.AtMerchant -> {
                        categories.categoriseAtMerchant(anchor.merchantId, kind, categoryId, transactionId)
                    }

                    is CategoryAnchor.IntoAccount -> {
                        categories.categoriseTransfersInto(anchor.accountId, categoryId, transactionId)
                    }
                }
            }
            when {
                reach.fromNowOn -> setDefault(anchor, categoryId)
                defaultAt(anchor) == categoryId -> setDefault(anchor, null)
            }
        }

        private suspend fun setDefault(
            anchor: CategoryAnchor,
            categoryId: Long?,
        ) = when (anchor) {
            is CategoryAnchor.AtMerchant -> merchants.setCategory(anchor.merchantId, categoryId)
            is CategoryAnchor.IntoAccount -> accounts.setCategory(anchor.accountId, categoryId)
        }

        private companion object {
            const val RECENT_DAYS = 90L
        }
    }

/**
 * What a category spreads along for a transaction of [kind]: its merchant, or the
 * account a transfer goes into. Null when there is neither.
 */
fun anchorFor(
    kind: TransactionKind,
    merchantId: Long?,
    toAccountId: Long?,
): CategoryAnchor? =
    when (kind) {
        TransactionKind.TRANSFER -> toAccountId?.let(CategoryAnchor::IntoAccount)
        TransactionKind.EXPENSE, TransactionKind.INCOME -> merchantId?.let(CategoryAnchor::AtMerchant)
    }

private fun CategoryEntity.toDomain() = Category(id = id, name = name)

private fun CategoryWithCounts.toDomain() =
    CategoryUsage(
        category = category.toDomain(),
        transactionCount = transactionCount,
        merchantCount = merchantCount,
        accountCount = accountCount,
    )
