// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.feature.reports

import android.app.Activity
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import io.github.jaytech64.piastre.core.data.BudgetRepository
import io.github.jaytech64.piastre.core.data.CategoryGroupRepository
import io.github.jaytech64.piastre.core.data.CategoryRepository
import io.github.jaytech64.piastre.core.data.LocalDateTicker
import io.github.jaytech64.piastre.core.data.MerchantRepository
import io.github.jaytech64.piastre.core.data.PremiumOffer
import io.github.jaytech64.piastre.core.data.PremiumOffers
import io.github.jaytech64.piastre.core.data.PremiumRepository
import io.github.jaytech64.piastre.core.data.PremiumSource
import io.github.jaytech64.piastre.core.data.ReportRepository
import io.github.jaytech64.piastre.core.data.SpentOn
import io.github.jaytech64.piastre.core.data.TransactionRepository
import io.github.jaytech64.piastre.core.database.CategoryEntity
import io.github.jaytech64.piastre.core.database.CategoryGroupEntity
import io.github.jaytech64.piastre.core.database.ExpenseTransactionEntity
import io.github.jaytech64.piastre.core.database.PiastreDatabase
import io.github.jaytech64.piastre.core.model.BudgetTarget
import io.github.jaytech64.piastre.core.model.Days
import io.github.jaytech64.piastre.core.model.Money
import io.github.jaytech64.piastre.core.model.Premium
import io.github.jaytech64.piastre.core.model.TransactionKind
import io.github.jaytech64.piastre.core.model.TransactionSource
import io.github.jaytech64.piastre.core.model.TransactionStatus
import io.github.jaytech64.piastre.core.testing.inMemoryDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Provider

