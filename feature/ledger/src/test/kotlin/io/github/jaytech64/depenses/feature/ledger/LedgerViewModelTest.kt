// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.feature.ledger

import app.cash.turbine.test
import io.github.jaytech64.depenses.core.data.LocalDateTicker
import io.github.jaytech64.depenses.core.data.TransactionRepository
import io.github.jaytech64.depenses.core.database.DepensesDatabase
import io.github.jaytech64.depenses.core.database.ExpenseTransactionEntity
import io.github.jaytech64.depenses.core.database.MerchantEntity
import io.github.jaytech64.depenses.core.model.Money
import io.github.jaytech64.depenses.core.model.TransactionSource
import io.github.jaytech64.depenses.core.model.TransactionStatus
import io.github.jaytech64.depenses.core.testing.inMemoryDatabase
import kotlinx.coroutines.Dispatchers
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
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Provider

@RunWith(RobolectricTestRunner::class)
class LedgerViewModelTest {
    private lateinit var database: DepensesDatabase
    private lateinit var viewModel: LedgerViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        database = inMemoryDatabase()
        viewModel =
            LedgerViewModel(
                repository = TransactionRepository(database.transactionDao(), database.reportDao()),
                ticker = LocalDateTicker(Provider { ZONE }),
            )
    }

    @After
    fun tearDown() {
        database.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `an untouched ledger says so instead of showing a blank list`() =
        runTest {
            viewModel.uiState.test {
                assertTrue(settled().isEmpty)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `purchases are grouped by the day they were made, newest first`() =
        runTest {
            insert(amountMinor = 1_075, day = TODAY)
            insert(amountMinor = 2_342, day = TODAY)
            insert(amountMinor = 3_180, day = YESTERDAY)

            viewModel.uiState.test {
                val state = settled()

                assertEquals(listOf(TODAY, YESTERDAY), state.days.map { it.date })
                assertEquals(listOf(2, 1), state.days.map { it.transactions.size })
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `each day carries its own total and today carries the headline`() =
        runTest {
            insert(amountMinor = 1_075, day = TODAY)
            insert(amountMinor = 2_342, day = TODAY)
            insert(amountMinor = 3_180, day = YESTERDAY)

            viewModel.uiState.test {
                val state = settled()

                assertEquals(Money(3_417, Money.CAD), state.days.first().total)
                assertEquals(Money(3_180, Money.CAD), state.days.last().total)
                assertEquals(Money(3_417, Money.CAD), state.spentToday)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a day with no spending shows a zero headline rather than the last day's`() =
        runTest {
            insert(amountMinor = 3_180, day = YESTERDAY)

            viewModel.uiState.test {
                val state = settled()

                assertEquals(Money(0, Money.CAD), state.spentToday)
                assertFalse(state.isEmpty)
                cancelAndIgnoreRemainingEvents()
            }
        }

    /**
     * Totals exclude unreviewed purchases by design, so the count beside them is
     * the only thing keeping a low number from being a silent lie.
     */
    @Test
    fun `unreviewed purchases stay out of the totals but are counted`() =
        runTest {
            insert(amountMinor = 1_075, day = TODAY)
            insert(amountMinor = 9_999, day = TODAY, status = TransactionStatus.NEEDS_REVIEW)

            viewModel.uiState.test {
                val state = settled()

                assertEquals(Money(1_075, Money.CAD), state.spentToday)
                assertEquals(
                    1,
                    state.days
                        .single()
                        .transactions.size,
                )
                assertEquals(1, state.needsReview)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a purchase shows its canonical merchant, not the payment network string`() =
        runTest {
            val merchantId =
                database.merchantDao().insert(
                    MerchantEntity(displayName = "MARCHE ST JOSEPH", normalisedName = "MARCHE ST JOSEPH"),
                )
            insert(amountMinor = 2_342, day = TODAY, merchantId = merchantId)

            viewModel.uiState.test {
                val transaction =
                    settled()
                        .days
                        .single()
                        .transactions
                        .single()

                assertEquals("MARCHE ST JOSEPH", transaction.displayMerchant)
                assertEquals("MARCHE ST JOSEPH  1234", transaction.merchantRaw)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a purchase whose merchant never resolved still appears, under its raw name`() =
        runTest {
            insert(amountMinor = 2_342, day = TODAY, merchantId = null)

            viewModel.uiState.test {
                assertEquals(
                    "MARCHE ST JOSEPH  1234",
                    settled()
                        .days
                        .single()
                        .transactions
                        .single()
                        .displayMerchant,
                )
                cancelAndIgnoreRemainingEvents()
            }
        }

    /** The first emission is the placeholder the flow starts with; the next is real. */
    private suspend fun app.cash.turbine.TurbineTestContext<LedgerUiState>.settled(): LedgerUiState {
        var state = awaitItem()
        while (state.loading) state = awaitItem()
        return state
    }

    private suspend fun insert(
        amountMinor: Long,
        day: LocalDate,
        status: TransactionStatus = TransactionStatus.CONFIRMED,
        merchantId: Long? = null,
    ) = database.transactionDao().insertOrUpdate(
        ExpenseTransactionEntity(
            rawEventId = null,
            amountMinor = amountMinor,
            currency = Money.CAD,
            occurredAtUtcMillis =
                day
                    .atTime(NOON, 0)
                    .atZone(ZONE)
                    .toInstant()
                    .toEpochMilli(),
            zoneId = ZONE.id,
            localDateEpochDay = day.toEpochDay().toInt(),
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
            sourcePackage = "com.google.android.apps.walletnfcrel",
            sourceKey = "tag-$amountMinor-${day.toEpochDay()}-$status",
            cardLabel = "Récompenses Visa",
            cardLast4 = "0000",
            note = null,
        ),
    )

    private companion object {
        val ZONE: ZoneId = ZoneId.of("America/Toronto")
        val TODAY: LocalDate = LocalDate.now(ZONE)
        val YESTERDAY: LocalDate = TODAY.minusDays(1)
        const val NOON = 12
    }
}
