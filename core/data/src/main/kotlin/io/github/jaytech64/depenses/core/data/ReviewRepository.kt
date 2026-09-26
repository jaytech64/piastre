// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.data

import io.github.jaytech64.depenses.core.database.ExpenseTransactionEntity
import io.github.jaytech64.depenses.core.database.TransactionDao
import io.github.jaytech64.depenses.core.database.TransactionForReview
import io.github.jaytech64.depenses.core.database.TransactionWithMerchant
import io.github.jaytech64.depenses.core.model.Money
import io.github.jaytech64.depenses.core.model.ReviewItem
import io.github.jaytech64.depenses.core.model.TransactionStatus
import io.github.jaytech64.depenses.parsing.ParserRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The queue of purchases the parser declined to guess at, and the three rulings
 * that empty it.
 *
 * Every ruling stamps [ExpenseTransactionEntity.reviewedAtUtcMillis], which is
 * what stops a later Wallet re-post from overwriting the decision — see
 * [TransactionDao.insertOrUpdate].
 */
@Singleton
class ReviewRepository
    @Inject
    constructor(
        private val transactions: TransactionDao,
        private val merchants: MerchantRepository,
        private val registry: ParserRegistry,
        private val clock: Clock,
    ) {
        fun queue(): Flow<List<ReviewItem>> =
            transactions
                .reviewQueue(TransactionStatus.NEEDS_REVIEW)
                .map { rows -> rows.map { toReviewItem(it, registry.sourceName(it.transaction.sourcePackage)) } }

        /** Accepts the parser's reading as it stands. */
        suspend fun confirm(id: Long) {
            rule(id, TransactionStatus.CONFIRMED)
        }

        /**
         * Drops the row from the ledger without deleting it.
         *
         * A dismissed purchase stays on disk because the raw notification behind it
         * is still evidence — that a refund, a pre-authorisation or a duplicate was
         * read as a charge is exactly what a parser fix needs to see.
         */
        suspend fun dismiss(id: Long) {
            rule(id, TransactionStatus.DISMISSED)
        }

        /**
         * Applies a correction and confirms the row in one step.
         *
         * A blank [merchantName] leaves the merchant unresolved rather than storing
         * an empty one: a purchase you could not name is still a purchase, and the
         * amount is the part the ledger cannot do without.
         *
         * A corrected merchant brings its own category, or none. The inbox never
         * asks for one, so the category it replaces came from the merchant the
         * parser misread, never from you.
         */
        suspend fun correct(
            id: Long,
            amount: Money,
            merchantName: String?,
        ) {
            val existing = transactions.byId(id) ?: return
            val merchantId =
                merchantName?.takeIf { it.isNotBlank() }?.let {
                    merchants.assign(
                        existing.merchantRaw,
                        it,
                    )
                }
            val merchantChanged = merchantId != null && merchantId != existing.merchantId
            transactions.update(
                existing.copy(
                    amountMinor = amount.minor,
                    currency = amount.currency,
                    merchantId = merchantId ?: existing.merchantId,
                    categoryId = if (merchantChanged) merchants.categoryOf(merchantId) else existing.categoryId,
                    status = TransactionStatus.CONFIRMED,
                    reviewReason = null,
                    reviewedAtUtcMillis = clock.millis(),
                ),
            )
        }

        private suspend fun rule(
            id: Long,
            status: TransactionStatus,
        ) {
            val existing = transactions.byId(id) ?: return
            transactions.update(
                existing.copy(
                    status = status,
                    reviewReason = null,
                    reviewedAtUtcMillis = clock.millis(),
                ),
            )
        }
    }

internal fun toReviewItem(
    row: TransactionForReview,
    sourceName: String,
): ReviewItem =
    ReviewItem(
        transaction = toDomain(TransactionWithMerchant(row.transaction, row.merchantName)),
        reason = row.transaction.reviewReason,
        sourceName = sourceName,
        rawTitle = row.rawTitle,
        rawText = row.rawText,
        postedAt = row.rawPostedAtUtcMillis?.let(Instant::ofEpochMilli),
    )
