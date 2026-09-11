// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.data

import io.github.jaytech64.depenses.core.database.ExpenseTransactionEntity
import io.github.jaytech64.depenses.core.database.TransactionDao
import io.github.jaytech64.depenses.core.model.Money
import io.github.jaytech64.depenses.core.model.TransactionKind
import io.github.jaytech64.depenses.core.model.TransactionSource
import io.github.jaytech64.depenses.core.model.TransactionStatus
import io.github.jaytech64.depenses.core.model.localDateEpochDay
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Corrections to purchases already in the ledger: what the review inbox does for
 * a row the parser doubted, done for any row you do.
 *
 * Every write stamps [ExpenseTransactionEntity.reviewedAtUtcMillis], whatever
 * the source. On a captured row that is what makes the correction stick — see
 * [TransactionDao.insertOrUpdate] — and without it the next Wallet re-post or
 * parser fix would put back the very reading you just corrected.
 */
@Singleton
class TransactionEditor
    @Inject
    constructor(
        private val transactions: TransactionDao,
        private val merchants: MerchantRepository,
        private val clock: Clock,
    ) {
        /**
         * Rewrites a purchase with what you say it was.
         *
         * The row keeps the zone it was made in, and its day is recomputed there: a
         * trip purchase corrected from home must not shift into home's calendar.
         *
         * The merchant is only touched when the name actually changed, so saving an
         * edit to the note does not quietly turn the normaliser's guess into a
         * user-made alias. See [withMerchant] for what a changed name does.
         */
        suspend fun edit(
            id: Long,
            amount: Money,
            kind: TransactionKind,
            merchantName: String?,
            occurredAt: Instant,
            note: String?,
        ) {
            val row = transactions.withMerchantById(id) ?: return
            val existing = row.transaction
            val merchant = merchantName?.takeIf { it.isNotBlank() }?.trim()
            val renamed = merchant != (row.merchantName ?: existing.merchantRaw)
            val occurredAtMillis = occurredAt.toEpochMilli()
            transactions.update(
                (if (renamed) existing.withMerchant(merchant) else existing).copy(
                    amountMinor = amount.minor,
                    currency = amount.currency,
                    kind = kind,
                    occurredAtUtcMillis = occurredAtMillis,
                    localDateEpochDay = localDateEpochDay(occurredAtMillis, ZoneId.of(existing.zoneId)),
                    note = note?.takeIf { it.isNotBlank() }?.trim(),
                    reviewedAtUtcMillis = clock.millis(),
                ),
            )
        }

        /**
         * A manual row simply takes the new name, or none.
         *
         * A captured row keeps its raw string — it is what the bank actually said —
         * and the string is re-pointed at the name you typed, exactly as a
         * correction in the review inbox does, so the next notification from the
         * same merchant arrives under that name. A blank name leaves a captured
         * row's merchant as it was: clearing the field cannot unsay the bank.
         */
        private suspend fun ExpenseTransactionEntity.withMerchant(merchant: String?): ExpenseTransactionEntity =
            when (source) {
                TransactionSource.MANUAL -> {
                    copy(
                        merchantId = merchant?.let { merchants.assign(raw = it, displayName = it) },
                        merchantRaw = merchant,
                    )
                }

                TransactionSource.NOTIFICATION -> {
                    merchant?.let { copy(merchantId = merchants.assign(merchantRaw, it)) } ?: this
                }
            }

        /**
         * Takes a purchase out of the ledger.
         *
         * A manual row is deleted: nothing else describes it, and nothing would
         * bring it back. A captured row is dismissed instead, for the same reasons
         * [ReviewRepository.dismiss] keeps one — its notification is still evidence
         * — and one more: with the row gone, the next Wallet re-post would find no
         * purchase under its source key and put it straight back.
         */
        suspend fun remove(id: Long) {
            val existing = transactions.byId(id) ?: return
            when (existing.source) {
                TransactionSource.MANUAL -> {
                    transactions.deleteById(id)
                }

                TransactionSource.NOTIFICATION -> {
                    transactions.update(
                        existing.copy(status = TransactionStatus.DISMISSED, reviewedAtUtcMillis = clock.millis()),
                    )
                }
            }
        }
    }
