// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.feature.ledger

import androidx.lifecycle.SavedStateHandle
import io.github.jaytech64.depenses.core.data.AccountRepository
import io.github.jaytech64.depenses.core.data.CategoryRepository
import io.github.jaytech64.depenses.core.data.LocalDateTicker
import io.github.jaytech64.depenses.core.data.MerchantRepository
import io.github.jaytech64.depenses.core.data.TransactionRepository
import io.github.jaytech64.depenses.core.database.AccountEntity
import io.github.jaytech64.depenses.core.database.CategoryEntity
import io.github.jaytech64.depenses.core.database.DepensesDatabase
import io.github.jaytech64.depenses.core.database.ExpenseTransactionEntity
import io.github.jaytech64.depenses.core.database.MerchantEntity
import io.github.jaytech64.depenses.core.model.AccountOrigin
import io.github.jaytech64.depenses.core.model.AccountType
import io.github.jaytech64.depenses.core.model.Category
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
class LedgerViewModelTest {
    private lateinit var database: DepensesDatabase
    private lateinit var savedState: SavedStateHandle
    private lateinit var viewModel: LedgerViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        database = inMemoryDatabase()
        savedState = SavedStateHandle()
        viewModel = ledgerViewModel(savedState)
    }

    private fun ledgerViewModel(state: SavedStateHandle) =
        LedgerViewModel(
            repository = transactionRepository(),
            accounts = AccountRepository(database.accountDao()),
            categories = categoryRepository(),
            ticker = LocalDateTicker(Provider { ZONE }),
            savedState = state,
        )

    private fun transactionRepository() =
        TransactionRepository(
            database = database,
            transactions = database.transactionDao(),
            merchants = MerchantRepository(database.merchantDao()),
            categories = categoryRepository(),
            zoneId = Provider { ZONE },
        )

    private fun categoryRepository() =
        CategoryRepository(
            database.categoryDao(),
            database.merchantDao(),
            database.accountDao(),
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
    fun `each day carries its own total and the month carries the headline`() =
        runTest {
            insert(amountMinor = 1_075, day = TODAY)
            insert(amountMinor = 2_342, day = TODAY)
            insert(amountMinor = 3_180, day = ANOTHER_DAY_THIS_MONTH)
            val state = settled()

            assertEquals(Money(-3_417, Money.CAD), state.days.single { it.date == TODAY }.total)
            assertEquals(Money(-3_180, Money.CAD), state.days.single { it.date == ANOTHER_DAY_THIS_MONTH }.total)
            assertEquals(Money(-6_597, Money.CAD), state.thisMonth.balance)
        }

    @Test
    fun `spending from last month stays out of this month's balance`() =
        runTest {
            insert(amountMinor = 1_075, day = TODAY)
            insert(amountMinor = 9_900, day = LAST_MONTH)

            assertEquals(Money(-1_075, Money.CAD), settled().thisMonth.balance)
        }

    @Test
    fun `a month with nothing spent in it yet shows a zero headline rather than last month's`() =
        runTest {
            insert(amountMinor = 3_180, day = LAST_MONTH)
            val state = settled()

            assertEquals(Money(0, Money.CAD), state.thisMonth.balance)
            assertFalse(state.isEmpty)
        }

    /** A purchase dated ahead of today is still this month's spending. */
    @Test
    fun `an entry dated later this month counts toward the headline`() =
        runTest {
            insert(amountMinor = 1_075, day = TODAY)
            insert(amountMinor = 2_000, day = TODAY.withDayOfMonth(TODAY.lengthOfMonth()))

            assertEquals(Money(-3_075, Money.CAD), settled().thisMonth.balance)
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

            assertEquals(Money(-1_075, Money.CAD), state.thisMonth.balance)
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

            assertEquals(Money(10_790, Money.CAD), state.thisMonth.balance)
            assertEquals(Money(10_790, Money.CAD), state.days.single().total)
        }

    @Test
    fun `income and expenses are counted apart and add up to the balance`() =
        runTest {
            insert(amountMinor = 245_000, day = TODAY, kind = TransactionKind.INCOME)
            insert(amountMinor = 150_000, day = ANOTHER_DAY_THIS_MONTH)
            insert(amountMinor = 36_237, day = TODAY)
            insert(amountMinor = 9_900, day = LAST_MONTH)
            val month = settled().thisMonth

            assertEquals(Money(245_000, Money.CAD), month.income)
            assertEquals(Money(186_237, Money.CAD), month.expenses)
            assertEquals(Money(58_763, Money.CAD), month.balance)
        }

    @Test
    fun `a purchase waiting for review is in neither figure`() =
        runTest {
            insert(amountMinor = 15_000, day = TODAY, kind = TransactionKind.INCOME)
            insert(amountMinor = 9_999, day = TODAY, status = TransactionStatus.NEEDS_REVIEW)
            val month = settled().thisMonth

            assertEquals(Money(15_000, Money.CAD), month.income)
            assertEquals(Money(0, Money.CAD), month.expenses)
        }

    @Test
    fun `a transfer adds nothing to any figure on the whole ledger`() =
        runTest {
            insert(
                amountMinor = 90_000,
                day = TODAY,
                kind = TransactionKind.TRANSFER,
                accountId = account("Desrives"),
                toAccountId = account("Visa"),
            )

            assertEquals(MonthSummary(), settled().thisMonth)
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

            viewModel.onAccountSelected(visa)
            val month = settled().thisMonth

            assertEquals(Money(0, Money.CAD), month.income)
            assertEquals(Money(121_480, Money.CAD), month.expenses)
            assertEquals(Money(90_000, Money.CAD), month.transfers)
            assertEquals(Money(-31_480, Money.CAD), month.balance)

            viewModel.onAccountSelected(desrives)
            assertEquals(
                Money(-90_000, Money.CAD),
                viewModel.uiState
                    .first { it.filter.account?.id == desrives }
                    .thisMonth.transfers,
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

            viewModel.onAccountSelected(desrives)
            val state = settled()

            assertEquals("Desrives", state.filter.account?.name)
            assertEquals(Money(-1_075, Money.CAD), state.thisMonth.balance)
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

            viewModel.onAccountSelected(desrives)

            assertEquals(Money(-1_075, Money.CAD), settled().thisMonth.balance)
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

            viewModel.onAccountSelected(desrives)
            assertEquals(
                1,
                settled()
                    .days
                    .single()
                    .transactions.size,
            )

            viewModel.onAccountSelected(cash)
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

            viewModel.onAccountSelected(desrives)
            assertEquals(Money(-6_575, Money.CAD), settled().thisMonth.balance)

            viewModel.onAccountSelected(cash)
            assertEquals(
                Money(6_000, Money.CAD),
                viewModel.uiState
                    .first { it.filter.account?.id == cash }
                    .thisMonth.balance,
            )
        }

    @Test
    fun `a transfer still moves the whole ledger by nothing`() =
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

            assertEquals(Money(0, Money.CAD), state.thisMonth.balance)
            assertEquals(Money(0, Money.CAD), state.days.single().total)
        }

    @Test
    fun `an account nothing has touched shows an empty ledger rather than everybody else's`() =
        runTest {
            val desrives = account("Desrives")
            val epargne = account("Épargne")
            insert(amountMinor = 1_075, day = TODAY, accountId = desrives)

            viewModel.onAccountSelected(epargne)
            val state = viewModel.uiState.first { it.filter.account?.id == epargne }

            assertTrue(state.isEmpty)
            assertEquals(Money(0, Money.CAD), state.thisMonth.balance)
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
            viewModel.onAccountSelected(desrives)
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
            viewModel.onAccountSelected(desrives)
            assertEquals("Desrives", settled().filter.account?.name)

            database.accountDao().deleteIfUnused(desrives)
            val state = viewModel.uiState.first { it.filter.account == null }

            assertNull(state.filter.account)
            assertEquals(Money(-1_075, Money.CAD), state.thisMonth.balance)
        }

    @Test
    fun `the ledger narrows to the category you pick`() =
        runTest {
            val groceries = category("Épicerie")
            val restaurants = category("Restaurants")
            insert(amountMinor = 4_000, day = TODAY, categoryId = groceries)
            insert(amountMinor = 1_850, day = TODAY, categoryId = restaurants)

            viewModel.onCategorySelected(CategoryChoice.Named(Category(groceries, "Épicerie")))
            val state = viewModel.uiState.first { it.filter.category != null }

            assertEquals(CategoryChoice.Named(Category(groceries, "Épicerie")), state.filter.category)
            assertEquals(Money(-4_000, Money.CAD), state.thisMonth.balance)
            assertEquals(
                listOf(4_000L),
                state.days
                    .single()
                    .transactions
                    .map { it.amount.minor },
            )
        }

    @Test
    fun `no category finds only the purchases nobody has sorted yet`() =
        runTest {
            val groceries = category("Épicerie")
            insert(amountMinor = 4_000, day = TODAY, categoryId = groceries)
            insert(amountMinor = 575, day = TODAY, categoryId = null)

            viewModel.onCategorySelected(CategoryChoice.Uncategorised)
            val state = viewModel.uiState.first { it.filter.category != null }

            assertEquals(Money(-575, Money.CAD), state.thisMonth.balance)
        }

    @Test
    fun `an account and a category together narrow to what matches both`() =
        runTest {
            val visa = account("Visa")
            val desrives = account("Desrives")
            val groceries = category("Épicerie")
            insert(amountMinor = 4_000, day = TODAY, accountId = visa, categoryId = groceries)
            insert(amountMinor = 3_000, day = TODAY, accountId = desrives, categoryId = groceries)
            insert(amountMinor = 1_850, day = TODAY, accountId = visa, categoryId = null)

            viewModel.onAccountSelected(visa)
            viewModel.onCategorySelected(CategoryChoice.Named(Category(groceries, "Épicerie")))
            val state = viewModel.uiState.first { it.filter.account != null && it.filter.category != null }

            assertEquals(Money(-4_000, Money.CAD), state.thisMonth.balance)
        }

    /**
     * A transfer is worth zero to the ledger as a whole, but the mortgage payment
     * is exactly what the *Hypothèque* category is for: under that filter it is
     * money that left, or the headline says nothing was spent over rows that say
     * otherwise (ADR-0011).
     */
    @Test
    fun `a transfer under a category is money that left for it`() =
        runTest {
            val desrives = account("Desrives")
            val mortgage = account("Hypothèque")
            val mortgageCategory = category("Hypothèque")
            insert(
                amountMinor = 120_000,
                day = TODAY,
                kind = TransactionKind.TRANSFER,
                accountId = desrives,
                toAccountId = mortgage,
                categoryId = mortgageCategory,
            )

            viewModel.onCategorySelected(CategoryChoice.Named(Category(mortgageCategory, "Hypothèque")))
            val state = viewModel.uiState.first { it.filter.category != null }

            assertEquals(Money(-120_000, Money.CAD), state.thisMonth.transfers)
            assertEquals(Money(-120_000, Money.CAD), state.thisMonth.balance)
            assertEquals(Money(-120_000, Money.CAD), state.days.single().total)
        }

    /** Under an account as well, the account's own arithmetic still decides (ADR-0006). */
    @Test
    fun `a transfer under an account and a category is signed by the account's end`() =
        runTest {
            val desrives = account("Desrives")
            val mortgage = account("Hypothèque")
            val mortgageCategory = category("Hypothèque")
            insert(
                amountMinor = 120_000,
                day = TODAY,
                kind = TransactionKind.TRANSFER,
                accountId = desrives,
                toAccountId = mortgage,
                categoryId = mortgageCategory,
            )

            viewModel.onAccountSelected(mortgage)
            viewModel.onCategorySelected(CategoryChoice.Named(Category(mortgageCategory, "Hypothèque")))
            val state = viewModel.uiState.first { it.filter.account != null && it.filter.category != null }

            assertEquals(Money(120_000, Money.CAD), state.thisMonth.balance)
        }

    /** A card payment is most of what goes uncategorised, and counting it would charge the purchases twice. */
    @Test
    fun `an uncategorised transfer still moves nothing under no category`() =
        runTest {
            val desrives = account("Desrives")
            val visa = account("Visa")
            insert(
                amountMinor = 45_480,
                day = TODAY,
                kind = TransactionKind.TRANSFER,
                accountId = desrives,
                toAccountId = visa,
            )

            viewModel.onCategorySelected(CategoryChoice.Uncategorised)
            val state = viewModel.uiState.first { it.filter.category != null }

            assertEquals(
                1,
                state.days
                    .single()
                    .transactions.size,
            )
            assertEquals(Money(0, Money.CAD), state.thisMonth.balance)
        }

    @Test
    fun `the categories offered are the ones in use, unsorted rows first, then the most used`() =
        runTest {
            val groceries = category("Épicerie")
            val restaurants = category("Restaurants")
            category("Jamais utilisée")
            insert(amountMinor = 4_000, day = TODAY, categoryId = groceries)
            insert(amountMinor = 1_850, day = TODAY, categoryId = restaurants)
            insert(amountMinor = 1_900, day = YESTERDAY, categoryId = restaurants)
            insert(amountMinor = 575, day = TODAY, categoryId = null)

            assertEquals(
                listOf(
                    FilterOption<CategoryChoice>(CategoryChoice.Uncategorised, 1),
                    FilterOption<CategoryChoice>(CategoryChoice.Named(Category(restaurants, "Restaurants")), 2),
                    FilterOption<CategoryChoice>(CategoryChoice.Named(Category(groceries, "Épicerie")), 1),
                ),
                settled().categories,
            )
        }

    @Test
    fun `a category deleted in settings stops filtering the ledger`() =
        runTest {
            val groceries = category("Épicerie")
            insert(amountMinor = 4_000, day = TODAY, categoryId = groceries)
            insert(amountMinor = 575, day = TODAY, categoryId = null)
            viewModel.onCategorySelected(CategoryChoice.Named(Category(groceries, "Épicerie")))
            viewModel.uiState.first { it.filter.category != null }

            database.categoryDao().delete(groceries)
            val state = viewModel.uiState.first { it.filter.category == null }

            assertEquals(Money(-4_575, Money.CAD), state.thisMonth.balance)
        }

    @Test
    fun `clearing the filters shows the whole ledger again`() =
        runTest {
            val visa = account("Visa")
            insert(amountMinor = 4_000, day = TODAY, accountId = visa)
            insert(amountMinor = 575, day = TODAY)
            viewModel.onAccountSelected(visa)
            viewModel.onCategorySelected(CategoryChoice.Uncategorised)
            viewModel.uiState.first { it.filter.isActive }

            viewModel.onFiltersCleared()
            val state = viewModel.uiState.first { !it.filter.isActive }

            assertEquals(Money(-4_575, Money.CAD), state.thisMonth.balance)
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
        accountId: Long? = null,
        toAccountId: Long? = null,
        categoryId: Long? = null,
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
            categoryId = categoryId,
            source = TransactionSource.NOTIFICATION,
            status = status,
            reviewReason = null,
            reviewedAtUtcMillis = null,
            confidence = 1f,
            parserId = "google-wallet",
            parserVersion = 1,
            sourcePackage = "com.google.android.apps.walletnfcrel",
            sourceKey = "tag-$amountMinor-${day.toEpochDay()}-$status-$accountId-$toAccountId-$categoryId",
            cardLabel = "Récompenses Visa",
            cardLast4 = "0000",
            note = null,
            kind = kind,
            accountId = accountId,
            toAccountId = toAccountId,
        ),
    )

    private suspend fun account(name: String) =
        database.accountDao().insert(
            AccountEntity(
                name = name,
                type = AccountType.BANK,
                issuerLabel = null,
                last4 = null,
                origin = AccountOrigin.USER,
            ),
        )

    private suspend fun category(name: String) =
        database.categoryDao().insert(CategoryEntity(name = name, normalisedName = name.uppercase()))

    private companion object {
        val ZONE: ZoneId = ZoneId.of("America/Toronto")
        val TODAY: LocalDate = LocalDate.now(ZONE)
        val YESTERDAY: LocalDate = TODAY.minusDays(1)
        val LAST_MONTH: LocalDate = TODAY.minusMonths(1)

        /**
         * A second day inside the current month whichever day the suite runs on:
         * yesterday belongs to the month before it on the first of every month.
         */
        val ANOTHER_DAY_THIS_MONTH: LocalDate = if (TODAY.dayOfMonth == 1) TODAY.plusDays(1) else YESTERDAY
        const val NOON = 12
    }
}
