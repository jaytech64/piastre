// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.feature.ledger

import androidx.lifecycle.SavedStateHandle
import io.github.jaytech64.piastre.core.data.AccountRepository
import io.github.jaytech64.piastre.core.data.CategoryRepository
import io.github.jaytech64.piastre.core.data.LocalDateTicker
import io.github.jaytech64.piastre.core.data.MerchantRepository
import io.github.jaytech64.piastre.core.data.TransactionRepository
import io.github.jaytech64.piastre.core.database.AccountEntity
import io.github.jaytech64.piastre.core.database.CategoryEntity
import io.github.jaytech64.piastre.core.database.DepensesDatabase
import io.github.jaytech64.piastre.core.database.ExpenseTransactionEntity
import io.github.jaytech64.piastre.core.database.MerchantEntity
import io.github.jaytech64.piastre.core.model.AccountOrigin
import io.github.jaytech64.piastre.core.model.AccountType
import io.github.jaytech64.piastre.core.model.Category
import io.github.jaytech64.piastre.core.model.Money
import io.github.jaytech64.piastre.core.model.TransactionKind
import io.github.jaytech64.piastre.core.model.TransactionSource
import io.github.jaytech64.piastre.core.model.TransactionStatus
import io.github.jaytech64.piastre.core.testing.inMemoryDatabase
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
class LedgerViewModelTest : LedgerTest() {
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
    fun `each day carries its own total and the month carries the headline`() =
        runTest {
            insert(amountMinor = 1_075, day = TODAY)
            insert(amountMinor = 2_342, day = TODAY)
            insert(amountMinor = 3_180, day = ANOTHER_DAY_THIS_MONTH)
            val state = settled()

            assertEquals(Money(-3_417, Money.CAD), state.days.single { it.date == TODAY }.total)
            assertEquals(Money(-3_180, Money.CAD), state.days.single { it.date == ANOTHER_DAY_THIS_MONTH }.total)
            assertEquals(Money(-6_597, Money.CAD), state.headline.balance)
        }

    @Test
    fun `spending from last month stays out of this month's balance`() =
        runTest {
            insert(amountMinor = 1_075, day = TODAY)
            insert(amountMinor = 9_900, day = LAST_MONTH)

            assertEquals(Money(-1_075, Money.CAD), settled().headline.balance)
        }

    @Test
    fun `a purchase in US dollars is left out of the totals and counted apart`() =
        runTest {
            insert(amountMinor = 1_075, day = TODAY)
            insert(amountMinor = 745, day = TODAY, currency = "USD")
            val state = settled()

            assertEquals(Money(-1_075, Money.CAD), state.days.single().total)
            assertEquals(
                2,
                state.days
                    .single()
                    .transactions.size,
            )
            assertEquals(Money(-1_075, Money.CAD), state.headline.balance)
            assertEquals(1, state.headline.otherCurrencyCount)
        }

    @Test
    fun `a month spent only in another currency counts zero Canadian dollars`() =
        runTest {
            insert(amountMinor = 745, day = TODAY, currency = "USD")
            val state = settled()

            assertEquals(Money(0, Money.CAD), state.days.single().total)
            assertEquals(Money(0, Money.CAD), state.headline.balance)
            assertEquals(1, state.headline.otherCurrencyCount)
        }

    @Test
    fun `a month with nothing spent in it yet shows a zero headline rather than last month's`() =
        runTest {
            insert(amountMinor = 3_180, day = LAST_MONTH)
            val state = settled()

            assertEquals(Money(0, Money.CAD), state.headline.balance)
            assertFalse(state.isEmpty)
        }

