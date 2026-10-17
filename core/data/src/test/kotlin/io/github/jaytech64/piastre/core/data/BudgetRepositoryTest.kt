// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.core.data

import io.github.jaytech64.piastre.core.database.CategoryEntity
import io.github.jaytech64.piastre.core.database.CategoryGroupEntity
import io.github.jaytech64.piastre.core.database.ExpenseTransactionEntity
import io.github.jaytech64.piastre.core.database.PiastreDatabase
import io.github.jaytech64.piastre.core.model.BudgetTarget
import io.github.jaytech64.piastre.core.model.Money
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

/** Through the real report queries, since a budget must count exactly what the category ranking counts. */
@RunWith(RobolectricTestRunner::class)
class BudgetRepositoryTest {
    private lateinit var database: PiastreDatabase
    private lateinit var budgets: BudgetRepository
    private var home = 0L
    private var groceries = 0L
    private var hardware = 0L

    @Before
    fun open() =
        runTest {
            database = inMemoryDatabase()
            budgets =
                BudgetRepository(
                    database.budgetDao(),
                    ReportRepository(database.reportDao()),
                    CategoryRepository(database.categoryDao(), database.merchantDao(), database.accountDao()),
                    CategoryGroupRepository(database.categoryGroupDao(), database.categoryDao()),
                )
            home = database.categoryGroupDao().insert(CategoryGroupEntity(name = "Maison", normalisedName = "MAISON"))
            groceries =
                database.categoryDao().insert(
                    CategoryEntity(name = "Épicerie", normalisedName = "EPICERIE", groupId = home),
                )
            hardware =
                database.categoryDao().insert(
                    CategoryEntity(name = "Quincaillerie", normalisedName = "QUINCAILLERIE", groupId = home),
                )
        }

    @After
    fun close() = database.close()

    @Test
    fun `a budget is judged against what went out in its category that month`() =
        runTest {
            budgets.set(BudgetTarget.Category(groceries), cad(40_000), OCTOBER)
            spend(OCTOBER.atDay(3), 12_000, groceries)
            spend(OCTOBER.atDay(10), 8_000, groceries)
            spend(SEPTEMBER.atDay(28), 9_999, groceries)
            record(OCTOBER.atDay(4), 150_000, groceries, kind = TransactionKind.INCOME)

            val line =
                budgets
                    .month(OCTOBER, TODAY)
                    .first()
                    .lines
                    .single()

            assertEquals(cad(20_000), line.spent)
            assertEquals(cad(20_000), line.left)
        }

    @Test
    fun `a group's budget counts every category in it, and each category's budget stays its own`() =
        runTest {
            budgets.set(BudgetTarget.Group(home), cad(100_000), OCTOBER)
            budgets.set(BudgetTarget.Category(groceries), cad(40_000), OCTOBER)
            spend(OCTOBER.atDay(3), 12_000, groceries)
            spend(OCTOBER.atDay(5), 30_000, hardware)

            val lines =
                budgets
                    .month(OCTOBER, TODAY)
                    .first()
                    .lines
                    .associateBy { it.target }

            assertEquals(cad(42_000), lines.getValue(BudgetTarget.Group(home)).spent)
            assertEquals(cad(12_000), lines.getValue(BudgetTarget.Category(groceries)).spent)
        }

    @Test
    fun `nothing waiting for review and nothing dated after today counts against a budget`() =
        runTest {
            budgets.set(BudgetTarget.Category(groceries), cad(40_000), OCTOBER)
            spend(OCTOBER.atDay(3), 12_000, groceries)
            record(OCTOBER.atDay(4), 5_000, groceries, status = TransactionStatus.NEEDS_REVIEW)
            spend(TODAY.plusDays(1), 7_000, groceries)

            assertEquals(
                cad(12_000),
                budgets
                    .month(OCTOBER, TODAY)
                    .first()
                    .lines
                    .single()
                    .spent,
            )
        }

    @Test
    fun `money in another currency is counted aside, never added`() =
        runTest {
            budgets.set(BudgetTarget.Category(groceries), cad(40_000), OCTOBER)
            spend(OCTOBER.atDay(3), 12_000, groceries)
            record(OCTOBER.atDay(4), 745, groceries, currency = "USD")

            val month = budgets.month(OCTOBER, TODAY).first()

            assertEquals(cad(12_000), month.lines.single().spent)
            assertEquals(1, month.otherCurrencyCount)
        }

    @Test
    fun `a stopped budget is gone from that month and kept in the ones before`() =
        runTest {
            budgets.set(BudgetTarget.Category(groceries), cad(40_000), SEPTEMBER)
            budgets.stop(BudgetTarget.Category(groceries), OCTOBER)

            assertEquals(
                1,
                budgets
                    .month(SEPTEMBER, TODAY)
                    .first()
                    .lines.size,
            )
            assertTrue(
                budgets
                    .month(OCTOBER, TODAY)
                    .first()
                    .lines
                    .isEmpty(),
            )
        }

    @Test
    fun `a deleted category takes its budgets with it`() =
        runTest {
            budgets.set(BudgetTarget.Category(groceries), cad(40_000), SEPTEMBER)
            database.categoryDao().delete(groceries)

            assertTrue(budgets.changes().first().isEmpty())
        }

    private fun cad(minor: Long) = Money(minor, Money.CAD)

    private suspend fun spend(
        on: LocalDate,
        amountMinor: Long,
        categoryId: Long,
    ) = record(on, amountMinor, categoryId)

    private suspend fun record(
        on: LocalDate,
        amountMinor: Long,
        categoryId: Long,
        kind: TransactionKind = TransactionKind.EXPENSE,
        status: TransactionStatus = TransactionStatus.CONFIRMED,
        currency: String = Money.CAD,
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
                sourceKey = "tag-$on-$amountMinor-$currency-$status-$kind",
                cardLabel = null,
                cardLast4 = null,
                note = null,
                kind = kind,
                accountId = null,
                toAccountId = null,
            ),
        )
    }

    private companion object {
        val SEPTEMBER: YearMonth = YearMonth.of(2026, 9)
        val OCTOBER: YearMonth = YearMonth.of(2026, 10)
        val TODAY: LocalDate = LocalDate.of(2026, 10, 15)
        const val MILLIS_IN_DAY = 86_400_000L
    }
}
