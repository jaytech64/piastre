// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.feature.ledger

import androidx.lifecycle.SavedStateHandle
import io.github.jaytech64.piastre.core.model.Money
import io.github.jaytech64.piastre.core.model.Rhythm
import io.github.jaytech64.piastre.core.model.TransactionKind
import io.github.jaytech64.piastre.core.model.TransactionSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** What is dated after today: kept above it, counted nowhere, and let into the list on its day. */
@RunWith(RobolectricTestRunner::class)
class LedgerUpcomingTest : LedgerTest() {
    private val movingClock = MovingClock(TODAY.atTime(NOON, 0).atZone(ZONE).toInstant())

    override val clock: Clock get() = movingClock

    @Test
    fun `a row dated tomorrow is above today and in no total`() =
        runTest {
            insert(amountMinor = 1_000, day = TODAY)
            insert(amountMinor = 21_240, day = TOMORROW)
            val state = settled()

            assertEquals(listOf(TODAY), state.days.map { it.date })
            assertEquals(Money(-1_000, Money.CAD), state.headline.balance)
            assertEquals(listOf(TOMORROW), state.upcoming.map { it.transaction.localDate })
            assertEquals(Money(-21_240, Money.CAD), state.upcomingSummary.balance)
        }

    @Test
    fun `at midnight a row dated tomorrow moves into the list and starts to count`() =
        runTest {
            insert(amountMinor = 21_240, day = TOMORROW)
            settled()

            val untilMidnight = Duration.between(movingClock.instant(), TOMORROW.atStartOfDay(ZONE).toInstant())
            movingClock.now = TOMORROW.atTime(0, 0, 2).atZone(ZONE).toInstant()
            advanceTimeBy(untilMidnight.toMillis() + 5_000)
            val state = viewModel.uiState.first { it.today == TOMORROW }

            assertTrue(state.upcoming.isEmpty())
            assertEquals(listOf(TOMORROW), state.days.map { it.date })
            assertEquals(Money(-21_240, Money.CAD), state.headline.balance)
        }

    @Test
    fun `what is to come cannot be selected`() =
        runTest {
            insert(amountMinor = 21_240, day = TOMORROW)
            val later =
                settled()
                    .upcoming
                    .single()
                    .transaction.id

            viewModel.selection.toggle(later)

            assertTrue(settled().selected.isEmpty())
        }

    @Test
    fun `a filter narrows what is to come too`() =
        runTest {
            val chequing = account("Chèques")
            insert(amountMinor = 145_000, day = TOMORROW, accountId = chequing)
            insert(amountMinor = 21_240, day = TOMORROW)

            viewModel.filters.onAccountSelected(chequing)

            assertEquals(
                listOf(145_000L),
                viewModel.uiState
                    .first {
                        it.filter.account != null
                    }.upcoming
                    .map { it.transaction.amount.minor },
            )
        }

    @Test
    fun `the group says what income and spending to come add up to`() =
        runTest {
            insert(amountMinor = 185_000, day = TOMORROW, kind = TransactionKind.INCOME)
            insert(amountMinor = 145_000, day = TOMORROW)

            assertEquals(Money(40_000, Money.CAD), settled().upcomingSummary.balance)
        }

    @Test
    fun `the group opens collapsed and stays open once opened, until the app starts again`() =
        runTest {
            insert(amountMinor = 21_240, day = TOMORROW)
            assertFalse(settled().upcomingOpen)

            viewModel.upcoming.toggle()
            viewModel = ledgerViewModel(savedState)

            assertTrue(settled().upcomingOpen)
            viewModel = ledgerViewModel(SavedStateHandle())
            assertFalse(settled().upcomingOpen)
        }

    @Test
    fun `a recurring transaction's next day waits above today once, and counts in nothing`() =
        runTest {
            repeatMonthly(amountMinor = 145_000, from = YESTERDAY)
            val state = settled()

            val next = state.upcoming.single()
            assertEquals(YESTERDAY.plusMonths(1), next.transaction.localDate)
            assertEquals(TransactionSource.RECURRING, next.transaction.source)
            assertEquals(
                recurringRepository()
                    .all()
                    .first()
                    .single()
                    .id,
                next.scheduleId,
            )
            assertEquals(Money(-145_000, Money.CAD), state.headline.balance)
            assertEquals(Money(-145_000, Money.CAD), state.upcomingSummary.balance)
        }

    @Test
    fun `what recurs is the first of its day, under everything spent that day`() =
        runTest {
            insert(amountMinor = 316, day = TODAY, hour = 8)
            insert(amountMinor = 4_250, day = TODAY, hour = 18)
            repeatMonthly(amountMinor = 145_000, from = TODAY)

            val day = settled().days.single()

            assertEquals(listOf(4_250L, 316L, 145_000L), day.transactions.map { it.amount.minor })
        }

    @Test
    fun `a recurring transaction due today is never shown as upcoming, even before it is written`() =
        runTest {
            repeatMonthly(amountMinor = 8_500, from = TODAY.plusDays(2))
            val schedule = recurringRepository().all().first().single()
            database.recurringTransactionDao().moveNextDue(schedule.id, TODAY.toEpochDay().toInt())

            assertTrue(settled().upcoming.isEmpty())
        }

    @Test
    fun `a recurring transaction starting next week shows its first day and writes nothing`() =
        runTest {
            repeatMonthly(amountMinor = 8_500, from = TODAY.plusWeeks(1))
            val state = settled()

            assertTrue(state.days.isEmpty())
            assertEquals(listOf(TODAY.plusWeeks(1)), state.upcoming.map { it.transaction.localDate })
        }

    @Test
    fun `a filter narrows the next days too`() =
        runTest {
            val chequing = account("Chèques")
            repeatMonthly(amountMinor = 145_000, from = TODAY.plusDays(3), accountId = chequing)
            repeatMonthly(amountMinor = 8_500, from = TODAY.plusDays(3))

            viewModel.filters.onAccountSelected(chequing)

            assertEquals(
                listOf(145_000L),
                viewModel.uiState
                    .first { it.filter.account != null }
                    .upcoming
                    .map { it.transaction.amount.minor },
            )
        }

    private fun recurringRepository() = database.recurringRepository(clock, ZONE)

    private suspend fun repeatMonthly(
        amountMinor: Long,
        from: LocalDate,
        accountId: Long? = null,
    ) {
        val id =
            transactionRepository().addManual(
                amount = Money(amountMinor, Money.CAD),
                merchantName = "Loyer",
                occurredAt = from.atTime(NOON, 0).atZone(ZONE).toInstant(),
                note = null,
                kind = TransactionKind.EXPENSE,
                accountId = accountId,
                toAccountId = null,
                categoryId = null,
                reach = null,
            )
        recurringRepository().repeat(id, Rhythm.MONTHLY, lastDate = null)
    }

    /** A clock a test moves by hand, so the ticker can be walked across midnight. */
    private class MovingClock(
        var now: Instant,
    ) : Clock() {
        override fun getZone(): ZoneId = ZONE

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = now
    }
}
