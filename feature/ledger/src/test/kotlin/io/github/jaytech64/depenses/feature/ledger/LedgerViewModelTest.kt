// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.feature.ledger

import io.github.jaytech64.depenses.core.data.LocalDateTicker
import io.github.jaytech64.depenses.core.data.MerchantRepository
import io.github.jaytech64.depenses.core.data.TransactionRepository
import io.github.jaytech64.depenses.core.database.DepensesDatabase
import io.github.jaytech64.depenses.core.database.ExpenseTransactionEntity
import io.github.jaytech64.depenses.core.database.MerchantEntity
import io.github.jaytech64.depenses.core.model.Money
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
                repository = transactionRepository(),
                ticker = LocalDateTicker(Provider { ZONE }),
            )
    }

    private fun transactionRepository() =
        TransactionRepository(
            transactions = database.transactionDao(),
            reports = database.reportDao(),
            merchants = MerchantRepository(database.merchantDao()),
            zoneId = Provider { ZONE },
        )

    @After
    fun tearDown() {
        database.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `an untouched ledger says so instead of showing a blank list`() =
        runTest {
            assertTrue(settled().isEmpty)
        }

    @Test
    fun `purchases are grouped by the day they were made, newest first`() =
        runTest {
            insert(amountMinor = 1_075, day = TODAY)
            insert(amountMinor = 2_342, day = TODAY)
            insert(amountMinor = 3_180, day = YESTERDAY)
            val state = settled()

            assertEquals(listOf(TODAY, YESTERDAY), state.days.map { it.date })
            assertEquals(listOf(2, 1), state.days.map { it.transactions.size })
        }

    @Test
    fun `each day carries its own total and today carries the headline`() =
        runTest {
            insert(amountMinor = 1_075, day = TODAY)
            insert(amountMinor = 2_342, day = TODAY)
            insert(amountMinor = 3_180, day = YESTERDAY)
            val state = settled()

            assertEquals(Money(-3_417, Money.CAD), state.days.first().total)
            assertEquals(Money(-3_180, Money.CAD), state.days.last().total)
            assertEquals(Money(-3_417, Money.CAD), state.balanceToday)
        }

    @Test
    fun `a day with no spending shows a zero headline rather than the last day's`() =
        runTest {
            insert(amountMinor = 3_180, day = YESTERDAY)
            val state = settled()

            assertEquals(Money(0, Money.CAD), state.balanceToday)
            assertFalse(state.isEmpty)
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
            val state = settled()

            assertEquals(Money(-1_075, Money.CAD), state.balanceToday)
            assertEquals(
                1,
                state.days
                    .single()
                    .transactions.size,
            )
            assertEquals(1, state.needsReview)
        }

    @Test
    fun `a purchase shows its canonical merchant, not the payment network string`() =
        runTest {
            val merchantId =
                database.merchantDao().insert(
                    MerchantEntity(displayName = "MARCHE ST JOSEPH", normalisedName = "MARCHE ST JOSEPH"),
                )
            insert(amountMinor = 2_342, day = TODAY, merchantId = merchantId)
            val transaction =
                settled()
                    .days
                    .single()
                    .transactions
                    .single()

            assertEquals("MARCHE ST JOSEPH", transaction.displayMerchant)
            assertEquals("MARCHE ST JOSEPH  1234", transaction.merchantRaw)
        }

    @Test
    fun `a purchase whose merchant never resolved still appears, under its raw name`() =
        runTest {
            insert(amountMinor = 2_342, day = TODAY, merchantId = null)
            assertEquals(
                "MARCHE ST JOSEPH  1234",
                settled()
                    .days
                    .single()
                    .transactions
                    .single()
                    .displayMerchant,
            )
        }

    /** Income and spending on one day meet in one signed balance, not two figures. */
    @Test
    fun `income lifts the day's balance above zero`() =
        runTest {
            insert(amountMinor = 15_000, day = TODAY, kind = TransactionKind.INCOME)
            insert(amountMinor = 4_210, day = TODAY)
            val state = settled()

            assertEquals(Money(10_790, Money.CAD), state.balanceToday)
            assertEquals(Money(10_790, Money.CAD), state.days.single().total)
        }

    /**
     * The state once the database has answered, awaited rather than polled.
     *
     * Turbine's timeouts run on the test scheduler's virtual clock, which jumps the
     * moment nothing is queued — so a wait that starts before the flow has been
     * subscribed times out instantly rather than waiting at all.
     */
    private suspend fun settled(): LedgerUiState = viewModel.uiState.first { !it.loading }

    private suspend fun insert(
        amountMinor: Long,
        day: LocalDate,
        status: TransactionStatus = TransactionStatus.CONFIRMED,
        merchantId: Long? = null,
        kind: TransactionKind = TransactionKind.EXPENSE,
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
            kind = kind,
        ),
    )

    private companion object {
        val ZONE: ZoneId = ZoneId.of("America/Toronto")
        val TODAY: LocalDate = LocalDate.now(ZONE)
        val YESTERDAY: LocalDate = TODAY.minusDays(1)
        const val NOON = 12
    }
}
