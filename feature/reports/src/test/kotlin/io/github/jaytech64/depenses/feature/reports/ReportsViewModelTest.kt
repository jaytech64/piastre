// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.feature.reports

import androidx.lifecycle.SavedStateHandle
import io.github.jaytech64.depenses.core.data.CategoryRepository
import io.github.jaytech64.depenses.core.data.LocalDateTicker
import io.github.jaytech64.depenses.core.data.MerchantRepository
import io.github.jaytech64.depenses.core.data.ReportRepository
import io.github.jaytech64.depenses.core.data.TransactionRepository
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
import java.time.Clock
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
    fun `a period totals what was spent in it and says how many transactions that was`() =
        runTest {
            spend(2_342, TODAY)
            spend(625, TODAY)
            spend(9_999, TODAY.minusWeeks(2))

            val state = settled()

            assertEquals(Money(2_967, Money.CAD), state.total)
            assertEquals(2, state.spendingCount)
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
    fun `a transfer into a bank account is in a report's spending and its count`() =
        runTest {
            spend(1_000, TODAY)
            record(
                120_000,
                TODAY,
                kind = TransactionKind.TRANSFER,
                accountId = account(AccountType.CARD),
                toAccountId = account(AccountType.BANK),
            )

            val state = settled()

            assertEquals(Money(121_000, Money.CAD), state.total)
            assertEquals(2, state.spendingCount)
            assertEquals(Money(-121_000, Money.CAD), state.balance)
        }

    @Test
    fun `money moved back out of a bank account is in a report's income`() =
        runTest {
            record(
                30_000,
                TODAY,
                kind = TransactionKind.TRANSFER,
                accountId = account(AccountType.BANK),
                toAccountId = account(AccountType.CARD),
            )

            val state = settled()

            assertEquals(Money(30_000, Money.CAD), state.income)
            assertEquals(Money(30_000, Money.CAD), state.balance)
        }

    @Test
    fun `a payday with no spending is not an empty period`() =
        runTest {
            record(185_000, TODAY, kind = TransactionKind.INCOME)

            val state = settled()

            assertFalse(state.isEmpty)
            assertEquals(0, state.spendingCount)
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

            assertEquals(0, state.spendingCount)
            assertNull(state.comparison)
        }

    @Test
    fun `groups rank heaviest first with their categories inside, and a category in no group ranks as itself`() =
        runTest {
            val home = group("Maison")
            val power = category("Électricité", home)
            val upkeep = category("Entretien", home)
            val gifts = category("Cadeaux", null)
            spend(1_000, TODAY, categoryId = power)
            spend(3_000, TODAY, categoryId = upkeep)
            spend(5_000, TODAY, categoryId = gifts)
            spend(1_000, TODAY)

            val state = settled()

            assertEquals(listOf("Cadeaux", "Maison"), state.ranking.map { it.name })
            assertEquals(listOf(null, home), state.ranking.map { it.groupId })
            assertEquals(0.5f, state.ranking[0].share, TOLERANCE)
            assertEquals(emptyList<CategoryShare>(), state.ranking[0].categories)
            assertEquals(Money(4_000, Money.CAD), state.ranking[1].total)
            assertEquals(0.4f, state.ranking[1].share, TOLERANCE)
            assertEquals(listOf("Entretien", "Électricité"), state.ranking[1].categories.map { it.name })
            assertEquals(0.3f, state.ranking[1].categories[0].share, TOLERANCE)
            assertEquals(0.1f, state.ranking[1].categories[1].share, TOLERANCE)
            assertEquals(Money(1_000, Money.CAD), state.uncategorised)
        }

    @Test
    fun `the ranking keeps the five heaviest, and never more than what was spent`() =
        runTest {
            (1..6).forEach { spend(it * 1_000L, TODAY, categoryId = category("Catégorie $it", null)) }

            val state = settled()

            assertEquals((6 downTo 2).map { "Catégorie $it" }, state.ranking.map { it.name })
            assertTrue(state.ranking.sumOf { it.total.minor } <= state.total.minor)
        }

    @Test
    fun `a mortgage paid by transfer ranks in its group, and the group never exceeds what was spent`() =
        runTest {
            val home = group("Maison")
            spend(4_000, TODAY, categoryId = category("Électricité", home))
            record(
                120_000,
                TODAY,
                kind = TransactionKind.TRANSFER,
                accountId = account(AccountType.CARD),
                toAccountId = account(AccountType.BANK),
                categoryId = category("Hypothèque", home),
            )

            val state = settled()

            assertEquals(Money(124_000, Money.CAD), state.ranking.single().total)
            assertEquals(state.total, state.ranking.single().total)
            assertEquals(
                listOf("Hypothèque", "Électricité"),
                state.ranking
                    .single()
                    .categories
                    .map { it.name },
            )
        }

    @Test
    fun `tapping a group opens it, tapping it again closes it, and another takes its place`() =
        runTest {
            val home = group("Maison")
            val car = group("Auto")
            spend(1_000, TODAY, categoryId = category("Électricité", home))
            spend(2_000, TODAY, categoryId = category("Essence", car))
            settled()

            viewModel.onGroupTapped(home)
            assertEquals(home, viewModel.uiState.first { it.openGroupId != null }.openGroupId)

            viewModel.onGroupTapped(car)
            assertEquals(car, viewModel.uiState.first { it.openGroupId != home }.openGroupId)

            viewModel.onGroupTapped(car)
            assertNull(viewModel.uiState.first { it.openGroupId != car }.openGroupId)
        }

    @Test
    fun `the open group stays open on the period you step to`() =
        runTest {
            val home = group("Maison")
            spend(1_000, TODAY, categoryId = category("Électricité", home))
            settled()
            viewModel.onGroupTapped(home)

            viewModel.onStepBack()

            assertEquals(home, shownFor(THIS_WEEK.previous()).openGroupId)
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
            ticker = LocalDateTicker(Clock.systemUTC(), Provider { ZONE }),
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
        currency: String = Money.CAD,
        categoryId: Long? = null,
    ) = record(amountMinor, on, currency = currency, categoryId = categoryId)

    private suspend fun record(
        amountMinor: Long,
        on: LocalDate,
        kind: TransactionKind = TransactionKind.EXPENSE,
        status: TransactionStatus = TransactionStatus.CONFIRMED,
        currency: String = Money.CAD,
        accountId: Long? = null,
        toAccountId: Long? = null,
        categoryId: Long? = null,
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
                sourceKey = "tag-$amountMinor-${on.toEpochDay()}-$kind-$status-$currency-$categoryId",
                cardLabel = null,
                cardLast4 = null,
                note = null,
                kind = kind,
                accountId = accountId,
                toAccountId = toAccountId,
            ),
        )
    }

    private suspend fun account(type: AccountType): Long =
        database.accountDao().insert(
            AccountEntity(name = null, type = type, issuerLabel = null, last4 = null, origin = AccountOrigin.USER),
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
