// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.data

import io.github.jaytech64.depenses.core.database.DepensesDatabase
import io.github.jaytech64.depenses.core.database.ExpenseTransactionEntity
import io.github.jaytech64.depenses.core.database.MerchantEntity
import io.github.jaytech64.depenses.core.model.Money
import io.github.jaytech64.depenses.core.model.PeriodUnit
import io.github.jaytech64.depenses.core.model.ReportPeriod
import io.github.jaytech64.depenses.core.model.TransactionKind
import io.github.jaytech64.depenses.core.model.TransactionSource
import io.github.jaytech64.depenses.core.model.TransactionStatus
import io.github.jaytech64.depenses.core.testing.inMemoryDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

/**
 * Through the real DAO and a real SQLite, because what these queries have to get
 * right is what a fake DAO cannot fail: the `WHERE` clauses that decide a report
 * is about spending and nothing else.
 */
@RunWith(RobolectricTestRunner::class)
class ReportRepositoryTest {
    private lateinit var database: DepensesDatabase
    private lateinit var reports: ReportRepository

    @Before
    fun open() {
        database = inMemoryDatabase()
        reports = ReportRepository(database.reportDao())
    }

    @After
    fun close() = database.close()

    @Test
    fun `a week totals the days inside it and ignores the days on either side`() =
        runTest {
            spend(on = MONDAY.minusDays(1), amountMinor = 9_999)
            spend(on = MONDAY, amountMinor = 1_000)
            spend(on = MONDAY.plusDays(3), amountMinor = 2_500)
            spend(on = SUNDAY, amountMinor = 500)
            spend(on = SUNDAY.plusDays(1), amountMinor = 9_999)

            val spending = reports.spending(week()).first()

            assertEquals(Money(4_000, Money.CAD), spending.total)
            assertEquals(3, spending.count)
        }

    @Test
    fun `an income is not spending, and neither is moving your own money`() =
        runTest {
            spend(on = MONDAY, amountMinor = 1_000)
            record(on = MONDAY, amountMinor = 150_000, kind = TransactionKind.INCOME, key = "pay")
            record(on = MONDAY, amountMinor = 6_000, kind = TransactionKind.TRANSFER, key = "move")

            assertEquals(Money(1_000, Money.CAD), reports.spending(week()).first().total)
        }

    @Test
    fun `a purchase still waiting for review moves no total`() =
        runTest {
            spend(on = MONDAY, amountMinor = 1_000)
            record(on = MONDAY, amountMinor = 8_000, status = TransactionStatus.NEEDS_REVIEW, key = "unsure")

            val spending = reports.spending(week()).first()

            assertEquals(Money(1_000, Money.CAD), spending.total)
            assertEquals(1, spending.count)
        }

    @Test
    fun `a day with no spending is absent rather than zero`() =
        runTest {
            spend(on = MONDAY, amountMinor = 1_000)
            spend(on = SUNDAY, amountMinor = 500)

            val days = reports.dailyTotals(week()).first()

            assertEquals(setOf(MONDAY, SUNDAY), days.keys)
            assertEquals(Money(1_000, Money.CAD), days[MONDAY])
        }

    @Test
    fun `a period nothing happened in totals zero rather than failing`() =
        runTest {
            val spending = reports.spending(week()).first()

            assertEquals(Money(0, Money.CAD), spending.total)
            assertEquals(0, spending.count)
            assertEquals(Money(0, Money.CAD), reports.total(week()).first())
            assertTrue(reports.dailyTotals(week()).first().isEmpty())
            assertTrue(reports.topMerchants(week(), limit = 5).first().isEmpty())
        }

