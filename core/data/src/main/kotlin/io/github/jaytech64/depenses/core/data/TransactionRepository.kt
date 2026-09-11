// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.data

import io.github.jaytech64.depenses.core.database.ExpenseTransactionEntity
import io.github.jaytech64.depenses.core.database.ReportDao
import io.github.jaytech64.depenses.core.database.TransactionDao
import io.github.jaytech64.depenses.core.database.TransactionWithMerchant
import io.github.jaytech64.depenses.core.model.Money
import io.github.jaytech64.depenses.core.model.Transaction
import io.github.jaytech64.depenses.core.model.TransactionSource
import io.github.jaytech64.depenses.core.model.TransactionStatus
import io.github.jaytech64.depenses.core.model.localDateEpochDay
import kotlinx.coroutines.flow.Flow
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
        private val transactions: TransactionDao,
        private val reports: ReportDao,
        private val merchants: MerchantRepository,
        private val zoneId: Provider<ZoneId>,
    ) {
        /**
         * What the ledger shows: purchases you have agreed to, newest first.
         *
         * Unreviewed transactions are deliberately absent. Every amount on screen is
         * one you accepted, which is only honest as long as [needsReviewCount] is
         * surfaced beside it — a total that quietly omits rows is worse than one
         * that admits it.
         */
        fun confirmed(): Flow<List<Transaction>> =
            transactions
                .withMerchantByStatus(TransactionStatus.CONFIRMED)
                .map { rows -> rows.map(::toDomain) }

        fun needsReview(): Flow<List<Transaction>> =
            transactions
                .withMerchantByStatus(TransactionStatus.NEEDS_REVIEW)
                .map { rows -> rows.map(::toDomain) }

        fun needsReviewCount(): Flow<Int> = transactions.countByStatus(TransactionStatus.NEEDS_REVIEW)

        fun count(): Flow<Int> = transactions.count()

        /**
         * Records a purchase no notification will ever describe — cash, Interac, a
         * card this phone does not carry.
         *
         * Such a row has no natural identity, so it is given one: a random key under
         * a source package no parser claims. That keeps the uniqueness constraint
         * meaningful for notifications while letting you enter the same amount at
         * the same merchant twice in a minute, which really happens.
         *
         * The zone is the one you are in as you type, which is also the zone the day
         * bucket is computed in — a purchase entered on a trip belongs to the day it
         * was that trip, not the day it was back home.
         */
        suspend fun addManual(
            amount: Money,
            merchantName: String?,
            occurredAt: Instant,
            note: String?,
        ): Long {
            val zone = zoneId.get()
            val merchant = merchantName?.takeIf { it.isNotBlank() }?.trim()
            return transactions.insert(
                ExpenseTransactionEntity(
                    rawEventId = null,
                    amountMinor = amount.minor,
                    currency = amount.currency,
                    occurredAtUtcMillis = occurredAt.toEpochMilli(),
                    zoneId = zone.id,
                    localDateEpochDay = localDateEpochDay(occurredAt.toEpochMilli(), zone),
                    merchantId = merchant?.let { merchants.assign(raw = it, displayName = it) },
                    merchantRaw = merchant,
                    categoryId = null,
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
                ),
            )
        }

        suspend fun byId(id: Long): Transaction? = transactions.withMerchantById(id)?.let(::toDomain)

        private companion object {
            /** Not a package name, and deliberately not one: no parser can claim it. */
            const val MANUAL_SOURCE = "manual"

            /** A purchase you typed yourself is not a reading that could be wrong. */
            const val CERTAIN = 1f
        }

        fun dailyTotals(
            from: LocalDate,
            to: LocalDate,
            currency: String = Money.CAD,
        ): Flow<Map<LocalDate, Money>> =
            reports
                .dailyTotals(from.toEpochDay().toInt(), to.toEpochDay().toInt(), currency)
                .map { rows ->
                    rows.associate { LocalDate.ofEpochDay(it.day.toLong()) to Money(it.totalMinor, currency) }
                }

        fun totalForRange(
            from: LocalDate,
            to: LocalDate,
            currency: String = Money.CAD,
        ): Flow<Money> =
            reports
                .totalForRange(from.toEpochDay().toInt(), to.toEpochDay().toInt(), currency)
                .map { Money(it ?: 0L, currency) }
    }

internal fun toDomain(row: TransactionWithMerchant): Transaction =
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
            cardLast4 = row.resolvedCardLast4 ?: cardLast4,
            note = note,
            kind = kind,
            paymentMethod = paymentMethod,
            cardId = cardId,
            cardName = row.cardName,
        )
    }
