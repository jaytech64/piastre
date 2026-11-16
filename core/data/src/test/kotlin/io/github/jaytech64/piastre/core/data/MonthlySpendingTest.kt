// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.core.data

import io.github.jaytech64.piastre.core.database.AccountEntity
import io.github.jaytech64.piastre.core.database.CategoryEntity
import io.github.jaytech64.piastre.core.database.CategoryGroupEntity
import io.github.jaytech64.piastre.core.database.ExpenseTransactionEntity
import io.github.jaytech64.piastre.core.database.PiastreDatabase
import io.github.jaytech64.piastre.core.database.TransactionPartEntity
import io.github.jaytech64.piastre.core.model.AccountOrigin
import io.github.jaytech64.piastre.core.model.AccountType
import io.github.jaytech64.piastre.core.model.Money
import io.github.jaytech64.piastre.core.model.PeriodUnit
import io.github.jaytech64.piastre.core.model.ReportPeriod
import io.github.jaytech64.piastre.core.model.TransactionKind
import io.github.jaytech64.piastre.core.model.TransactionSource
import io.github.jaytech64.piastre.core.model.TransactionStatus
import io.github.jaytech64.piastre.core.testing.inMemoryDatabase
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
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Provider

/**
 * The monthly figures a trend draws, through the real DAO and a real SQLite,
 * held to what the report of each month counts.
 */
@RunWith(RobolectricTestRunner::class)
class MonthlySpendingTest {
    private lateinit var database: PiastreDatabase
    private lateinit var reports: ReportRepository

    @Before
    fun open() {
        database = inMemoryDatabase()
        reports = ReportRepository(database.reportDao())
    }

    @After
    fun close() = database.close()

    /**
     * A trend is read through a query of its own, so this is what holds it to
     * the report: every month, for everything a trend can be asked about, adds
     * up to what the ranking of that month says.
     */
    @Test
    fun `a month of a trend is what the report of that month counts, whatever it is on`() =
        runTest {
            val food = group("Food")
            val groceries = category("Groceries", food)
            val restaurants = category("Restaurants", food)
            val transport = category("Transport", null)
            val mortgage = account(AccountType.BANK)
            spend(on = SEPTEMBER_3, amountMinor = 4_000, categoryId = groceries)
            spend(on = SEPTEMBER_3, amountMinor = 9_000, key = "dinner and groceries")
            split("dinner and groceries", 6_000L to groceries, 3_000L to restaurants)
            spend(on = SEPTEMBER_3.plusDays(9), amountMinor = 2_500, categoryId = transport)
            spend(on = SEPTEMBER_3.plusDays(10), amountMinor = 700)
            transfer(on = OCTOBER_7, amountMinor = 120_000, from = account(AccountType.CARD), into = mortgage)
            spend(on = OCTOBER_7, amountMinor = 1_500, categoryId = restaurants)
            spend(on = OCTOBER_7, amountMinor = 8_000, currency = "USD", categoryId = groceries)
            record(on = OCTOBER_7, amountMinor = 50_000, kind = TransactionKind.INCOME, key = "pay")
            record(
                on = OCTOBER_7,
                amountMinor = 6_600,
                status = TransactionStatus.NEEDS_REVIEW,
                key = "unread",
                categoryId = groceries,
            )

            val subjects =
                listOf(
                    SpentOn.Everything,
                    SpentOn.OneCategory(groceries),
                    SpentOn.OneCategory(restaurants),
                    SpentOn.OneCategory(transport),
                    SpentOn.OneGroup(food),
                    SpentOn.NoCategory,
                )
            for (month in listOf(YearMonth.of(2026, 9), YearMonth.of(2026, 10))) {
                val report =
                    reports
                        .categorySpending(
                            ReportPeriod.containing(PeriodUnit.MONTH, month.atDay(1)),
                            TODAY,
                        ).first()
                for (on in subjects) {
                    val trend =
                        reports.monthlySpending(month, month, on, TODAY).first()[month]?.total ?: Money(0, Money.CAD)
                    val counted = Money(report.filter { it.isOn(on) }.sumOf { it.total.minor }, Money.CAD)

                    assertEquals("$on in $month", counted, trend)
                }
            }
        }

