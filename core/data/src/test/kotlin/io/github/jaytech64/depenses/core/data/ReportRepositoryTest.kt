// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.data

import io.github.jaytech64.depenses.core.database.AccountEntity
import io.github.jaytech64.depenses.core.database.CategoryEntity
import io.github.jaytech64.depenses.core.database.CategoryGroupEntity
import io.github.jaytech64.depenses.core.database.DepensesDatabase
import io.github.jaytech64.depenses.core.database.ExpenseTransactionEntity
import io.github.jaytech64.depenses.core.model.AccountOrigin
import io.github.jaytech64.depenses.core.model.AccountType
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
import java.time.ZoneId
import javax.inject.Provider

/**
 * Through the real DAO and a real SQLite, because what these queries have to get
 * right is what a fake DAO cannot fail: the `WHERE` clauses that decide which
 * rows are spending, which are income, and which are neither.
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

            val spending = reports.spending(week(), TODAY).first()

            assertEquals(Money(4_000, Money.CAD), spending.total)
            assertEquals(3, spending.count)
        }

    @Test
    fun `an income is not spending, and neither is moving your own money`() =
        runTest {
            spend(on = MONDAY, amountMinor = 1_000)
            record(on = MONDAY, amountMinor = 150_000, kind = TransactionKind.INCOME, key = "pay")
            record(on = MONDAY, amountMinor = 6_000, kind = TransactionKind.TRANSFER, key = "move")

            assertEquals(Money(1_000, Money.CAD), reports.spending(week(), TODAY).first().total)
        }

    @Test
    fun `a paycheque is income, and neither a purchase nor moving your own money is`() =
        runTest {
            spend(on = MONDAY, amountMinor = 1_000)
            record(on = MONDAY, amountMinor = 150_000, kind = TransactionKind.INCOME, key = "pay")
            record(on = SUNDAY, amountMinor = 2_500, kind = TransactionKind.INCOME, key = "refund")
            record(on = MONDAY, amountMinor = 6_000, kind = TransactionKind.TRANSFER, key = "move")
            record(on = SUNDAY.plusDays(1), amountMinor = 9_999, kind = TransactionKind.INCOME, key = "next week")

            assertEquals(Money(152_500, Money.CAD), reports.income(week(), TODAY).first())
        }

    @Test
    fun `a transfer into a bank account is spending, and one back out of it is income`() =
        runTest {
            val card = account(AccountType.CARD)
            val mortgage = account(AccountType.BANK)
            spend(on = MONDAY, amountMinor = 1_000)
            transfer(on = MONDAY, amountMinor = 120_000, from = card, into = mortgage)
            transfer(on = SUNDAY, amountMinor = 30_000, from = mortgage, into = account(AccountType.CASH))

            val spending = reports.spending(week(), TODAY).first()

            assertEquals(Money(121_000, Money.CAD), spending.total)
            assertEquals(2, spending.count)
            assertEquals(Money(121_000, Money.CAD), reports.total(week(), TODAY).first())
            assertEquals(Money(121_000, Money.CAD), reports.dailyTotals(week(), TODAY).first()[MONDAY])
            assertEquals(Money(30_000, Money.CAD), reports.income(week(), TODAY).first())
        }

    @Test
    fun `a transfer that stays among cards and cash, or among bank accounts, is in no figure`() =
        runTest {
            val card = account(AccountType.CARD)
            val bank = account(AccountType.BANK)
            transfer(on = MONDAY, amountMinor = 90_000, from = card, into = account(AccountType.CARD))
            transfer(on = MONDAY, amountMinor = 6_000, from = card, into = account(AccountType.CASH))
            transfer(on = MONDAY, amountMinor = 50_000, from = bank, into = account(AccountType.BANK))
            transfer(on = MONDAY, amountMinor = 7_000, from = null, into = bank)
            transfer(on = MONDAY, amountMinor = 8_000, from = bank, into = null)

            assertEquals(Money(0, Money.CAD), reports.spending(week(), TODAY).first().total)
            assertEquals(Money(0, Money.CAD), reports.income(week(), TODAY).first())
        }

    /**
     * The rule is written twice, in SQL here and in `Transaction.signedAmount`
     * for the ledger, and the two screens show the same month one tap apart.
     */
    @Test
    fun `a report and the ledger agree to the cent on every pair of accounts`() =
        runTest {
            val kinds: List<AccountType?> = AccountType.entries + null
            var amountMinor = 100L
            for (from in kinds) {
                for (into in kinds) {
                    amountMinor *= 2
                    transfer(
                        on = MONDAY,
                        amountMinor = amountMinor,
                        from = from?.let { account(it) },
                        into = into?.let { account(it) },
                    )
                }
            }
            spend(on = MONDAY, amountMinor = 1_000)
            record(on = MONDAY, amountMinor = 150_000, kind = TransactionKind.INCOME, key = "pay")

            val ledger = transactionRepository().confirmed().first().sumOf { it.signedAmount.minor }
            val report =
                reports.income(week(), TODAY).first().minor -
                    reports
                        .spending(week(), TODAY)
                        .first()
                        .total.minor

            assertEquals(ledger, report)
        }

    @Test
    fun `an income still waiting for review or in another currency is not counted as income`() =
        runTest {
            record(on = MONDAY, amountMinor = 150_000, kind = TransactionKind.INCOME, key = "pay")
            record(
                on = MONDAY,
                amountMinor = 8_000,
                kind = TransactionKind.INCOME,
                status = TransactionStatus.NEEDS_REVIEW,
                key = "unsure",
            )
            record(on = MONDAY, amountMinor = 40_000, kind = TransactionKind.INCOME, currency = "USD", key = "abroad")

            assertEquals(Money(150_000, Money.CAD), reports.income(week(), TODAY).first())
        }

    @Test
    fun `a purchase still waiting for review moves no total`() =
        runTest {
            spend(on = MONDAY, amountMinor = 1_000)
            record(on = MONDAY, amountMinor = 8_000, status = TransactionStatus.NEEDS_REVIEW, key = "unsure")

            val spending = reports.spending(week(), TODAY).first()

            assertEquals(Money(1_000, Money.CAD), spending.total)
            assertEquals(1, spending.count)
        }

    @Test
    fun `a day with no spending is absent rather than zero`() =
        runTest {
            spend(on = MONDAY, amountMinor = 1_000)
            spend(on = SUNDAY, amountMinor = 500)

            val days = reports.dailyTotals(week(), TODAY).first()

            assertEquals(setOf(MONDAY, SUNDAY), days.keys)
            assertEquals(Money(1_000, Money.CAD), days[MONDAY])
        }

    @Test
    fun `a period nothing happened in totals zero rather than failing`() =
        runTest {
            val spending = reports.spending(week(), TODAY).first()

            assertEquals(Money(0, Money.CAD), spending.total)
            assertEquals(0, spending.count)
            assertEquals(Money(0, Money.CAD), reports.total(week(), TODAY).first())
            assertEquals(Money(0, Money.CAD), reports.income(week(), TODAY).first())
            assertTrue(reports.dailyTotals(week(), TODAY).first().isEmpty())
            assertTrue(reports.categorySpending(week(), TODAY).first().isEmpty())
        }

    @Test
    fun `what went to each category adds up to the period, with what has no category on a row of its own`() =
        runTest {
            val home = group("Maison")
            val power = category("Électricité", home)
            val gifts = category("Cadeaux", null)
            spend(on = MONDAY, amountMinor = 6_400, categoryId = power, key = "hydro")
            spend(on = SUNDAY, amountMinor = 2_010, categoryId = power, key = "hydro again")
            spend(on = MONDAY, amountMinor = 9_000, categoryId = gifts)
            spend(on = MONDAY, amountMinor = 8_000)
            spend(on = SUNDAY.plusDays(1), amountMinor = 9_999, categoryId = power, key = "next week")

            val spending = reports.categorySpending(week(), TODAY).first().associateBy { it.category?.name }

            assertEquals(setOf("Électricité", "Cadeaux", null), spending.keys)
            assertEquals(Money(8_410, Money.CAD), spending.getValue("Électricité").total)
            assertEquals(2, spending.getValue("Électricité").count)
            assertEquals("Maison", spending.getValue("Électricité").group?.name)
            assertEquals(null, spending.getValue("Cadeaux").group)
            assertEquals(Money(8_000, Money.CAD), spending.getValue(null).total)
            assertEquals(
                reports
                    .spending(week(), TODAY)
                    .first()
                    .total.minor,
                spending.values.sumOf { it.total.minor },
            )
        }

    @Test
    fun `a transfer into a bank account goes to its category, and a card payment to none`() =
        runTest {
            val card = account(AccountType.CARD)
            val mortgage = category("Hypothèque", group("Maison"))
            val payments = category("Paiements de carte", null)
            transfer(
                on = MONDAY,
                amountMinor = 120_000,
                from = card,
                into = account(AccountType.BANK),
                categoryId = mortgage,
            )
            transfer(
                on = MONDAY,
                amountMinor = 45_480,
                from = card,
                into = account(AccountType.CARD),
                categoryId = payments,
            )
            record(on = MONDAY, amountMinor = 150_000, kind = TransactionKind.INCOME, key = "pay")

            val spending = reports.categorySpending(week(), TODAY).first().single()

            assertEquals("Hypothèque", spending.category?.name)
            assertEquals(Money(120_000, Money.CAD), spending.total)
        }

    @Test
    fun `a category moved to another group takes what went to it along`() =
        runTest {
            val power = category("Électricité", group("Maison"))
            spend(on = MONDAY, amountMinor = 6_400, categoryId = power)

            CategoryGroupRepository(database.categoryGroupDao(), database.categoryDao()).move(power, group("Services"))

            assertEquals(
                "Services",
                reports
                    .categorySpending(week(), TODAY)
                    .first()
                    .single()
                    .group
                    ?.name,
            )
        }

    @Test
    fun `a purchase in another currency is in no total, and is counted so a screen can say so`() =
        runTest {
            spend(on = MONDAY, amountMinor = 1_000)
            spend(on = MONDAY, amountMinor = 4_500, currency = "USD", key = "abroad")

            assertEquals(Money(1_000, Money.CAD), reports.spending(week(), TODAY).first().total)
            assertEquals(1, reports.otherCurrencyCount(week(), TODAY).first())
        }

    @Test
    fun `a paycheque in another currency is counted as left out, and moving your own money is not`() =
        runTest {
            record(on = MONDAY, amountMinor = 40_000, kind = TransactionKind.INCOME, currency = "USD", key = "pay")
            record(on = MONDAY, amountMinor = 6_000, kind = TransactionKind.TRANSFER, currency = "USD", key = "move")

            assertEquals(1, reports.otherCurrencyCount(week(), TODAY).first())
        }

    @Test
    fun `a transfer into a bank account in another currency is counted as left out`() =
        runTest {
            transfer(
                on = MONDAY,
                amountMinor = 6_000,
                from = account(AccountType.CARD),
                into = account(AccountType.BANK),
                currency = "USD",
            )

            assertEquals(Money(0, Money.CAD), reports.spending(week(), TODAY).first().total)
            assertEquals(1, reports.otherCurrencyCount(week(), TODAY).first())
        }

    @Test
    fun `a month covers its own days and no neighbour's`() =
        runTest {
            spend(on = LocalDate.of(2026, 8, 31), amountMinor = 9_999)
            spend(on = LocalDate.of(2026, 9, 1), amountMinor = 1_000)
            spend(on = LocalDate.of(2026, 9, 30), amountMinor = 2_000, key = "last")
            spend(on = LocalDate.of(2026, 10, 1), amountMinor = 9_999, key = "next")

            val september = ReportPeriod.containing(PeriodUnit.MONTH, LocalDate.of(2026, 9, 16))

            assertEquals(Money(3_000, Money.CAD), reports.spending(september, TODAY).first().total)
        }

    @Test
    fun `a purchase dated after today counts from its day, not before`() =
        runTest {
            spend(on = LocalDate.of(2026, 9, 29), amountMinor = 1_000)
            spend(on = LocalDate.of(2026, 10, 15), amountMinor = 21_240, key = "insurance")
            val october = ReportPeriod.containing(PeriodUnit.MONTH, LocalDate.of(2026, 10, 1))
            val september = ReportPeriod.containing(PeriodUnit.MONTH, LocalDate.of(2026, 9, 1))

            assertEquals(Money(0, Money.CAD), reports.spending(october, LocalDate.of(2026, 10, 14)).first().total)
            assertEquals(Money(21_240, Money.CAD), reports.spending(october, LocalDate.of(2026, 10, 15)).first().total)
            assertEquals(Money(1_000, Money.CAD), reports.spending(september, LocalDate.of(2026, 9, 30)).first().total)
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

    private fun transactionRepository(): TransactionRepository {
        val merchants = MerchantRepository(database.merchantDao())
        val categories = CategoryRepository(database.categoryDao(), database.merchantDao(), database.accountDao())
        return TransactionRepository(database, database.transactionDao(), merchants, categories, Provider { ZONE })
    }

    private fun week() = ReportPeriod.containing(PeriodUnit.WEEK, MONDAY)

    private companion object {
        val MONDAY: LocalDate = LocalDate.of(2026, 9, 14)
        val SUNDAY: LocalDate = LocalDate.of(2026, 9, 20)

        /** Well after every period read here, so today's bound narrows none of them. */
        val TODAY: LocalDate = LocalDate.of(2026, 12, 31)
        val ZONE: ZoneId = ZoneId.of("America/Toronto")
        const val MILLIS_IN_DAY = 86_400_000L
    }
}
