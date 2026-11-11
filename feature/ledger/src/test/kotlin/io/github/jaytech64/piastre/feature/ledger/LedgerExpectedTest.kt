// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.feature.ledger

import io.github.jaytech64.piastre.core.model.AccountType
import io.github.jaytech64.piastre.core.model.Money
import io.github.jaytech64.piastre.core.model.Rhythm
import io.github.jaytech64.piastre.core.model.TransactionKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

/** Where this month's balance lands once the rest of the month has come, said beside it and counted nowhere else. */
@RunWith(RobolectricTestRunner::class)
class LedgerExpectedTest : LedgerTest() {
    @Test
    fun `the expected balance adds what is still to come this month to what has happened`() =
        runTest {
            insert(amountMinor = 95_000, day = YESTERDAY, kind = TransactionKind.INCOME)
            insert(amountMinor = 95_000, day = LATER_THIS_MONTH, kind = TransactionKind.INCOME)
            val state = settled()

            assertEquals(Money(95_000, Money.CAD), state.headline.balance)
            assertEquals(Money(95_000, Money.CAD), state.headline.income)
            assertEquals(Money(190_000, Money.CAD), state.expectedByMonthEnd)
        }

    @Test
    fun `a row dated next month waits above today but stays out of the expected balance`() =
        runTest {
            insert(amountMinor = 1_000, day = TODAY)
            insert(amountMinor = 5_000, day = LATER_THIS_MONTH)
            insert(amountMinor = 120_000, day = NEXT_MONTH)
            val state = settled()

            assertEquals(Money(-6_000, Money.CAD), state.expectedByMonthEnd)
            assertEquals(Money(-125_000, Money.CAD), state.upcomingSummary.balance)
        }

    @Test
    fun `a schedule counts on every day it falls on through the month's end, though only its next waits above today`() =
        runTest {
            repeat(Rhythm.WEEKLY, amountMinor = 10_000, from = TOMORROW)
            val state = settled()

            assertEquals(listOf(TOMORROW), state.upcoming.map { it.transaction.localDate })
            assertEquals(Money(-20_000, Money.CAD), state.expectedByMonthEnd)
        }

    @Test
    fun `with nothing still to come this month there is no expected balance`() =
        runTest {
            insert(amountMinor = 1_000, day = TODAY)
            insert(amountMinor = 120_000, day = NEXT_MONTH)

            assertNull(settled().expectedByMonthEnd)
        }

    @Test
    fun `a filter with dates has no expected balance, its dates being the period`() =
        runTest {
            insert(amountMinor = 1_000, day = TODAY)
            insert(amountMinor = 5_000, day = LATER_THIS_MONTH)

            viewModel.filters.onFromSelected(TODAY.withDayOfMonth(1))

            assertNull(viewModel.uiState.first { it.filter.hasDates }.expectedByMonthEnd)
        }

    @Test
    fun `under an account filter the expected balance is that account's`() =
        runTest {
            val chequing = account("Chèques", AccountType.BANK)
            insert(amountMinor = 145_000, day = LATER_THIS_MONTH, kind = TransactionKind.INCOME, accountId = chequing)
            insert(amountMinor = 5_000, day = LATER_THIS_MONTH)

            viewModel.filters.onAccountSelected(chequing)

            assertEquals(
                Money(145_000, Money.CAD),
                viewModel.uiState.first { it.filter.account != null }.expectedByMonthEnd,
            )
        }

    private suspend fun repeat(
        rhythm: Rhythm,
        amountMinor: Long,
        from: LocalDate,
    ) {
        val id =
            transactionRepository().addManual(
                amount = Money(amountMinor, Money.CAD),
                merchantName = "IGA",
                occurredAt = from.atTime(NOON, 0).atZone(ZONE).toInstant(),
                note = null,
                kind = TransactionKind.EXPENSE,
                accountId = null,
                toAccountId = null,
                categoryId = null,
                reach = null,
            )
        database.recurringRepository(clock, ZONE).repeat(id, rhythm, lastDate = null)
    }

    private companion object {
        val LATER_THIS_MONTH: LocalDate = TODAY.plusDays(7)
        val NEXT_MONTH: LocalDate = TODAY.plusMonths(1).withDayOfMonth(1)
    }
}