    @Test
    fun `a split across two categories of a group is one transaction of that group`() =
        runTest {
            val food = group("Food")
            val groceries = category("Groceries", food)
            spend(on = SEPTEMBER_3, amountMinor = 9_000, key = "dinner and groceries")
            split("dinner and groceries", 6_000L to groceries, 3_000L to category("Restaurants", food))

            val september = YearMonth.of(2026, 9)
            val trend = reports.monthlySpending(september, september, SpentOn.OneGroup(food), TODAY).first()

            assertEquals(PeriodSpending(Money(9_000, Money.CAD), 1), trend[september])
        }

    @Test
    fun `each month counts its own days, and a month with nothing spent is absent`() =
        runTest {
            spend(on = LocalDate.of(2026, 8, 31), amountMinor = 1_000)
            spend(on = LocalDate.of(2026, 9, 1), amountMinor = 2_000)
            spend(on = LocalDate.of(2026, 9, 30), amountMinor = 3_000)
            spend(on = LocalDate.of(2026, 11, 1), amountMinor = 4_000)

            val months =
                reports
                    .monthlySpending(YearMonth.of(2026, 8), YearMonth.of(2026, 11), SpentOn.Everything, TODAY)
                    .first()

            assertEquals(
                mapOf(
                    YearMonth.of(2026, 8) to PeriodSpending(Money(1_000, Money.CAD), 1),
                    YearMonth.of(2026, 9) to PeriodSpending(Money(5_000, Money.CAD), 2),
                    YearMonth.of(2026, 11) to PeriodSpending(Money(4_000, Money.CAD), 1),
                ),
                months,
            )
        }

    @Test
    fun `a purchase dated after today counts in no month until its day`() =
        runTest {
            spend(on = OCTOBER_7, amountMinor = 1_000)
            spend(on = OCTOBER_7.plusDays(8), amountMinor = 21_240, key = "insurance")
            val october = YearMonth.of(2026, 10)

            val beforeItsDay = reports.monthlySpending(october, october, SpentOn.Everything, OCTOBER_7).first()
            val onItsDay = reports.monthlySpending(october, october, SpentOn.Everything, OCTOBER_7.plusDays(8)).first()

            assertEquals(Money(1_000, Money.CAD), beforeItsDay[october]?.total)
            assertEquals(Money(22_240, Money.CAD), onItsDay[october]?.total)
        }

    @Test
    fun `a category moved to another group takes its months along`() =
        runTest {
            val home = group("Home")
            val utilities = group("Utilities")
            val power = category("Power", home)
            spend(on = SEPTEMBER_3, amountMinor = 11_000, categoryId = power)
            val september = YearMonth.of(2026, 9)

            database.categoryDao().moveToGroup(power, utilities)

            assertEquals(
                emptyMap<YearMonth, PeriodSpending>(),
                reports.monthlySpending(september, september, SpentOn.OneGroup(home), TODAY).first(),
            )
            assertEquals(
                Money(11_000, Money.CAD),
                reports
                    .monthlySpending(
                        september,
                        september,
                        SpentOn.OneGroup(utilities),
                        TODAY,
                    ).first()[september]
                    ?.total,
            )
        }

    @Test
    fun `the ledger begins on its first confirmed row, of any kind`() =
        runTest {
            assertEquals(null, reports.firstDay().first())

            record(on = SEPTEMBER_3, amountMinor = 6_600, status = TransactionStatus.NEEDS_REVIEW, key = "unread")
            record(on = OCTOBER_7, amountMinor = 50_000, kind = TransactionKind.INCOME, key = "pay")
            spend(on = OCTOBER_7.plusDays(1), amountMinor = 1_000)

            assertEquals(OCTOBER_7, reports.firstDay().first())
        }