@RunWith(RobolectricTestRunner::class)
class TrendsViewModelTest {
    private lateinit var database: PiastreDatabase
    private lateinit var viewModel: TrendsViewModel
    private val storeFile: File = File.createTempFile("settings", ".preferences_pb").apply { delete() }
    private val premium by lazy { PremiumRepository(UnlockedBuild, PreferenceDataStoreFactory.create { storeFile }) }

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        database = inMemoryDatabase()
        viewModel = trendsViewModel(SavedStateHandle())
    }

    @After
    fun tearDown() {
        database.close()
        storeFile.delete()
        Dispatchers.resetMain()
    }

    @Test
    fun `a year ends with the month you are living in, counted so far`() =
        runTest {
            spend(5_000, TODAY)

            val months = year().months

            assertEquals(12, months.size)
            assertEquals(YearMonth.of(2025, 12), months.first().month)
            assertEquals(NOVEMBER, months.last().month)
            assertTrue(months.last().running)
            assertEquals(Money(5_000, Money.CAD), months.last().spent)
        }

    @Test
    fun `a month before the ledger began is not yet, and one after it with nothing spent is zero`() =
        runTest {
            spend(4_000, SEPTEMBER.atDay(3))

            val months = year().months.associateBy { it.month }

            assertNull(months.getValue(AUGUST).spent)
            assertEquals(Money(4_000, Money.CAD), months.getValue(SEPTEMBER).spent)
            assertEquals(Money(0, Money.CAD), months.getValue(OCTOBER).spent)
        }

    @Test
    fun `the average counts the complete months and leaves the running month out`() =
        runTest {
            spend(10_000, SEPTEMBER.atDay(3))
            spend(30_000, OCTOBER.atDay(3))
            spend(5_000, TODAY)

            val year = year()

            assertEquals(Money(20_000, Money.CAD), year.average)
            assertEquals(2, year.completeMonths)
        }

    @Test
    fun `a ledger that began this month has no complete month to average`() =
        runTest {
            spend(5_000, TODAY)

            assertNull(year().average)
        }

    @Test
    fun `a month stands beside the same month a year earlier, once the ledger reaches back to it`() =
        runTest {
            spend(20_000, OCTOBER.minusYears(1).atDay(3))
            spend(30_000, OCTOBER.atDay(3))

            val months = year().months.associateBy { it.month }

            assertEquals(Money(20_000, Money.CAD), months.getValue(OCTOBER).yearBefore)
            assertEquals(Comparison.Change(50), months.getValue(OCTOBER).comparison)
            assertNull(months.getValue(SEPTEMBER).yearBefore)
        }

    @Test
    fun `the running month is not compared with a whole month a year before`() =
        runTest {
            spend(40_000, NOVEMBER.minusYears(1).atDay(3))
            spend(5_000, TODAY)

            val november = year().months.last()

            assertEquals(Money(40_000, Money.CAD), november.yearBefore)
            assertNull(november.comparison)
        }

    @Test
    fun `a budget is drawn on the months it judged, from the month it was set`() =
        runTest {
            val groceries = category("Groceries", null)
            spend(10_000, SEPTEMBER.atDay(3), categoryId = groceries)
            budgets().set(BudgetTarget.Category(groceries), Money(25_000, Money.CAD), OCTOBER)
            viewModel.onSpentOnSelected(SpentOn.OneCategory(groceries))

            val months =
                viewModel.uiState
                    .first {
                        it.on == SpentOn.OneCategory(groceries) && it.year != null
                    }.year!!
                    .months
                    .associateBy { it.month }

            assertNull(months.getValue(SEPTEMBER).budget)
            assertEquals(Money(25_000, Money.CAD), months.getValue(OCTOBER).budget)
            assertEquals(Money(25_000, Money.CAD), months.getValue(NOVEMBER).budget)
        }

    @Test
    fun `a year steps back as far as the ledger reaches, and forward to this one`() =
        runTest {
            spend(1_000, OCTOBER.minusYears(1).atDay(3))
            val now = year()
            assertTrue(now.canStepBack)
            assertFalse(now.canStepForward)

            viewModel.onStepBack()
            val before = viewModel.uiState.first { it.year?.end == NOVEMBER.minusYears(1) }.year!!

            assertFalse(before.canStepBack)
            assertTrue(before.canStepForward)
            assertEquals(Money(1_000, Money.CAD), before.months.single { it.month == OCTOBER.minusYears(1) }.spent)
        }

    @Test
    fun `the page opens on the group a ranking opened it on`() =
        runTest {
            val food = group("Food")
            viewModel = trendsViewModel(SavedStateHandle(mapOf(TRENDS_ON_ARG to "group", TRENDS_ID_ARG to food)))

            assertEquals(SpentOn.OneGroup(food), viewModel.uiState.first { it.year != null }.on)
        }

    @Test
    fun `a category deleted since it was chosen falls back to all spending`() =
        runTest {
            viewModel = trendsViewModel(SavedStateHandle(mapOf(TRENDS_ON_ARG to "category", TRENDS_ID_ARG to 999L)))

            assertEquals(SpentOn.Everything, viewModel.uiState.first { it.year != null }.on)
        }

    @Test
    fun `a comparison opens on last month against this one so far`() =
        runTest {
            val compare = compare()

            assertEquals(Days(OCTOBER.atDay(1), OCTOBER.atEndOfMonth()), compare.first)
            assertEquals(Days(NOVEMBER.atDay(1), TODAY), compare.second)
        }

    @Test
    fun `two spans of different lengths are compared per day, and their totals stay as they are`() =
        runTest {
            spend(31_000, OCTOBER.atDay(3))
            spend(16_000, TODAY)

            val compare = compare()

            assertTrue(compare.perDay)
            assertEquals(Money(31_000, Money.CAD), compare.everything.first)
            assertEquals(Money(16_000, Money.CAD), compare.everything.second)
            assertEquals(Comparison.Unchanged, compare.everything.change)
        }

    @Test
    fun `a group in a comparison adds up its categories, and what has no category comes last`() =
        runTest {
            val food = group("Food")
            val groceries = category("Groceries", food)
            val restaurants = category("Restaurants", food)
            spend(30_000, OCTOBER.atDay(3), categoryId = groceries)
            spend(5_000, OCTOBER.atDay(4), categoryId = restaurants)
            spend(9_000, TODAY, categoryId = restaurants)
            spend(700, TODAY)

            val rows = compare().rows

            val foodRow = rows.first()
            assertEquals(food, foodRow.groupId)
            assertEquals(Money(35_000, Money.CAD), foodRow.first)
            assertEquals(Money(9_000, Money.CAD), foodRow.second)
            assertEquals(listOf(groceries, restaurants), foodRow.categories.map { it.categoryId })
            assertNull(rows.last().name)
            assertEquals(Money(700, Money.CAD), rows.last().second)
            assertEquals(Comparison.NothingBefore, rows.last().change)
        }

    @Test
    fun `a span can be chosen, and a shortcut puts this month beside the same month a year before`() =
        runTest {
            compare()
            val week = Days(TODAY.minusDays(6), TODAY)

            viewModel.onSpanPicked(SpanEnd.SECOND, week)
            assertEquals(
                week,
                viewModel.uiState
                    .first { it.compare?.second == week }
                    .compare!!
                    .second,
            )

            viewModel.onShortcut(CompareShortcut.A_YEAR_EARLIER)
            val yearBefore = Days(NOVEMBER.minusYears(1).atDay(1), NOVEMBER.minusYears(1).atEndOfMonth())
            val compare = viewModel.uiState.first { it.compare?.first == yearBefore }.compare!!
            assertEquals(Days(NOVEMBER.atDay(1), TODAY), compare.second)
        }

    private suspend fun year(): TrendYear = viewModel.uiState.first { it.year != null }.year!!

    private suspend fun compare(): TrendsCompare = viewModel.uiState.first { it.compare != null }.compare!!

    private suspend fun spend(
        amountMinor: Long,
        on: LocalDate,
        categoryId: Long? = null,
    ) {
        database.transactionDao().insert(
            ExpenseTransactionEntity(
                rawEventId = null,
                amountMinor = amountMinor,
                currency = Money.CAD,
                occurredAtUtcMillis =
                    on
                        .atTime(NOON, 0)
                        .atZone(ZONE)
                        .toInstant()
                        .toEpochMilli(),
                zoneId = ZONE.id,
                localDateEpochDay = on.toEpochDay().toInt(),
                merchantId = null,
                merchantRaw = null,
                categoryId = categoryId,
                source = TransactionSource.MANUAL,
                status = TransactionStatus.CONFIRMED,
                reviewReason = null,
                reviewedAtUtcMillis = null,
                confidence = 1f,
                parserId = null,
                parserVersion = null,
                sourcePackage = "manual",
                sourceKey = "tag-$amountMinor-${on.toEpochDay()}-$categoryId",
                cardLabel = null,
                cardLast4 = null,
                note = null,
                kind = TransactionKind.EXPENSE,
                accountId = null,
                toAccountId = null,
            ),
        )
    }

    private suspend fun group(name: String): Long =
        database.categoryGroupDao().insert(CategoryGroupEntity(name = name, normalisedName = name.uppercase()))

    private suspend fun category(
        name: String,
        groupId: Long?,
    ): Long =
        database
            .categoryDao()
            .insert(CategoryEntity(name = name, normalisedName = name.uppercase(), groupId = groupId))

    private fun categories() = CategoryRepository(database.categoryDao(), database.merchantDao(), database.accountDao())

    private fun budgets() =
        BudgetRepository(
            database.budgetDao(),
            ReportRepository(database.reportDao()),
            categories(),
            CategoryGroupRepository(database.categoryGroupDao(), database.categoryDao()),
        )

    private fun trendsViewModel(state: SavedStateHandle) =
        TrendsViewModel(
            reports = ReportRepository(database.reportDao()),
            budgets = budgets(),
            categories = categories(),
            groups = CategoryGroupRepository(database.categoryGroupDao(), database.categoryDao()),
            premium = premium,
            transactions =
                TransactionRepository(
                    database = database,
                    transactions = database.transactionDao(),
                    merchants = MerchantRepository(database.merchantDao()),
                    categories = categories(),
                    zoneId = Provider { ZONE },
                ),
            ticker = LocalDateTicker(CLOCK, Provider { ZONE }),
            savedState = state,
        )

    private object UnlockedBuild : PremiumSource {
        override val donationPage: String? = null
        override val managePage: String? = null
        override val storeName: String = "F-Droid"
        override val owned: Flow<Premium?> = flowOf(Premium.Unlocked)
        override val offers: Flow<PremiumOffers> = flowOf(PremiumOffers.None)

        override fun refresh() = Unit

        override suspend fun redeem(code: String) = false

        override fun buy(
            activity: Activity,
            offer: PremiumOffer,
        ) = Unit
    }

    private companion object {
        val ZONE: ZoneId = ZoneId.of("America/Toronto")
        val TODAY: LocalDate = LocalDate.of(2026, 11, 16)
        val CLOCK: Clock = Clock.fixed(TODAY.atTime(NOON, 0).atZone(ZONE).toInstant(), ZONE)
        val AUGUST: YearMonth = YearMonth.of(2026, 8)
        val SEPTEMBER: YearMonth = YearMonth.of(2026, 9)
        val OCTOBER: YearMonth = YearMonth.of(2026, 10)
        val NOVEMBER: YearMonth = YearMonth.of(2026, 11)
        const val NOON = 12
    }
}
