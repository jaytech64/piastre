// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.feature.reports

import androidx.lifecycle.SavedStateHandle
import io.github.jaytech64.depenses.core.data.CategoryRepository
import io.github.jaytech64.depenses.core.data.LocalDateTicker
import io.github.jaytech64.depenses.core.data.MerchantRepository
import io.github.jaytech64.depenses.core.data.ReportRepository
import io.github.jaytech64.depenses.core.data.TransactionRepository
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Provider

@RunWith(RobolectricTestRunner::class)
class ReportsViewModelTest {
    private lateinit var database: DepensesDatabase
    private lateinit var savedState: SavedStateHandle
    private lateinit var viewModel: ReportsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        database = inMemoryDatabase()
        savedState = SavedStateHandle()
        viewModel = reportsViewModel(savedState)
    }

    @After
    fun tearDown() {
        database.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `a report opens on the week containing today`() =
        runTest {
            val state = settled()

            assertEquals(PeriodUnit.WEEK, state.period.unit)
            assertTrue(TODAY in state.period)
        }

    @Test
    fun `the week you are living in cannot be stepped past`() =
        runTest {
            assertFalse(settled().canStepForward)

            viewModel.onStepBack()

            assertTrue(shownFor(THIS_WEEK.previous()).canStepForward)
        }

    @Test
    fun `stepping back reaches the week before, and forward comes back`() =
        runTest {
            settled()

            viewModel.onStepBack()
            assertEquals(THIS_WEEK.previous(), shownFor(THIS_WEEK.previous()).period)

            viewModel.onStepForward()
            assertEquals(THIS_WEEK, shownFor(THIS_WEEK).period)
        }

    @Test
    fun `a period totals what was spent in it and says how many purchases that was`() =
        runTest {
            spend(2_342, TODAY)
            spend(625, TODAY)
            spend(9_999, TODAY.minusWeeks(2))

            val state = settled()

            assertEquals(Money(2_967, Money.CAD), state.total)
            assertEquals(2, state.purchaseCount)
        }

    @Test
    fun `a paycheque is not spending, and neither is moving your own money`() =
        runTest {
            spend(1_000, TODAY)
            record(150_000, TODAY, kind = TransactionKind.INCOME)
            record(6_000, TODAY, kind = TransactionKind.TRANSFER)

            assertEquals(Money(1_000, Money.CAD), settled().total)
        }

    @Test
    fun `a period where nothing happened says so rather than showing a bare zero`() =
        runTest {
            assertTrue(settled().isEmpty)
        }

    @Test
    fun `a period's balance is what came in less what was spent`() =
        runTest {
            spend(2_080, TODAY)
            record(4_300, TODAY, kind = TransactionKind.INCOME)

            val state = settled()

            assertEquals(Money(4_300, Money.CAD), state.income)
            assertEquals(Money(2_220, Money.CAD), state.balance)
        }

    @Test
    fun `a card payment moves a report's balance by nothing`() =
        runTest {
            spend(1_000, TODAY)
            record(90_000, TODAY, kind = TransactionKind.TRANSFER)

            assertEquals(Money(-1_000, Money.CAD), settled().balance)
        }

    @Test
    fun `a payday with no spending is not an empty period`() =
        runTest {
            record(185_000, TODAY, kind = TransactionKind.INCOME)

            val state = settled()

            assertFalse(state.isEmpty)
            assertEquals(0, state.purchaseCount)
            assertEquals(Money(185_000, Money.CAD), state.balance)
        }

    @Test
    fun `income is compared month to month and never week to week`() =
        runTest {
            record(150_000, TODAY, kind = TransactionKind.INCOME)
            record(100_000, TODAY.minusWeeks(1), kind = TransactionKind.INCOME)

            assertNull(settled().incomeComparison)

            val march = LocalDate.of(2025, 3, 14)
            record(200_000, march, kind = TransactionKind.INCOME)
            record(250_000, march.minusMonths(1), kind = TransactionKind.INCOME)
            viewModel.onDaySelected(march)
            viewModel.onUnitSelected(PeriodUnit.MONTH)
            val state = shownFor(ReportPeriod(PeriodUnit.MONTH, march.withDayOfMonth(1)))

            assertEquals(Comparison.Change(-20), state.incomeComparison)
        }

    @Test
    fun `spending twice what you spent last week is a hundred percent more`() =
        runTest {
            spend(2_000, TODAY)
            spend(1_000, TODAY.minusWeeks(1))

            assertEquals(Comparison.Change(100), settled().comparison)
        }

    @Test
    fun `spending less than last week is a negative change, which is the good direction`() =
        runTest {
            spend(1_000, TODAY)
            spend(4_000, TODAY.minusWeeks(1))

            assertEquals(Comparison.Change(-75), settled().comparison)
        }

    @Test
    fun `a period with nothing before it reports no percentage rather than an infinite one`() =
        runTest {
            spend(2_000, TODAY)

            assertEquals(Comparison.NothingBefore, settled().comparison)
        }

    @Test
    fun `two empty periods have nothing to compare`() =
        runTest {
            assertNull(settled().comparison)
        }

    @Test
    fun `a period with nothing spent is not compared with one that had spending`() =
        runTest {
            record(50_000, TODAY, kind = TransactionKind.INCOME)
            spend(11_500, TODAY.minusWeeks(1))

            val state = settled()

            assertEquals(0, state.purchaseCount)
            assertNull(state.comparison)
        }

    @Test
    fun `merchants rank heaviest first, each carrying its share of the period`() =
        runTest {
            val grocer = merchant("Metro Plus", "METRO PLUS")
            val cafe = merchant("Café Olimpico", "CAFE OLIMPICO")
            spend(7_500, TODAY, merchantId = grocer)
            spend(2_500, TODAY, merchantId = cafe)

            val ranking = settled().merchants

            assertEquals(listOf("Metro Plus", "Café Olimpico"), ranking.map { it.name })
            assertEquals(0.75f, ranking.first().share, TOLERANCE)
            assertEquals(0.25f, ranking.last().share, TOLERANCE)
        }

    @Test
    fun `the calendar covers the month the period sits in, and no neighbour's days`() =
        runTest {
            spend(1_000, LocalDate.of(2026, 9, 3))
            spend(9_999, LocalDate.of(2026, 8, 31))

            viewModel.onDaySelected(A_SEPTEMBER_DAY)
            val state = shownFor(ReportPeriod(PeriodUnit.DAY, A_SEPTEMBER_DAY))

            assertEquals(setOf(LocalDate.of(2026, 9, 3)), state.monthTotals.keys)
            assertEquals(9, state.period.month.monthValue)
        }

    @Test
    fun `tapping a day narrows the report to that day`() =
        runTest {
            spend(2_342, TODAY)
            spend(625, TODAY.minusDays(1))
            settled()

            viewModel.onDaySelected(TODAY)
            val state = shownFor(ReportPeriod(PeriodUnit.DAY, TODAY))

            assertEquals(PeriodUnit.DAY, state.period.unit)
            assertEquals(Money(2_342, Money.CAD), state.total)
        }

    @Test
    fun `switching to the month keeps you in the month of the day you were on`() =
        runTest {
            viewModel.onDaySelected(A_SEPTEMBER_DAY)
            shownFor(ReportPeriod(PeriodUnit.DAY, A_SEPTEMBER_DAY))

            viewModel.onUnitSelected(PeriodUnit.MONTH)
            val state = shownFor(ReportPeriod(PeriodUnit.MONTH, LocalDate.of(2026, 9, 1)))

            assertEquals(LocalDate.of(2026, 9, 30), state.period.endInclusive)
        }

    @Test
    fun `the period survives the view model being rebuilt from saved state`() =
        runTest {
            viewModel.onDaySelected(A_SEPTEMBER_DAY)
            shownFor(ReportPeriod(PeriodUnit.DAY, A_SEPTEMBER_DAY))

            viewModel = reportsViewModel(savedState)

            assertEquals(A_SEPTEMBER_DAY, settled().period.start)
        }

    @Test
    fun `what the totals leave out is counted so the screen can admit it`() =
        runTest {
            spend(1_000, TODAY)
            spend(4_500, TODAY, currency = "USD")
            record(40_000, TODAY, kind = TransactionKind.INCOME, currency = "USD")
            record(8_000, TODAY, status = TransactionStatus.NEEDS_REVIEW)

            val state = settled()

            assertEquals(Money(1_000, Money.CAD), state.total)
            assertEquals(2, state.otherCurrencyCount)
            assertEquals(1, state.needsReviewCount)
        }

    private fun reportsViewModel(state: SavedStateHandle) =
        ReportsViewModel(
            reports = ReportRepository(database.reportDao()),
            transactions =
                TransactionRepository(
                    database = database,
                    transactions = database.transactionDao(),
                    merchants = MerchantRepository(database.merchantDao()),
                    categories =
                        CategoryRepository(
                            database.categoryDao(),
                            database.merchantDao(),
                            database.accountDao(),
                        ),
                    zoneId = Provider { ZONE },
                ),
            ticker = LocalDateTicker(Provider { ZONE }),
            savedState = state,
        )

    private suspend fun settled(): ReportsUiState = viewModel.uiState.first { !it.loading }

    /**
     * Waits for the state that belongs to [period], not merely for a settled one.
     *
     * The flow keeps its last value after a subscriber leaves, so a plain
     * [settled] straight after a step would read the period you stepped away
     * from and pass on the wrong screen.
     */
    private suspend fun shownFor(period: ReportPeriod): ReportsUiState =
        viewModel.uiState.first { !it.loading && it.period == period }

    private suspend fun spend(
        amountMinor: Long,
        on: LocalDate,
        merchantId: Long? = null,
        currency: String = Money.CAD,
    ) = record(amountMinor, on, merchantId = merchantId, currency = currency)

    private suspend fun record(
        amountMinor: Long,
        on: LocalDate,
        kind: TransactionKind = TransactionKind.EXPENSE,
        status: TransactionStatus = TransactionStatus.CONFIRMED,
        merchantId: Long? = null,
        currency: String = Money.CAD,
    ) {
        database.transactionDao().insert(
            ExpenseTransactionEntity(
                rawEventId = null,
                amountMinor = amountMinor,
                currency = currency,
                occurredAtUtcMillis =
                    on
                        .atTime(NOON, 0)
                        .atZone(ZONE)
                        .toInstant()
                        .toEpochMilli(),
                zoneId = ZONE.id,
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
                sourceKey = "tag-$amountMinor-${on.toEpochDay()}-$kind-$status-$currency-$merchantId",
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

    private companion object {
        val ZONE: ZoneId = ZoneId.of("America/Toronto")
        val TODAY: LocalDate = LocalDate.now(ZONE)
        val THIS_WEEK: ReportPeriod = ReportPeriod.containing(PeriodUnit.WEEK, TODAY)

        /** A fixed date, so the month assertions do not depend on the day the suite runs. */
        val A_SEPTEMBER_DAY: LocalDate = LocalDate.of(2026, 9, 16)
        const val NOON = 12
        const val TOLERANCE = 0.001f
    }
}