    /** A purchase dated ahead of today counts from its day, not before (ADR-0015). */
    @Test
    fun `an entry dated later this month waits above today and counts nowhere yet`() =
        runTest {
            insert(amountMinor = 1_075, day = TODAY)
            insert(amountMinor = 2_000, day = TODAY.withDayOfMonth(TODAY.lengthOfMonth()))
            val state = settled()

            assertEquals(Money(-1_075, Money.CAD), state.headline.balance)
            assertEquals(listOf(TODAY), state.days.map { it.date })
            assertEquals(listOf(2_000L), state.upcoming.map { it.transaction.amount.minor })
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

            assertEquals(Money(-1_075, Money.CAD), state.headline.balance)
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

    /** Income and spending meet in one signed balance. */
    @Test
    fun `income lifts the month's balance above zero`() =
        runTest {
            insert(amountMinor = 15_000, day = TODAY, kind = TransactionKind.INCOME)
            insert(amountMinor = 4_210, day = TODAY)
            val state = settled()

            assertEquals(Money(10_790, Money.CAD), state.headline.balance)
            assertEquals(Money(10_790, Money.CAD), state.days.single().total)
        }

    @Test
    fun `income and expenses are counted apart and add up to the balance`() =
        runTest {
            insert(amountMinor = 245_000, day = TODAY, kind = TransactionKind.INCOME)
            insert(amountMinor = 150_000, day = ANOTHER_DAY_THIS_MONTH)
            insert(amountMinor = 36_237, day = TODAY)
            insert(amountMinor = 9_900, day = LAST_MONTH)
            val month = settled().headline

            assertEquals(Money(245_000, Money.CAD), month.income)
            assertEquals(Money(186_237, Money.CAD), month.expenses)
            assertEquals(Money(58_763, Money.CAD), month.balance)
        }

    @Test
    fun `a purchase waiting for review is in neither figure`() =
        runTest {
            insert(amountMinor = 15_000, day = TODAY, kind = TransactionKind.INCOME)
            insert(amountMinor = 9_999, day = TODAY, status = TransactionStatus.NEEDS_REVIEW)
            val month = settled().headline

            assertEquals(Money(15_000, Money.CAD), month.income)
            assertEquals(Money(0, Money.CAD), month.expenses)
        }

    @Test
    fun `a transfer into a bank account is among the expenses, and one back out of it among the income`() =
        runTest {
            val desrives = account("Desrives")
            val savings = account("Épargne", AccountType.BANK)
            insert(amountMinor = 4_000, day = TODAY)
            insert(
                amountMinor = 120_000,
                day = TODAY,
                kind = TransactionKind.TRANSFER,
                accountId = desrives,
                toAccountId = account("Hypothèque", AccountType.BANK),
            )
            insert(
                amountMinor = 30_000,
                day = TODAY,
                kind = TransactionKind.TRANSFER,
                accountId = savings,
                toAccountId = desrives,
            )

            val state = settled()

            assertEquals(Money(30_000, Money.CAD), state.headline.income)
            assertEquals(Money(124_000, Money.CAD), state.headline.expenses)
            assertEquals(Money(0, Money.CAD), state.headline.transfers)
            assertEquals(Money(-94_000, Money.CAD), state.headline.balance)
            assertEquals(Money(-94_000, Money.CAD), state.days.single().total)
        }

    @Test
    fun `a transfer between cards adds nothing to any figure on the whole ledger`() =
        runTest {
            insert(
                amountMinor = 90_000,
                day = TODAY,
                kind = TransactionKind.TRANSFER,
                accountId = account("Desrives"),
                toAccountId = account("Visa"),
            )

            assertEquals(BalanceSummary(), settled().headline)
        }

    /**
     * Paying the card off is money reaching the card, but it is not income: the
     * two figures by kind would no longer add up to the balance above them, so the
     * payment is a figure of its own.
     */
    @Test
    fun `a card payment shows as a transfer on the card, not as income`() =
        runTest {
            val desrives = account("Desrives")
            val visa = account("Visa")
            insert(amountMinor = 121_480, day = TODAY, accountId = visa)
            insert(
                amountMinor = 90_000,
                day = TODAY,
                kind = TransactionKind.TRANSFER,
                accountId = desrives,
                toAccountId = visa,
            )

            viewModel.filters.onAccountSelected(visa)
            val month = settled().headline

            assertEquals(Money(0, Money.CAD), month.income)
            assertEquals(Money(121_480, Money.CAD), month.expenses)
            assertEquals(Money(90_000, Money.CAD), month.transfers)
            assertEquals(Money(-31_480, Money.CAD), month.balance)

            viewModel.filters.onAccountSelected(desrives)
            assertEquals(
                Money(-90_000, Money.CAD),
                viewModel.uiState
                    .first { it.filter.account?.id == desrives }
                    .headline.transfers,
            )
        }

    @Test
    fun `every account is offered, including one nothing has been spent from`() =
        runTest {
            val desrives = account("Desrives")
            account("Épargne")
            insert(amountMinor = 1_075, day = TODAY, accountId = desrives)

            assertEquals(listOf("Desrives", "Épargne"), settled().accounts.map { it.name })
        }

    @Test
    fun `the ledger narrows to the account you pick`() =
        runTest {
            val desrives = account("Desrives")
            val visa = account("Visa")
            insert(amountMinor = 1_075, day = TODAY, accountId = desrives)
            insert(amountMinor = 4_820, day = TODAY, accountId = visa)

            viewModel.filters.onAccountSelected(desrives)
            val state = settled()

            assertEquals("Desrives", state.filter.account?.name)
            assertEquals(Money(-1_075, Money.CAD), state.headline.balance)
            assertEquals(
                listOf(1_075L),
                state.days
                    .single()
                    .transactions
                    .map { it.amount.minor },
            )
        }

    /** A purchase with no account named is somebody's spending, but not this account's. */
    @Test
    fun `a purchase that named no account is left out of a filtered ledger`() =
        runTest {
            val desrives = account("Desrives")
            insert(amountMinor = 1_075, day = TODAY, accountId = desrives)
            insert(amountMinor = 2_000, day = TODAY, accountId = null)

            viewModel.filters.onAccountSelected(desrives)

            assertEquals(Money(-1_075, Money.CAD), settled().headline.balance)
        }

    @Test
    fun `a transfer shows on the account it left and on the one it reached`() =
        runTest {
            val desrives = account("Desrives")
            val cash = account("Comptant")
            insert(
                amountMinor = 6_000,
                day = TODAY,
                kind = TransactionKind.TRANSFER,
                accountId = desrives,
                toAccountId = cash,
            )

            viewModel.filters.onAccountSelected(desrives)
            assertEquals(
                1,
                settled()
                    .days
                    .single()
                    .transactions.size,
            )

            viewModel.filters.onAccountSelected(cash)
            assertEquals(
                1,
                viewModel.uiState
                    .first { it.filter.account?.id == cash }
                    .days
                    .single()
                    .transactions.size,
            )
        }

    /**
     * The invariant that a transfer moves a balance by zero is about the ledger as
     * a whole. One account is not the whole ledger: money that left for another
     * account of yours has still left this one, and the balance the filter menu
     * offers is already counted that way.
     */
    @Test
    fun `a transfer takes from the account it left and adds to the one it reached`() =
        runTest {
            val desrives = account("Desrives")
            val cash = account("Comptant")
            insert(amountMinor = 575, day = TODAY, accountId = desrives)
            insert(
                amountMinor = 6_000,
                day = YESTERDAY,
                kind = TransactionKind.TRANSFER,
                accountId = desrives,
                toAccountId = cash,
            )

            viewModel.filters.onAccountSelected(desrives)
            assertEquals(Money(-6_575, Money.CAD), settled().headline.balance)

            viewModel.filters.onAccountSelected(cash)
            assertEquals(
                Money(6_000, Money.CAD),
                viewModel.uiState
                    .first { it.filter.account?.id == cash }
                    .headline.balance,
            )
        }

    @Test
    fun `a transfer between a card and cash still moves the whole ledger by nothing`() =
        runTest {
            val desrives = account("Desrives")
            val cash = account("Comptant")
            insert(
                amountMinor = 6_000,
                day = TODAY,
                kind = TransactionKind.TRANSFER,
                accountId = desrives,
                toAccountId = cash,
            )

            val state = settled()

            assertEquals(Money(0, Money.CAD), state.headline.balance)
            assertEquals(Money(0, Money.CAD), state.days.single().total)
        }

    @Test
    fun `an account nothing has touched shows an empty ledger rather than everybody else's`() =
        runTest {
            val desrives = account("Desrives")
            val epargne = account("Épargne")
            insert(amountMinor = 1_075, day = TODAY, accountId = desrives)

            viewModel.filters.onAccountSelected(epargne)
            val state = viewModel.uiState.first { it.filter.account?.id == epargne }

            assertTrue(state.isEmpty)
            assertEquals(Money(0, Money.CAD), state.headline.balance)
        }

    /**
     * The bottom bar pops the ledger and clears the view model scoped to it, so the
     * filter has to come back from saved state or it is lost on the way to Reports.
     */
    @Test
    fun `the filter survives the screen being thrown away and rebuilt`() =
        runTest {
            val desrives = account("Desrives")
            insert(amountMinor = 1_075, day = TODAY, accountId = desrives)
            viewModel.filters.onAccountSelected(desrives)
            settled()

            val rebuilt = ledgerViewModel(savedState)

            assertEquals(
                "Desrives",
                rebuilt.uiState
                    .first { !it.loading }
                    .filter.account
                    ?.name,
            )
        }

    /** Filtering to an account that has since been deleted would show nothing, forever. */
    @Test
    fun `an account removed in settings stops filtering the ledger`() =
        runTest {
            val desrives = account("Desrives")
            insert(amountMinor = 1_075, day = TODAY, accountId = null)
            viewModel.filters.onAccountSelected(desrives)
            assertEquals("Desrives", settled().filter.account?.name)

            database.accountDao().deleteIfUnused(desrives)
            val state = viewModel.uiState.first { it.filter.account == null }

            assertNull(state.filter.account)
            assertEquals(Money(-1_075, Money.CAD), state.headline.balance)
        }
}
