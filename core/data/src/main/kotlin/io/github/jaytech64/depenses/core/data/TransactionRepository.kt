// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.data

import io.github.jaytech64.depenses.core.database.ReportDao
import io.github.jaytech64.depenses.core.database.TransactionDao
import io.github.jaytech64.depenses.core.database.TransactionWithMerchant
import io.github.jaytech64.depenses.core.model.Money
import io.github.jaytech64.depenses.core.model.Transaction
import io.github.jaytech64.depenses.core.model.TransactionStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
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
            cardLast4 = cardLast4,
            note = note,
        )
    }
