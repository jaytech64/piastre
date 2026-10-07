// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.data

import io.github.jaytech64.depenses.core.database.DepensesDatabase
import io.github.jaytech64.depenses.core.database.ExpenseTransactionEntity
import io.github.jaytech64.depenses.core.database.TransactionDao
import io.github.jaytech64.depenses.core.database.inTransaction
import io.github.jaytech64.depenses.core.model.Money
import io.github.jaytech64.depenses.core.model.TransactionKind
import io.github.jaytech64.depenses.core.model.TransactionPart
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
        private val database: DepensesDatabase,
        private val transactions: TransactionDao,
        private val merchants: MerchantRepository,
        private val categories: CategoryRepository,
        private val clock: Clock,
    ) {
        private val parts = database.transactionPartDao()

        /**
         * Rewrites a purchase with what you say it was.
         *
         * The row keeps the zone it was made in, and its day is recomputed there: a
         * trip purchase corrected from home must not shift into home's calendar.
         *
         * The merchant is only touched when the name actually changed, so saving an
         * edit to the note does not quietly turn the normaliser's guess into a
         * user-made alias. See [withMerchant] for what a changed name does.
         *
         * [reach] is what the form offered beside the category, or null when it
         * offered nothing; the purchase and everything the category reaches are
         * written together or not at all.
         *
         * [recurring] says whether a typed row has a day and no hour, as
         * [TransactionRepository.addManual] takes it, and either way can be undone
         * here — on a row a schedule wrote too, which stays that schedule's, under
         * the key that keeps its day from being written again. A captured row keeps
         * the hour its notification gave it, whatever is asked.
         *
         * [parts] replace whatever the amount was divided into before, as
         * [TransactionRepository.addManual] takes them; none leaves it unsplit.
         * Splitting a captured row is a ruling like any other edit, so the stamp
         * that keeps a re-post out keeps the split too.
         */
        suspend fun edit(
            id: Long,
            amount: Money,
            kind: TransactionKind,
            merchantName: String?,
            occurredAt: Instant,
            note: String?,
            accountId: Long?,
            toAccountId: Long?,
            categoryId: Long?,
            reach: CategoryReach?,
            recurring: Boolean = false,
            parts: List<TransactionPart> = emptyList(),
        ) = database.inTransaction {
            val row = transactions.withMerchantById(id) ?: return@inTransaction
            val settled = settleCategory(amount, categoryId, parts, kind, accountId)
            val existing = row.transaction
            val merchant = merchantName?.takeIf { it.isNotBlank() }?.trim()
            val renamed = merchant != (row.merchantName ?: existing.merchantRaw)
            val zone = ZoneId.of(existing.zoneId)
            val source = existing.source.typedAs(recurring)
            val occurredAtMillis =
                placedAtMillis(occurredAt.toEpochMilli(), zone, source == TransactionSource.RECURRING)
            val edited =
                (
                    if (renamed) {
                        existing.withMerchant(
                            merchant,
                        )
                    } else {
                        existing
                    }
                ).withAccounts(kind, accountId, toAccountId).copy(
                    amountMinor = amount.minor,
                    currency = amount.currency,
                    kind = kind,
                    source = source,
                    occurredAtUtcMillis = occurredAtMillis,
                    localDateEpochDay = localDateEpochDay(occurredAtMillis, zone),
                    note = note?.takeIf { it.isNotBlank() }?.trim(),
                    categoryId = settled.categoryId,
                    reviewedAtUtcMillis = clock.millis(),
                )
            transactions.update(edited)
            this.parts.replace(id, settled.parts.map { it.toEntity() })
            categories.spreadFrom(edited, reach)
        }

        private fun TransactionSource.typedAs(recurring: Boolean): TransactionSource =
            when (this) {
                TransactionSource.NOTIFICATION -> {
                    this
                }

                TransactionSource.MANUAL, TransactionSource.RECURRING -> {
                    if (recurring) TransactionSource.RECURRING else TransactionSource.MANUAL
                }
            }

        /**
         * A typed or recurring row simply takes the new name, or none.
         *
         * A captured row keeps its raw string — it is what the bank actually said —
         * and the string is re-pointed at the name you typed, exactly as a
         * correction in the review inbox does, so the next notification from the
         * same merchant arrives under that name. A blank name leaves a captured
         * row's merchant as it was: clearing the field cannot unsay the bank.
         */
        private suspend fun ExpenseTransactionEntity.withMerchant(merchant: String?): ExpenseTransactionEntity =
            when (source) {
                TransactionSource.MANUAL, TransactionSource.RECURRING -> {
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
         * A captured row's account is what its notification said, so only a typed
         * or recurring row takes a new one — but either may become a transfer, and then the
         * destination is yours to name.
         *
         * The destination is cleared on anything that is not a transfer, so an
         * entry switched back to an expense cannot keep a second account nothing
         * reads.
         */
        private fun ExpenseTransactionEntity.withAccounts(
            kind: TransactionKind,
            accountId: Long?,
            toAccountId: Long?,
        ): ExpenseTransactionEntity =
            copy(
                accountId = if (source == TransactionSource.NOTIFICATION) this.accountId else accountId,
                toAccountId = toAccountId.takeIf { kind == TransactionKind.TRANSFER },
            )

        /**
         * Takes a purchase out of the ledger.
         *
         * A typed or recurring row is deleted: nothing else describes it, and
         * nothing would bring it back — a schedule has already moved past the day
         * of every row it wrote. A captured row is dismissed instead, for the same reasons
         * [ReviewRepository.dismiss] keeps one — its notification is still evidence
         * — and one more: with the row gone, the next Wallet re-post would find no
         * purchase under its source key and put it straight back.
         */
        suspend fun remove(id: Long) {
            val existing = transactions.byId(id) ?: return
            when (existing.source) {
                TransactionSource.MANUAL, TransactionSource.RECURRING -> {
                    transactions.deleteById(id)
                }

                TransactionSource.NOTIFICATION -> {
                    transactions.update(
                        existing.copy(status = TransactionStatus.DISMISSED, reviewedAtUtcMillis = clock.millis()),
                    )
                }
            }
        }

        /**
         * Gives [categoryId], or none, to exactly [ids] and nothing else: no other
         * transaction, and no merchant's or account's default. See
         * [TransactionDao.categorise] for why a category already there is replaced.
         */
        suspend fun categoriseAll(
            ids: Set<Long>,
            categoryId: Long?,
        ) {
            transactions.categorise(ids, categoryId)
        }

        /**
         * [remove] for every one of [ids], in one database transaction: a selection
         * leaves the ledger whole, or not at all.
         */
        suspend fun removeAll(ids: Set<Long>) =
            database.inTransaction {
                ids.forEach { remove(it) }
            }
    }
