// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.data

import io.github.jaytech64.depenses.core.database.DepensesDatabase
import io.github.jaytech64.depenses.core.database.RecurringTransactionDao
import io.github.jaytech64.depenses.core.database.RecurringTransactionEntity
import io.github.jaytech64.depenses.core.database.RecurringWithNames
import io.github.jaytech64.depenses.core.database.TransactionDao
import io.github.jaytech64.depenses.core.database.inTransaction
import io.github.jaytech64.depenses.core.model.Money
import io.github.jaytech64.depenses.core.model.Recurrence
import io.github.jaytech64.depenses.core.model.RecurringTransaction
import io.github.jaytech64.depenses.core.model.Rhythm
import io.github.jaytech64.depenses.core.model.TransactionKind
import io.github.jaytech64.depenses.core.model.TransactionSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Recurring transactions: made from a typed one, changed, and stopped.
 *
 * Every change is followed by a catch-up pass, so a schedule whose next day has
 * already come is written into the ledger before the screen that made it closes.
 */
@Singleton
class RecurringRepository
    @Inject
    constructor(
        private val database: DepensesDatabase,
        private val recurring: RecurringTransactionDao,
        private val transactions: TransactionDao,
        private val merchants: MerchantRepository,
        private val posting: RecurringPosting,
    ) {
        /** Every schedule, the soonest next day first. One with no day left is removed as it ends. */
        fun all(): Flow<List<RecurringTransaction>> = recurring.allWithNames().map { rows -> rows.map(::toDomain) }

        /** How many schedules still have a day to come. */
        fun activeCount(): Flow<Int> = recurring.activeCount()

        suspend fun byId(id: Long): RecurringTransaction? = recurring.withNamesById(id)?.let(::toDomain)

        /**
         * Makes the transaction [transactionId] the first of a schedule that falls
         * every [rhythm] after its day, until [lastDate] when there is one, and
         * returns the schedule's id.
         *
         * The schedule copies the transaction as it stands, category included, and
         * the transaction becomes its first row: from then on it is a recurring row
         * like those the schedule writes, keyed on the schedule and its day, and
         * says so in the ledger. Nothing is read through afterwards: see
         * [RecurringTransactionEntity].
         *
         * Only a typed transaction can repeat, and only one that is not already a
         * schedule's: a captured one will be captured again next time, and a
         * second schedule would write every day twice. Returns null for either.
         */
        suspend fun repeat(
            transactionId: Long,
            rhythm: Rhythm,
            lastDate: LocalDate?,
        ): Long? {
            val id =
                database.inTransaction {
                    val row =
                        transactions
                            .byId(transactionId)
                            ?.takeIf { it.source == TransactionSource.MANUAL && it.recurringId == null }
                            ?: return@inTransaction null
                    val first = LocalDate.ofEpochDay(row.localDateEpochDay.toLong())
                    val recurrence = Recurrence(rhythm, first, lastDate)
                    val uuid = UUID.randomUUID().toString()
                    val id =
                        recurring.insert(
                            RecurringTransactionEntity(
                                uuid = uuid,
                                amountMinor = row.amountMinor,
                                currency = row.currency,
                                kind = row.kind,
                                merchantId = row.merchantId,
                                merchantRaw = row.merchantRaw,
                                accountId = row.accountId,
                                toAccountId = row.toAccountId,
                                categoryId = row.categoryId,
                                note = row.note,
                                rhythm = rhythm,
                                firstEpochDay = first.toEpochDay().toInt(),
                                lastEpochDay = lastDate?.toEpochDay()?.toInt(),
                                nextDueEpochDay = recurrence.nextAfter(first)?.toEpochDay()?.toInt(),
                            ),
                        )
                    transactions.update(
                        row.copy(
                            recurringId = id,
                            source = TransactionSource.RECURRING,
                            sourcePackage = RECURRING_SOURCE,
                            sourceKey = recurringSourceKey(uuid, first),
                        ),
                    )
                    id
                }
            posting.catchUp()
            return id
        }

        /**
         * Rewrites what schedule [id] writes from now on. The rows it has already
         * written are left exactly as they are.
         *
         * [nextDate] is the next day it falls on. Left where it was with the same
         * [rhythm], the schedule keeps counting from its first day, so a bill on the
         * 31st that last fell on the 30th still comes back to the 31st. Moved, or
         * with a new rhythm, it counts from [nextDate] instead.
         */
        suspend fun change(
            id: Long,
            amount: Money,
            kind: TransactionKind,
            merchantName: String?,
            accountId: Long?,
            toAccountId: Long?,
            categoryId: Long?,
            note: String?,
            rhythm: Rhythm,
            nextDate: LocalDate,
            lastDate: LocalDate?,
        ) {
            require(lastDate == null || lastDate >= nextDate) { "a schedule cannot end before its next day" }
            database.inTransaction {
                val named = recurring.withNamesById(id) ?: return@inTransaction
                val existing = named.recurring
                val keepsCounting =
                    rhythm == existing.rhythm && nextDate.toEpochDay().toInt() == existing.nextDueEpochDay
                val first = if (keepsCounting) existing.firstEpochDay else nextDate.toEpochDay().toInt()
                val merchant = merchantName?.takeIf { it.isNotBlank() }?.trim()
                val renamed = merchant != (named.merchantName ?: existing.merchantRaw)
                recurring.update(
                    existing.copy(
                        amountMinor = amount.minor,
                        currency = amount.currency,
                        kind = kind,
                        merchantId =
                            if (renamed) {
                                merchant?.let {
                                    merchants.assign(
                                        raw = it,
                                        displayName = it,
                                    )
                                }
                            } else {
                                existing.merchantId
                            },
                        merchantRaw = if (renamed) merchant else existing.merchantRaw,
                        accountId = accountId,
                        toAccountId = toAccountId.takeIf { kind == TransactionKind.TRANSFER },
                        categoryId = categoryId,
                        note = note?.takeIf { it.isNotBlank() }?.trim(),
                        rhythm = rhythm,
                        firstEpochDay = first,
                        lastEpochDay = lastDate?.toEpochDay()?.toInt(),
                        nextDueEpochDay = nextDate.toEpochDay().toInt(),
                    ),
                )
            }
            posting.catchUp()
        }

        /** Ends schedule [id]. Every row it wrote stays in the ledger. */
        suspend fun stop(id: Long) = recurring.stop(id)
    }

internal fun RecurringTransactionEntity.recurrence(): Recurrence =
    Recurrence(
        rhythm = rhythm,
        firstDate = LocalDate.ofEpochDay(firstEpochDay.toLong()),
        lastDate = lastEpochDay?.let { LocalDate.ofEpochDay(it.toLong()) },
    )

private fun toDomain(row: RecurringWithNames): RecurringTransaction =
    with(row.recurring) {
        RecurringTransaction(
            id = id,
            amount = Money(amountMinor, currency),
            kind = kind,
            merchantName = row.merchantName ?: merchantRaw,
            accountId = accountId,
            accountName = row.accountName,
            accountType = row.accountType,
            toAccountId = toAccountId,
            toAccountName = row.toAccountName,
            toAccountType = row.toAccountType,
            categoryId = categoryId,
            categoryName = row.categoryName,
            note = note,
            recurrence = recurrence(),
            nextDue = nextDueEpochDay?.let { LocalDate.ofEpochDay(it.toLong()) },
        )
    }
