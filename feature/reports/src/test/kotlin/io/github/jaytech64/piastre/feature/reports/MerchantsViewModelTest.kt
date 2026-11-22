// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.feature.reports

import android.app.Activity
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import io.github.jaytech64.piastre.core.data.CategoryRepository
import io.github.jaytech64.piastre.core.data.LocalDateTicker
import io.github.jaytech64.piastre.core.data.MerchantRepository
import io.github.jaytech64.piastre.core.data.PremiumOffer
import io.github.jaytech64.piastre.core.data.PremiumOffers
import io.github.jaytech64.piastre.core.data.PremiumRepository
import io.github.jaytech64.piastre.core.data.PremiumSource
import io.github.jaytech64.piastre.core.data.ReportRepository
import io.github.jaytech64.piastre.core.data.TransactionRepository
import io.github.jaytech64.piastre.core.database.ExpenseTransactionEntity
import io.github.jaytech64.piastre.core.database.MerchantEntity
import io.github.jaytech64.piastre.core.database.PiastreDatabase
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
class MerchantsViewModelTest {
    private lateinit var database: PiastreDatabase
    private val storeFile: File = File.createTempFile("settings", ".preferences_pb").apply { delete() }
    private val premium by lazy { PremiumRepository(UnlockedBuild, PreferenceDataStoreFactory.create { storeFile }) }

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        database = inMemoryDatabase()
    }

    @After
    fun tearDown() {
        database.close()
        storeFile.delete()
        Dispatchers.resetMain()
    }

    @Test
    fun `the page opens on the month the report was on, heaviest merchant first`() =
        runTest {
            val bakery = merchant("Boulangerie Test")
            val grocer = merchant("Épicerie Test")
            spend(300, OCTOBER.atDay(2), bakery)
            spend(400, OCTOBER.atDay(9), bakery)
            spend(5_000, OCTOBER.atDay(5), grocer)
            spend(9_999, TODAY, grocer)

            val state = merchantsViewModel(OCTOBER).uiState.first { it.ranking.lines.isNotEmpty() }

            assertEquals(OCTOBER.atDay(1), state.span.start)
            assertEquals(listOf("Épicerie Test", "Boulangerie Test"), state.ranking.lines.map { it.name })
            assertEquals(Money(5_700, Money.CAD), state.ranking.total)
        }

    @Test
    fun `ranked by visits, the merchant visited most comes first`() =
        runTest {
            val bakery = merchant("Boulangerie Test")
            val grocer = merchant("Épicerie Test")
            spend(300, TODAY, bakery)
            spend(400, TODAY.minusDays(1), bakery)
            spend(5_000, TODAY, grocer)
            val viewModel = merchantsViewModel(NOVEMBER)

            viewModel.onRankSelected(MerchantRank.VISITS)
            val state = viewModel.uiState.first { it.rank == MerchantRank.VISITS && it.ranking.lines.isNotEmpty() }

            assertEquals(
                "Boulangerie Test",
                state.ranking.lines
                    .first()
                    .name,
            )
            assertEquals(
                2,
                state.ranking.lines
                    .first()
                    .visits,
            )
            assertEquals(
                Money(350, Money.CAD),
                state.ranking.lines
                    .first()
                    .average,
            )
        }

    @Test
    fun `a year counts every month of it up to today`() =
        runTest {
            val grocer = merchant("Épicerie Test")
            spend(1_000, LocalDate.of(2026, 1, 15), grocer)
            spend(2_000, OCTOBER.atDay(5), grocer)
            spend(4_000, LocalDate.of(2025, 12, 31), grocer)
            val viewModel = merchantsViewModel(OCTOBER)

            viewModel.onUnitSelected(MerchantSpanUnit.YEAR)
            val line =
                viewModel.uiState
                    .first { it.span.unit == MerchantSpanUnit.YEAR && it.ranking.lines.isNotEmpty() }
                    .ranking.lines
                    .single()

            assertEquals(Money(3_000, Money.CAD), line.total)
            assertEquals(2, line.visits)
        }

    @Test
    fun `the page steps back through spans and never past the one you are living in`() =
        runTest {
            val viewModel = merchantsViewModel(NOVEMBER)
            val now = viewModel.uiState.first { it.today != null }
            assertFalse(now.canStepForward)

            viewModel.onStepForward()
            viewModel.onStepBack()

            val before = viewModel.uiState.first { it.span.start == OCTOBER.atDay(1) }
            assertTrue(before.canStepForward)
        }

    @Test
    fun `what went out with no merchant is said under the list rather than ranked`() =
        runTest {
            spend(1_240, TODAY, merchantId = null)
            spend(800, TODAY, merchant("Café Test"))

            val ranking = merchantsViewModel(NOVEMBER).uiState.first { it.ranking.lines.isNotEmpty() }.ranking

            assertEquals(1, ranking.lines.size)
            assertEquals(Money(1_240, Money.CAD), ranking.withoutMerchant)
        }

    @Test
    fun `an average is rounded to the nearest cent`() {
        assertEquals(Money(333, Money.CAD), Money(1_000, Money.CAD).averageOver(3))
        assertEquals(Money(667, Money.CAD), Money(2_000, Money.CAD).averageOver(3))
        assertEquals(Money(-667, Money.CAD), Money(-2_000, Money.CAD).averageOver(3))
        assertEquals(Money(1, Money.CAD), Money(1, Money.CAD).averageOver(2))
    }

    private suspend fun merchant(name: String): Long =
        database.merchantDao().insert(MerchantEntity(displayName = name, normalisedName = name.uppercase()))

    private suspend fun spend(
        amountMinor: Long,
        on: LocalDate,
        merchantId: Long?,
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
                merchantId = merchantId,
                merchantRaw = null,
                categoryId = null,
                source = TransactionSource.MANUAL,
                status = TransactionStatus.CONFIRMED,
                reviewReason = null,
                reviewedAtUtcMillis = null,
                confidence = 1f,
                parserId = null,
                parserVersion = null,
                sourcePackage = "manual",
                sourceKey = "tag-$amountMinor-${on.toEpochDay()}-$merchantId",
                cardLabel = null,
                cardLast4 = null,
                note = null,
                kind = TransactionKind.EXPENSE,
                accountId = null,
                toAccountId = null,
            ),
        )
    }

    private fun merchantsViewModel(month: YearMonth) =
        MerchantsViewModel(
            reports = ReportRepository(database.reportDao()),
            premium = premium,
            transactions =
                TransactionRepository(
                    database = database,
                    transactions = database.transactionDao(),
                    merchants = MerchantRepository(database.merchantDao()),
                    categories =
                        CategoryRepository(database.categoryDao(), database.merchantDao(), database.accountDao()),
                    zoneId = Provider { ZONE },
                ),
            ticker = LocalDateTicker(CLOCK, Provider { ZONE }),
            savedState = SavedStateHandle(mapOf(MERCHANTS_MONTH_ARG to month.toString())),
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
        val OCTOBER: YearMonth = YearMonth.of(2026, 10)
        val NOVEMBER: YearMonth = YearMonth.of(2026, 11)
        const val NOON = 12
    }
}
