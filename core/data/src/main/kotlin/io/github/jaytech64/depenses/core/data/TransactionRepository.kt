// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.data

import io.github.jaytech64.depenses.core.database.DepensesDatabase
import io.github.jaytech64.depenses.core.database.ExpenseTransactionEntity
import io.github.jaytech64.depenses.core.database.PartWithCategory
import io.github.jaytech64.depenses.core.database.TransactionDao
import io.github.jaytech64.depenses.core.database.TransactionWithMerchant
import io.github.jaytech64.depenses.core.database.inTransaction
import io.github.jaytech64.depenses.core.model.Money
import io.github.jaytech64.depenses.core.model.Transaction
import io.github.jaytech64.depenses.core.model.TransactionKind
import io.github.jaytech64.depenses.core.model.TransactionPart
import io.github.jaytech64.depenses.core.model.TransactionSource
import io.github.jaytech64.depenses.core.model.TransactionStatus
import io.github.jaytech64.depenses.core.model.localDateEpochDay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * The only door features are allowed through. Room types stop here — a feature
 * module that could see an entity would end up writing SQL in a ViewModel.
 */
@Singleton
class TransactionRepository
    @Inject
    constructor(
        private val database: DepensesDatabase,
        private val transactions: TransactionDao,
        private val merchants: MerchantRepository,
        private val categories: CategoryRepository,
        private val zoneId: Provider<ZoneId>,
    ) {
        private val parts = database.transactionPartDao()

        /**
         * What the ledger shows: purchases you have agreed to, newest first.
         *
         * Unreviewed transactions are deliberately absent. Every amount on screen is
         * one you accepted, which is only honest as long as [needsReviewCount] is
         * surfaced beside it — a total that quietly omits rows is worse than one
         * that admits it.
         */
        fun confirmed(): Flow<List<Transaction>> =
            combine(
                transactions.withMerchantByStatus(TransactionStatus.CONFIRMED),
                parts.allWithNames(),
            ) { rows, parts ->
                val byTransaction = parts.groupBy { it.part.transactionId }
                rows.map { toDomain(it, byTransaction[it.transaction.id].orEmpty()) }
            }

        fun needsReview(): Flow<List<Transaction>> =
            transactions
                .withMerchantByStatus(TransactionStatus.NEEDS_REVIEW)
                .map { rows -> rows.map(::toDomain) }

        fun needsReviewCount(): Flow<Int> = transactions.countByStatus(TransactionStatus.NEEDS_REVIEW)

        fun count(): Flow<Int> = transactions.count()

        /**
         * The account you last paid each merchant from — or, for [kind] income, were
         * paid into — keyed on the merchant's normalised name. A Wallet capture
         * counts as much as a typed row, and a row dated after [today] does not
         * count yet (ADR-0015).
         */
        fun lastAccountAtEachMerchant(
            kind: TransactionKind,
            today: LocalDate,
        ): Flow<Map<String, Long>> =
            transactions
                .lastAccountAtEachMerchant(kind, today.toEpochDay().toInt())
                .map { rows -> rows.associate { it.normalisedName to it.accountId } }

        /**
         * Records a transaction no notification will ever describe — cash, Interac,
         * a card this phone does not carry, or money coming in.
         *
         * Such a row has no natural identity, so it is given one: a random key under
         * a source package no parser claims. That keeps the uniqueness constraint
         * meaningful for notifications while letting you enter the same amount at
         * the same merchant twice in a minute, which really happens.
         *
         * The zone is the one you are in as you type, which is also the zone the day
         * bucket is computed in — a purchase entered on a trip belongs to the day it
         * was that trip, not the day it was back home.
         *
         * [accountId] is the account it came from, null when nothing was said, and
         * [toAccountId] where a transfer landed. [reach] is what the form offered
         * beside [categoryId], as [TransactionEditor.edit] takes it.
         *
         * A [recurring] one comes back, at an amount only known once it has: it has
         * a day and no hour, and reads in the ledger as a row a schedule wrote. It
         * is still typed each time, and writes nothing to come.
         *
         * [parts] divide the amount among categories: see [settleCategory] for what
         * they must be. A split carries no category of its own, so [categoryId] and
         * [reach] are then set aside. A part of an expense may go into another
         * account, which then gains that share.
         */
        suspend fun addManual(
            amount: Money,
            merchantName: String?,
            occurredAt: Instant,
            note: String?,
            kind: TransactionKind,
            accountId: Long?,
            toAccountId: Long?,
            categoryId: Long?,
            reach: CategoryReach?,
            recurring: Boolean = false,
            parts: List<TransactionPart> = emptyList(),
        ): Long =
            database.inTransaction {
                val settled = settleCategory(amount, categoryId, parts, kind, accountId)
                val entry =
                    manualEntry(
                        amount,
                        merchantName,
                        occurredAt,
                        note,
                        kind,
                        accountId,
                        toAccountId,
                        settled.categoryId,
                    ).placed(recurring)
                val id = transactions.insert(entry)
                this.parts.replace(id, settled.parts.map { it.toEntity() })
                categories.spreadFrom(entry.copy(id = id), reach)
                id
            }

        suspend fun byId(id: Long): Transaction? =
            transactions.withMerchantById(id)?.let { toDomain(it, parts.ofTransaction(id)) }

        private suspend fun manualEntry(
            amount: Money,
            merchantName: String?,
            occurredAt: Instant,
            note: String?,
            kind: TransactionKind,
            accountId: Long?,
            toAccountId: Long?,
            categoryId: Long?,
        ): ExpenseTransactionEntity {
            val zone = zoneId.get()
            val merchant = merchantName?.takeIf { it.isNotBlank() }?.trim()
            return ExpenseTransactionEntity(
                rawEventId = null,
                amountMinor = amount.minor,
                currency = amount.currency,
                occurredAtUtcMillis = occurredAt.toEpochMilli(),
                zoneId = zone.id,
                localDateEpochDay = localDateEpochDay(occurredAt.toEpochMilli(), zone),
                merchantId = merchant?.let { merchants.assign(raw = it, displayName = it) },
                merchantRaw = merchant,
                categoryId = categoryId,
                source = TransactionSource.MANUAL,
                status = TransactionStatus.CONFIRMED,
                reviewReason = null,
                reviewedAtUtcMillis = null,
                confidence = CERTAIN,
                parserId = null,
                parserVersion = null,
                sourcePackage = MANUAL_SOURCE,
                sourceKey = UUID.randomUUID().toString(),
                cardLabel = null,
                cardLast4 = null,
                note = note?.takeIf { it.isNotBlank() }?.trim(),
                kind = kind,
                accountId = accountId,
                toAccountId = toAccountId,
            )
        }

        private fun ExpenseTransactionEntity.placed(recurring: Boolean): ExpenseTransactionEntity =
            copy(
                occurredAtUtcMillis = placedAtMillis(occurredAtUtcMillis, ZoneId.of(zoneId), recurring),
                source = if (recurring) TransactionSource.RECURRING else TransactionSource.MANUAL,
            )

        private companion object {
            /** Not a package name, and deliberately not one: no parser can claim it. */
            const val MANUAL_SOURCE = "manual"

            /** A purchase you typed yourself is not a reading that could be wrong. */
            const val CERTAIN = 1f
        }
    }

internal fun toDomain(
    row: TransactionWithMerchant,
    parts: List<PartWithCategory> = emptyList(),
): Transaction =
    with(row.transaction) {
        Transaction(
            id = id,
            amount = Money(amountMinor, currency),
            occurredAt = Instant.ofEpochMilli(occurredAtUtcMillis),
            zoneId = ZoneId.of(zoneId),
            localDate = LocalDate.ofEpochDay(localDateEpochDay.toLong()),
            merchantRaw = merchantRaw,
            merchantId = merchantId,
            merchantName = row.merchantName,
            source = source,
            status = status,
            cardLabel = cardLabel,
            cardLast4 = row.accountLast4 ?: cardLast4,
            note = note,
            kind = kind,
            accountId = accountId,
            accountName = row.accountName,
            accountType = row.accountType,
            toAccountId = toAccountId,
            toAccountName = row.toAccountName,
            toAccountLast4 = row.toAccountLast4,
            toAccountType = row.toAccountType,
            categoryId = categoryId,
            categoryName = row.categoryName,
            recurringId = recurringId,
            parts = parts.map { it.toDomain(currency) },
        )
    }
