// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.feature.ledger

import androidx.lifecycle.SavedStateHandle
import io.github.jaytech64.depenses.core.model.Money
import io.github.jaytech64.depenses.core.model.TransactionKind
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

    /** A clock a test moves by hand, so the ticker can be walked across midnight. */
    private class MovingClock(
        var now: Instant,
    ) : Clock() {
        override fun getZone(): ZoneId = ZONE

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = now
    }
}
