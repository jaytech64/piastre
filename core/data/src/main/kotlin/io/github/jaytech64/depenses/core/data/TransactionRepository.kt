package io.github.jaytech64.depenses.core.data

import io.github.jaytech64.depenses.core.database.ExpenseTransactionEntity
import io.github.jaytech64.depenses.core.database.ReportDao
import io.github.jaytech64.depenses.core.database.TransactionDao
import io.github.jaytech64.depenses.core.model.Money
import io.github.jaytech64.depenses.core.model.Transaction
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
        fun all(): Flow<List<Transaction>> = transactions.all().map { rows -> rows.map(::toDomain) }

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

internal fun toDomain(entity: ExpenseTransactionEntity): Transaction =
    Transaction(
        id = entity.id,
        amount = Money(entity.amountMinor, entity.currency),
        occurredAt = Instant.ofEpochMilli(entity.occurredAtUtcMillis),
        zoneId = ZoneId.of(entity.zoneId),
        localDate = LocalDate.ofEpochDay(entity.localDateEpochDay.toLong()),
        merchantRaw = entity.merchantRaw,
        merchantId = entity.merchantId,
        source = entity.source,
        status = entity.status,
        cardLabel = entity.cardLabel,
        cardLast4 = entity.cardLast4,
        note = entity.note,
    )
