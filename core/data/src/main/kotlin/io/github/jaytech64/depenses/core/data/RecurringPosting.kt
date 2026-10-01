// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.data

import android.database.sqlite.SQLiteException
import android.util.Log
import io.github.jaytech64.depenses.core.database.DepensesDatabase
import io.github.jaytech64.depenses.core.database.ExpenseTransactionEntity
import io.github.jaytech64.depenses.core.database.RawNotificationEventDao
import io.github.jaytech64.depenses.core.database.RecurringTransactionDao
import io.github.jaytech64.depenses.core.database.RecurringTransactionEntity
import io.github.jaytech64.depenses.core.database.TransactionDao
import io.github.jaytech64.depenses.core.database.inTransaction
import io.github.jaytech64.depenses.core.model.TransactionSource
import io.github.jaytech64.depenses.core.model.TransactionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Writes each recurring transaction into the ledger once its day has come.
 *
 * Nothing wakes the app on a due day. WorkManager declares a network permission
 * this app refuses to ship (see [CaptureCatchUp]), and an alarm that survives a
 * reboot needs the boot permission ADR-0004 declined. So due days are caught up
 * rather than fired: whenever the process starts — the app launching, or Android
 * binding the listener — whenever the app comes back on screen, at every midnight
 * while the process lives, and whenever the listener rebinds. The midnight timer
 * alone is not enough: its clock stops while the phone sleeps, so it can wake
 * hours late. Each row is dated on its own due day however late it is
 * written, so the ledger is right whenever it is read.
 *
 * See `docs/adr/0013-a-recurring-transaction-is-written-on-its-day.md`.
 */
@Singleton
class RecurringPosting
    @Inject
    constructor(
        private val database: DepensesDatabase,
        private val recurring: RecurringTransactionDao,
        private val transactions: TransactionDao,
        private val ticker: LocalDateTicker,
        private val clock: Clock,
        private val zoneId: Provider<ZoneId>,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Catches up now, and again each time the local day changes, for as long as the process lives. */
        fun followDays() {
            scope.launch { ticker.dates().collect { catchUpLogged(it) } }
        }

        /** Catches up once, without waiting for it. */
        fun schedule() {
            scope.launch { catchUpLogged(today()) }
        }

        /** Writes every day that has come by today, and returns how many rows that was. */
        suspend fun catchUp(): Int = catchUp(today())

        /**
         * Writes every day that has come by [today], and returns how many rows that
         * was. A schedule left with no day to come is then removed: it has nothing
         * more to write, and every row it wrote stays.
         */
        suspend fun catchUp(today: LocalDate): Int {
            val written =
                recurring
                    .dueBy(today.toEpochDay().toInt())
                    .sumOf { writeDue(it.id, today) }
            recurring.removeEnded()
            return written
        }

        /**
         * One schedule's due days and the cursor past them, in one database
         * transaction: a row is never written without its day being passed, and a
         * day is never passed without its row.
         *
         * The schedule is read again inside the transaction, because another pass
         * may have moved it since it was listed. Should two passes still reach the
         * same day, the second row collides on its source key and is ignored.
         */
        private suspend fun writeDue(
            id: Long,
            today: LocalDate,
        ): Int =
            database.inTransaction {
                val schedule = recurring.byId(id) ?: return@inTransaction 0
                val recurrence = schedule.recurrence()
                val zone = zoneId.get()
                var day = schedule.nextDueEpochDay?.let { LocalDate.ofEpochDay(it.toLong()) }
                var written = 0
                while (day != null && day <= today) {
                    val rowId = transactions.insertOrIgnore(schedule.occurrenceOn(day, zone))
                    if (rowId != RawNotificationEventDao.INSERT_IGNORED) written++
                    day = recurrence.nextAfter(day)
                }
                recurring.moveNextDue(schedule.id, day?.toEpochDay()?.toInt())
                written
            }

        private suspend fun catchUpLogged(today: LocalDate) {
            try {
                val written = catchUp(today)
                if (written > 0) Log.i(TAG, "wrote $written recurring transaction(s) that fell due")
            } catch (error: SQLiteException) {
                Log.e(TAG, "could not write the recurring transactions due; the next pass will", error)
            }
        }

        /** The zone the phone is in now, which a row written today is dated in. */
        internal fun zone(): ZoneId = zoneId.get()

        /** Today where the phone is now: the day due days are counted up to. */
        internal fun today(): LocalDate = LocalDate.now(clock.withZone(zoneId.get()))

        private companion object {
            const val TAG = "RecurringPosting"
        }
    }

/** Not a package name, and deliberately not one: no parser can claim it. */
internal const val RECURRING_SOURCE = "recurring"

/** The identity of a schedule's row for [day]: the schedule and the day, the one thing that makes two rows the same. */
internal fun recurringSourceKey(
    uuid: String,
    day: LocalDate,
): String = "$uuid:$day"

/**
 * The row a schedule writes for [day].
 *
 * A due day has no hour, so the row is placed at noon in the zone it is written
 * in, as far from either midnight as a day allows. Its day is [day] itself
 * rather than one worked out from that instant, so the ledger files it on its
 * due day whatever zone it is later read from.
 *
 * Its source key is [recurringSourceKey].
 */
internal fun RecurringTransactionEntity.occurrenceOn(
    day: LocalDate,
    zone: ZoneId,
): ExpenseTransactionEntity =
    ExpenseTransactionEntity(
        rawEventId = null,
        amountMinor = amountMinor,
        currency = currency,
        occurredAtUtcMillis =
            day
                .atTime(LocalTime.NOON)
                .atZone(zone)
                .toInstant()
                .toEpochMilli(),
        zoneId = zone.id,
        localDateEpochDay = day.toEpochDay().toInt(),
        merchantId = merchantId,
        merchantRaw = merchantRaw,
        categoryId = categoryId,
        source = TransactionSource.RECURRING,
        status = TransactionStatus.CONFIRMED,
        reviewReason = null,
        reviewedAtUtcMillis = null,
        confidence = 1f,
        parserId = null,
        parserVersion = null,
        sourcePackage = RECURRING_SOURCE,
        sourceKey = recurringSourceKey(uuid, day),
        cardLabel = null,
        cardLast4 = null,
        note = note,
        kind = kind,
        accountId = accountId,
        toAccountId = toAccountId,
        recurringId = id,
    )
