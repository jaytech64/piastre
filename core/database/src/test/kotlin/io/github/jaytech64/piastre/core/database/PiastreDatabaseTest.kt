// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.jaytech64.piastre.core.model.Money
import io.github.jaytech64.piastre.core.model.TransactionKind
import io.github.jaytech64.piastre.core.model.TransactionSource
import io.github.jaytech64.piastre.core.model.TransactionStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Runs on the JVM against a real SQLite, so CI proves the schema and the queries
 * work rather than only that they compile.
 */
@RunWith(RobolectricTestRunner::class)
class PiastreDatabaseTest {
    private lateinit var database: PiastreDatabase

    @Before
    fun open() {
        database =
            Room
                .inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    PiastreDatabase::class.java,
                ).allowMainThreadQueries()
                .build()
    }

    @After
    fun close() = database.close()

    @Test
    fun `a re-posted notification updates the transaction instead of adding one`() =
        runTest {
            val dao = database.transactionDao()
            dao.insertOrUpdate(transaction(amountMinor = 929))
            dao.insertOrUpdate(transaction(amountMinor = 1_299))

            assertEquals(1, dao.count().first())
            assertEquals(1_299L, dao.bySourceKey(WALLET, TAG)?.amountMinor)
        }

    @Test
    fun `a re-post keeps the time, zone and day of the first capture`() =
        runTest {
            val dao = database.transactionDao()
            dao.insertOrUpdate(transaction(amountMinor = 929))
            dao.insertOrUpdate(
                transaction(
                    amountMinor = 929,
                    day = 20_001,
                    occurredAtUtcMillis = FIRST_POST + HALF_AN_HOUR,
                    zoneId = "America/Vancouver",
                ),
            )

            val stored = dao.bySourceKey(WALLET, TAG)
            assertEquals(FIRST_POST, stored?.occurredAtUtcMillis)
            assertEquals("America/Toronto", stored?.zoneId)
            assertEquals(20_000, stored?.localDateEpochDay)
        }

    @Test
    fun `an earlier post delivered after a later one moves the purchase back to it`() =
        runTest {
            val dao = database.transactionDao()
            dao.insertOrUpdate(
                transaction(amountMinor = 929, day = 20_001, occurredAtUtcMillis = FIRST_POST + HALF_AN_HOUR),
            )
            dao.insertOrUpdate(transaction(amountMinor = 929))

            val stored = dao.bySourceKey(WALLET, TAG)
            assertEquals(FIRST_POST, stored?.occurredAtUtcMillis)
            assertEquals(20_000, stored?.localDateEpochDay)
        }

    @Test
    fun `two purchases of the same amount at the same merchant stay separate`() =
        runTest {
            val dao = database.transactionDao()
            dao.insertOrUpdate(transaction("tag-a", amountMinor = 1_881))
            dao.insertOrUpdate(transaction("tag-b", amountMinor = 1_881))

            assertEquals(2, dao.count().first())
        }

    @Test
    fun `daily totals group by local day and exclude anything not confirmed`() =
        runTest {
            val dao = database.transactionDao()
            dao.insertOrUpdate(transaction("a", amountMinor = 1_000, day = 20_000))
            dao.insertOrUpdate(transaction("b", amountMinor = 500, day = 20_000))
            dao.insertOrUpdate(transaction("c", amountMinor = 700, day = 20_001))
            dao.insertOrUpdate(
                transaction("d", amountMinor = 9_999, day = 20_000, status = TransactionStatus.NEEDS_REVIEW),
            )

            val totals = database.reportDao().dailyTotals(19_999, 20_002, Money.CAD).first()

            assertEquals(
                listOf(20_000 to 1_500L, 20_001 to 700L),
                totals.map { it.day to it.totalMinor },
            )
        }

    /** A paycheque is not spending: it must not rank beside the groceries or cancel out a week of coffee. */
    @Test
    fun `reports count what went out, never what came in`() =
        runTest {
            val merchant = MerchantEntity(displayName = "Acme inc.", normalisedName = "ACME INC")
            val id = database.merchantDao().insert(merchant)
            val dao = database.transactionDao()
            dao.insertOrUpdate(transaction("spent", amountMinor = 1_000, day = 20_000))
            dao.insertOrUpdate(
                transaction(
                    "paid",
                    amountMinor = 150_000,
                    day = 20_000,
                    merchantId = id,
                    kind = TransactionKind.INCOME,
                ),
            )

            val reports = database.reportDao()

            assertEquals(listOf(1_000L), reports.dailyTotals(19_999, 20_001, Money.CAD).first().map { it.totalMinor })
            assertEquals(1_000L, reports.totalForRange(19_999, 20_001, Money.CAD).first())
            assertEquals(listOf(1_000L), reports.categoryTotals(0, 99_999, Money.CAD).first().map { it.totalMinor })
        }

    @Test
    fun `a raw event survives with no parser able to read it`() =
        runTest {
            val dao = database.rawNotificationEventDao()
            val id =
                dao.insert(
                    RawNotificationEventEntity(
                        sourcePackage = "com.example.unknown",
                        notificationKey = "0|com.example.unknown|1|tag|10000",
                        postedAtUtcMillis = 1_788_740_166_564,
                        deviceLocale = "fr-CA",
                        zoneId = "America/Toronto",
                        title = "?",
                        text = "?",
                        bigText = null,
                        subText = null,
                        isGroupSummary = false,
                        extrasJson = "{}",
                        parseState = io.github.jaytech64.piastre.core.model.ParseState.PENDING,
                        parsedByVersion = null,
                    ),
                )
            assertNotNull(id)
            assertEquals(1, dao.count().first())
        }

    /**
     * Conversion needs a rate, a rate needs a network, and there is none — so a
     * report leaves a foreign purchase out and has to be able to say how many.
     */
    @Test
    fun `a purchase in another currency is left out of every total, and counted separately`() =
        runTest {
            val dao = database.transactionDao()
            dao.insertOrUpdate(transaction("here", amountMinor = 1_000, day = 20_000))
            dao.insertOrUpdate(transaction("away", amountMinor = 4_500, day = 20_000, currency = "USD"))

            val reports = database.reportDao()

            assertEquals(1_000L, reports.totalForRange(19_999, 20_001, Money.CAD).first())
            assertEquals(1, reports.otherCurrencyCount(19_999, 20_001, Money.CAD).first())
        }

    @Test
    fun `an unreviewed foreign purchase is not counted either, since no total omitted it`() =
        runTest {
            val dao = database.transactionDao()
            dao.insertOrUpdate(
                transaction(
                    "away",
                    amountMinor = 4_500,
                    day = 20_000,
                    status = TransactionStatus.NEEDS_REVIEW,
                    currency = "USD",
                ),
            )

            assertEquals(0, database.reportDao().otherCurrencyCount(19_999, 20_001, Money.CAD).first())
        }

    private fun transaction(
        sourceKey: String = TAG,
        amountMinor: Long,
        day: Int = 20_000,
        merchantId: Long? = null,
        status: TransactionStatus = TransactionStatus.CONFIRMED,
        kind: TransactionKind = TransactionKind.EXPENSE,
        currency: String = Money.CAD,
        occurredAtUtcMillis: Long = FIRST_POST,
        zoneId: String = "America/Toronto",
    ) = ExpenseTransactionEntity(
        rawEventId = null,
        amountMinor = amountMinor,
        currency = currency,
        occurredAtUtcMillis = occurredAtUtcMillis,
        zoneId = zoneId,
        localDateEpochDay = day,
        merchantId = merchantId,
        merchantRaw = "MARCHE ST JOSEPH  1234",
        categoryId = null,
        source = TransactionSource.NOTIFICATION,
        status = status,
        reviewReason = null,
        reviewedAtUtcMillis = null,
        confidence = 1f,
        parserId = "google-wallet",
        parserVersion = 1,
        sourcePackage = WALLET,
        sourceKey = sourceKey,
        cardLabel = "Remises Visa",
        cardLast4 = "0000",
        note = null,
        kind = kind,
    )

    private companion object {
        const val WALLET = "com.google.android.apps.walletnfcrel"
        const val TAG = "tag-1"
        const val FIRST_POST = 1_788_740_166_564L
        const val HALF_AN_HOUR = 1_800_000L
    }
}