    @Test
    fun `merchants rank heaviest first, and a purchase with no merchant ranks nowhere`() =
        runTest {
            val grocer = merchant("Metro Plus", "METRO PLUS")
            val cafe = merchant("Café Olimpico", "CAFE OLIMPICO")
            spend(on = MONDAY, amountMinor = 2_342, merchantId = grocer, key = "a")
            spend(on = SUNDAY, amountMinor = 4_078, merchantId = grocer, key = "b")
            spend(on = MONDAY, amountMinor = 625, merchantId = cafe, key = "c")
            spend(on = MONDAY, amountMinor = 5_000, merchantId = null, key = "nameless")

            val ranking = reports.topMerchants(week(), limit = 5).first()

            assertEquals(listOf("Metro Plus", "Café Olimpico"), ranking.map { it.name })
            assertEquals(Money(6_420, Money.CAD), ranking.first().total)
            assertEquals(2, ranking.first().count)
        }

    @Test
    fun `the ranking stops at the limit it was asked for`() =
        runTest {
            repeat(6) { index ->
                spend(
                    on = MONDAY,
                    amountMinor = (index + 1) * 1_000L,
                    merchantId = merchant("Merchant $index", "MERCHANT $index"),
                    key = "m$index",
                )
            }

            assertEquals(5, reports.topMerchants(week(), limit = 5).first().size)
        }

    @Test
    fun `a purchase in another currency is in no total, and is counted so a screen can say so`() =
        runTest {
            spend(on = MONDAY, amountMinor = 1_000)
            spend(on = MONDAY, amountMinor = 4_500, currency = "USD", key = "abroad")

            assertEquals(Money(1_000, Money.CAD), reports.spending(week()).first().total)
            assertEquals(1, reports.otherCurrencyCount(week()).first())
        }

    @Test
    fun `a month covers its own days and no neighbour's`() =
        runTest {
            spend(on = LocalDate.of(2026, 8, 31), amountMinor = 9_999)
            spend(on = LocalDate.of(2026, 9, 1), amountMinor = 1_000)
            spend(on = LocalDate.of(2026, 9, 30), amountMinor = 2_000, key = "last")
            spend(on = LocalDate.of(2026, 10, 1), amountMinor = 9_999, key = "next")

            val september = ReportPeriod.containing(PeriodUnit.MONTH, LocalDate.of(2026, 9, 16))

            assertEquals(Money(3_000, Money.CAD), reports.spending(september).first().total)
        }

    private suspend fun spend(
        on: LocalDate,
        amountMinor: Long,
        merchantId: Long? = null,
        currency: String = Money.CAD,
        key: String = "tag-$on-$amountMinor",
    ) = record(on, amountMinor, merchantId = merchantId, currency = currency, key = key)

    private suspend fun record(
        on: LocalDate,
        amountMinor: Long,
        kind: TransactionKind = TransactionKind.EXPENSE,
        status: TransactionStatus = TransactionStatus.CONFIRMED,
        merchantId: Long? = null,
        currency: String = Money.CAD,
        key: String = "tag-$on-$amountMinor",
    ) {
        database.transactionDao().insert(
            ExpenseTransactionEntity(
                rawEventId = null,
                amountMinor = amountMinor,
                currency = currency,
                occurredAtUtcMillis = on.toEpochDay() * MILLIS_IN_DAY,
                zoneId = "America/Toronto",
                localDateEpochDay = on.toEpochDay().toInt(),
                merchantId = merchantId,
                merchantRaw = null,
                categoryId = null,
                source = TransactionSource.MANUAL,
                status = status,
                reviewReason = null,
                reviewedAtUtcMillis = null,
                confidence = 1f,
                parserId = null,
                parserVersion = null,
                sourcePackage = "manual",
                sourceKey = key,
                cardLabel = null,
                cardLast4 = null,
                note = null,
                kind = kind,
            ),
        )
    }

    private suspend fun merchant(
        displayName: String,
        normalisedName: String,
    ): Long = database.merchantDao().insert(MerchantEntity(displayName = displayName, normalisedName = normalisedName))

    private fun week() = ReportPeriod.containing(PeriodUnit.WEEK, MONDAY)

    private companion object {
        val MONDAY: LocalDate = LocalDate.of(2026, 9, 14)
        val SUNDAY: LocalDate = LocalDate.of(2026, 9, 20)
        const val MILLIS_IN_DAY = 86_400_000L
    }
}