    private fun CategorySpending.isOn(on: SpentOn): Boolean =
        when (on) {
            SpentOn.Everything -> true
            is SpentOn.OneCategory -> category?.id == on.categoryId
            is SpentOn.OneGroup -> group?.id == on.groupId
            SpentOn.NoCategory -> category == null
        }

    private suspend fun spend(
        on: LocalDate,
        amountMinor: Long,
        currency: String = Money.CAD,
        key: String = "tag-$on-$amountMinor",
        categoryId: Long? = null,
    ) = record(on, amountMinor, currency = currency, key = key, categoryId = categoryId)

    private suspend fun record(
        on: LocalDate,
        amountMinor: Long,
        kind: TransactionKind = TransactionKind.EXPENSE,
        status: TransactionStatus = TransactionStatus.CONFIRMED,
        currency: String = Money.CAD,
        key: String = "tag-$on-$amountMinor",
        accountId: Long? = null,
        toAccountId: Long? = null,
        categoryId: Long? = null,
    ) {
        database.transactionDao().insert(
            ExpenseTransactionEntity(
                rawEventId = null,
                amountMinor = amountMinor,
                currency = currency,
                occurredAtUtcMillis = on.toEpochDay() * MILLIS_IN_DAY,
                zoneId = "America/Toronto",
                localDateEpochDay = on.toEpochDay().toInt(),
                merchantId = null,
                merchantRaw = null,
                categoryId = categoryId,
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
                accountId = accountId,
                toAccountId = toAccountId,
            ),
        )
    }

    /** Divides the row among categories, with a last part of [intoAccount]'s amount going into that account. */
    private suspend fun split(
        key: String,
        vararg parts: Pair<Long, Long?>,
        intoAccount: Pair<Long, Long>? = null,
        itsCategory: Long? = null,
    ) {
        val id = checkNotNull(database.transactionDao().bySourceKey("manual", key)).id
        val spent = parts.map { (amountMinor, categoryId) -> part(amountMinor, categoryId) }
        val movedOn = intoAccount?.let { (amountMinor, accountId) -> part(amountMinor, itsCategory, accountId) }
        database.transactionPartDao().replace(id, spent + listOfNotNull(movedOn))
    }

    private fun part(
        amountMinor: Long,
        categoryId: Long?,
        toAccountId: Long? = null,
    ) = TransactionPartEntity(
        transactionId = 0,
        position = 0,
        amountMinor = amountMinor,
        categoryId = categoryId,
        note = null,
        toAccountId = toAccountId,
    )

    private suspend fun transfer(
        on: LocalDate,
        amountMinor: Long,
        from: Long?,
        into: Long?,
        currency: String = Money.CAD,
        categoryId: Long? = null,
    ) = record(
        on,
        amountMinor,
        kind = TransactionKind.TRANSFER,
        currency = currency,
        key = "transfer-$amountMinor",
        accountId = from,
        toAccountId = into,
        categoryId = categoryId,
    )

    private suspend fun group(name: String): Long =
        database.categoryGroupDao().insert(CategoryGroupEntity(name = name, normalisedName = name.uppercase()))

    private suspend fun category(
        name: String,
        groupId: Long?,
    ): Long =
        database
            .categoryDao()
            .insert(CategoryEntity(name = name, normalisedName = name.uppercase(), groupId = groupId))

    private suspend fun account(type: AccountType): Long =
        database.accountDao().insert(
            AccountEntity(name = null, type = type, issuerLabel = null, last4 = null, origin = AccountOrigin.USER),
        )

    private companion object {
        val SEPTEMBER_3: LocalDate = LocalDate.of(2026, 9, 3)
        val OCTOBER_7: LocalDate = LocalDate.of(2026, 10, 7)

        /** Well after every month read here, so today's bound narrows none of them. */
        val TODAY: LocalDate = LocalDate.of(2026, 12, 31)
        const val MILLIS_IN_DAY = 86_400_000L
    }
}
