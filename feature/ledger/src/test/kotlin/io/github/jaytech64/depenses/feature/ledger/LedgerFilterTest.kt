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
import io.github.jaytech64.depenses.core.model.CategoryGroup
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

/** The ledger narrowed to a category, a merchant, or several filters at once. */
@RunWith(RobolectricTestRunner::class)
class LedgerFilterTest : LedgerTest() {
    @Test
    fun `the ledger narrows to the category you pick`() =
        runTest {
            val groceries = category("Épicerie")
            val restaurants = category("Restaurants")
            insert(amountMinor = 4_000, day = TODAY, categoryId = groceries)
            insert(amountMinor = 1_850, day = TODAY, categoryId = restaurants)

            viewModel.filters.onCategorySelected(CategoryChoice.Named(Category(groceries, "Épicerie")))
            val state = viewModel.uiState.first { it.filter.category != null }

            assertEquals(CategoryChoice.Named(Category(groceries, "Épicerie")), state.filter.category)
            assertEquals(Money(-4_000, Money.CAD), state.headline.balance)
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

            viewModel.filters.onCategorySelected(CategoryChoice.Uncategorised)
            val state = viewModel.uiState.first { it.filter.category != null }

            assertEquals(Money(-575, Money.CAD), state.headline.balance)
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

            viewModel.filters.onAccountSelected(visa)
            viewModel.filters.onCategorySelected(CategoryChoice.Named(Category(groceries, "Épicerie")))
            val state = viewModel.uiState.first { it.filter.account != null && it.filter.category != null }

            assertEquals(Money(-4_000, Money.CAD), state.headline.balance)
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

            viewModel.filters.onCategorySelected(CategoryChoice.Named(Category(mortgageCategory, "Hypothèque")))
            val state = viewModel.uiState.first { it.filter.category != null }

            assertEquals(Money(-120_000, Money.CAD), state.headline.transfers)
            assertEquals(Money(-120_000, Money.CAD), state.headline.balance)
            assertEquals(Money(-120_000, Money.CAD), state.days.single().total)
        }

    @Test
    fun `a group shows every category in it, and adds up to them`() =
        runTest {
            val home = categoryRepository().namedGroup("Maison")!!
            val power = categoryRepository().named("Électricité", home)!!
            val insurance = categoryRepository().named("Assurance", home)!!
            val desrives = account("Desrives")
            val mortgage = account("Hypothèque")
            insert(amountMinor = 8_410, day = TODAY, categoryId = power)
            insert(amountMinor = 6_000, day = TODAY, categoryId = insurance)
            insert(
                amountMinor = 120_000,
                day = TODAY,
                kind = TransactionKind.TRANSFER,
                accountId = desrives,
                toAccountId = mortgage,
                categoryId = categoryRepository().named("Hypothèque", home),
            )
            insert(amountMinor = 4_000, day = TODAY, categoryId = category("Épicerie"))

            viewModel.filters.onCategorySelected(CategoryChoice.InGroup(CategoryGroup(home, "Maison"), emptySet()))
            val state = viewModel.uiState.first { it.filter.category != null }

            assertEquals("Maison", (state.filter.category as CategoryChoice.InGroup).group.name)
            assertEquals(
                3,
                state.days
                    .single()
                    .transactions.size,
            )
            assertEquals(Money(-134_410, Money.CAD), state.headline.balance)
            assertEquals(Money(-120_000, Money.CAD), state.headline.transfers)
        }

    @Test
    fun `the filter offers each group some row's category is in, counting all its rows`() =
        runTest {
            val home = categoryRepository().namedGroup("Maison")!!
            categoryRepository().namedGroup("Auto")
            insert(amountMinor = 8_410, day = TODAY, categoryId = categoryRepository().named("Électricité", home))
            insert(amountMinor = 6_000, day = TODAY, categoryId = categoryRepository().named("Assurance", home))

            val state = viewModel.uiState.first { it.categoryGroups.isNotEmpty() }

            assertEquals(listOf("Maison" to 2), state.categoryGroups.map { it.value.group.name to it.count })
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

            viewModel.filters.onAccountSelected(mortgage)
            viewModel.filters.onCategorySelected(CategoryChoice.Named(Category(mortgageCategory, "Hypothèque")))
            val state = viewModel.uiState.first { it.filter.account != null && it.filter.category != null }

            assertEquals(Money(120_000, Money.CAD), state.headline.balance)
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

            viewModel.filters.onCategorySelected(CategoryChoice.Uncategorised)
            val state = viewModel.uiState.first { it.filter.category != null }

            assertEquals(
                1,
                state.days
                    .single()
                    .transactions.size,
            )
            assertEquals(Money(0, Money.CAD), state.headline.balance)
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
            viewModel.filters.onCategorySelected(CategoryChoice.Named(Category(groceries, "Épicerie")))
            viewModel.uiState.first { it.filter.category != null }

            database.categoryDao().delete(groceries)
            val state = viewModel.uiState.first { it.filter.category == null }

            assertEquals(Money(-4_575, Money.CAD), state.headline.balance)
        }

    @Test
    fun `clearing the filters shows the whole ledger again`() =
        runTest {
            val visa = account("Visa")
            insert(amountMinor = 4_000, day = TODAY, accountId = visa)
            insert(amountMinor = 575, day = TODAY)
            viewModel.filters.onAccountSelected(visa)
            viewModel.filters.onCategorySelected(CategoryChoice.Uncategorised)
            viewModel.uiState.first { it.filter.isActive }

            viewModel.filters.onFiltersCleared()
            val state = viewModel.uiState.first { !it.filter.isActive }

            assertEquals(Money(-4_575, Money.CAD), state.headline.balance)
        }

    @Test
    fun `the ledger narrows to the merchant you pick, whatever Wallet called it`() =
        runTest {
            val metro = merchant("Metro Plus")
            val zoo = merchant("Zoo Cafe")
            insert(amountMinor = 4_000, day = TODAY, merchantId = metro, merchantRaw = "METRO PLUS #123")
            insert(amountMinor = 3_000, day = YESTERDAY, merchantId = metro, merchantRaw = "METRO PLUS ST-JOSEPH")
            insert(amountMinor = 500, day = TODAY, merchantId = zoo)

            viewModel.filters.onMerchantSelected(metro)
            val state = viewModel.uiState.first { it.filter.merchant != null }

            assertEquals("Metro Plus", state.filter.merchant?.displayName)
            assertEquals(Money(-7_000, Money.CAD), state.headline.balance)
            assertEquals(2, state.days.sumOf { it.transactions.size })
        }

    @Test
    fun `all three filters together narrow to what matches every one`() =
        runTest {
            val visa = account("Visa")
            val groceries = category("Épicerie")
            val metro = merchant("Metro Plus")
            val iga = merchant("IGA")
            insert(amountMinor = 4_000, day = TODAY, accountId = visa, categoryId = groceries, merchantId = metro)
            insert(amountMinor = 3_000, day = TODAY, accountId = visa, categoryId = groceries, merchantId = iga)
            insert(amountMinor = 2_000, day = TODAY, accountId = visa, categoryId = null, merchantId = metro)

            viewModel.filters.onAccountSelected(visa)
            viewModel.filters.onCategorySelected(CategoryChoice.Named(Category(groceries, "Épicerie")))
            viewModel.filters.onMerchantSelected(metro)
            val state =
                viewModel.uiState.first {
                    it.filter.account != null && it.filter.category != null && it.filter.merchant != null
                }

            assertEquals(Money(-4_000, Money.CAD), state.headline.balance)
        }

    @Test
    fun `the merchants offered are the ones in use, the most used first, under their names today`() =
        runTest {
            val metro = merchant("Metro Plus")
            val zoo = merchant("Zoo Cafe")
            merchant("Jamais visité")
            insert(amountMinor = 500, day = TODAY, merchantId = zoo)
            insert(amountMinor = 4_000, day = TODAY, merchantId = metro)
            insert(amountMinor = 3_000, day = YESTERDAY, merchantId = metro)
            database.merchantDao().renameMerchant(zoo, "Café du Zoo")

            assertEquals(
                listOf("Metro Plus" to 2, "Café du Zoo" to 1),
                viewModel.uiState
                    .first { state -> state.merchants.any { it.value.displayName == "Café du Zoo" } }
                    .merchants
                    .map { it.value.displayName to it.count },
            )
        }

    /** Editing the last purchase at a merchant away leaves an empty list, not somebody else's spending. */
    @Test
    fun `a merchant with nothing left under it still filters the ledger`() =
        runTest {
            val metro = merchant("Metro Plus")
            insert(amountMinor = 500, day = TODAY)
            viewModel.filters.onMerchantSelected(metro)

            val state = viewModel.uiState.first { it.filter.merchant != null }

            assertTrue(state.isEmpty)
        }

    @Test
    fun `every filter survives the screen being thrown away and rebuilt`() =
        runTest {
            val visa = account("Visa")
            val metro = merchant("Metro Plus")
            insert(amountMinor = 4_000, day = TODAY, accountId = visa, merchantId = metro)
            viewModel.filters.onAccountSelected(visa)
            viewModel.filters.onCategorySelected(CategoryChoice.Uncategorised)
            viewModel.filters.onMerchantSelected(metro)
            viewModel.filters.onFromSelected(LAST_MONTH)
            viewModel.filters.onToSelected(TODAY)
            viewModel.uiState.first { it.filter.merchant != null && it.filter.to != null }

            val rebuilt = ledgerViewModel(savedState).uiState.first { !it.loading }.filter

            assertEquals("Visa", rebuilt.account?.name)
            assertEquals(CategoryChoice.Uncategorised, rebuilt.category)
            assertEquals("Metro Plus", rebuilt.merchant?.displayName)
            assertEquals(LAST_MONTH, rebuilt.from)
            assertEquals(TODAY, rebuilt.to)
        }

    @Test
    fun `the ledger narrows to the days between two dates, both included`() =
        runTest {
            insert(amountMinor = 100, day = TWO_MONTHS_AGO)
            insert(amountMinor = 200, day = LAST_MONTH)
            insert(amountMinor = 400, day = YESTERDAY)
            insert(amountMinor = 800, day = TODAY)

            viewModel.filters.onFromSelected(LAST_MONTH)
            viewModel.filters.onToSelected(YESTERDAY)
            val state = viewModel.uiState.first { it.filter.from != null && it.filter.to != null }

            assertEquals(listOf(YESTERDAY, LAST_MONTH), state.days.map { it.date })
        }

    @Test
    fun `a date with no other end shows every day on its side of it`() =
        runTest {
            insert(amountMinor = 100, day = TWO_MONTHS_AGO)
            insert(amountMinor = 200, day = LAST_MONTH)
            insert(amountMinor = 800, day = TODAY)

            viewModel.filters.onFromSelected(LAST_MONTH)
            val after = viewModel.uiState.first { it.filter.from != null }
            viewModel.filters.onFromSelected(null)
            viewModel.filters.onToSelected(LAST_MONTH)
            val before = viewModel.uiState.first { it.filter.from == null && it.filter.to != null }

            assertEquals(listOf(TODAY, LAST_MONTH), after.days.map { it.date })
            assertEquals(listOf(LAST_MONTH, TWO_MONTHS_AGO), before.days.map { it.date })
        }

    @Test
    fun `under a date filter the headline counts every day shown, not this month`() =
        runTest {
            insert(amountMinor = 200, day = LAST_MONTH)
            insert(amountMinor = 800, day = TODAY)

            viewModel.filters.onFromSelected(LAST_MONTH)
            val state = viewModel.uiState.first { it.filter.from != null }

            assertEquals(Money(-1_000, Money.CAD), state.headline.balance)
        }

    @Test
    fun `a period that ended before this month still has a balance`() =
        runTest {
            insert(amountMinor = 200, day = LAST_MONTH)
            insert(amountMinor = 800, day = TODAY)

            viewModel.filters.onFromSelected(LAST_MONTH)
            viewModel.filters.onToSelected(LAST_MONTH)
            val state = viewModel.uiState.first { it.filter.to != null }

            assertEquals(Money(-200, Money.CAD), state.headline.balance)
        }

    @Test
    fun `without dates a filtered headline is still this month's`() =
        runTest {
            val visa = account("Visa")
            insert(amountMinor = 200, day = LAST_MONTH, accountId = visa)
            insert(amountMinor = 800, day = TODAY, accountId = visa)

            viewModel.filters.onAccountSelected(visa)
            val state = viewModel.uiState.first { it.filter.account != null }

            assertEquals(Money(-800, Money.CAD), state.headline.balance)
            assertEquals(2, state.shownCount)
        }

    @Test
    fun `clearing the filters takes the dates away too`() =
        runTest {
            insert(amountMinor = 200, day = LAST_MONTH)
            viewModel.filters.onFromSelected(TODAY)
            viewModel.uiState.first { it.filter.from != null }

            viewModel.filters.onFiltersCleared()
            val state = viewModel.uiState.first { !it.filter.isActive }

            assertEquals(1, state.shownCount)
        }

    @Test
    fun `the ledger narrows to amounts between two ends, both included`() =
        runTest {
            insert(amountMinor = 1_999, day = TODAY)
            insert(amountMinor = 2_000, day = TODAY)
            insert(amountMinor = 5_000, day = TODAY)
            insert(amountMinor = 5_001, day = TODAY)

            viewModel.filters.onAtLeastTyped("20")
            viewModel.filters.onAtMostTyped("50,00")
            val state = viewModel.uiState.first { it.filter.atLeast != null && it.filter.atMost != null }

            assertEquals(Money(-7_000, Money.CAD), state.headline.balance)
        }

    @Test
    fun `an amount is matched on its size, whichever way the money went`() =
        runTest {
            insert(amountMinor = 5_000, day = TODAY, kind = TransactionKind.INCOME, merchantRaw = "PAIE")
            insert(amountMinor = 5_000, day = TODAY, kind = TransactionKind.EXPENSE)
            insert(amountMinor = 500, day = TODAY)

            viewModel.filters.onAtLeastTyped("40")
            val state = viewModel.uiState.first { it.filter.atLeast != null }

            assertEquals(2, state.shownCount)
        }

    @Test
    fun `an amount in another currency is matched on the number it shows`() =
        runTest {
            insert(amountMinor = 3_000, day = TODAY, currency = "USD")
            insert(amountMinor = 7_000, day = TODAY)

            viewModel.filters.onAtMostTyped("50.00")
            val state = viewModel.uiState.first { it.filter.atMost != null }

            assertEquals(1, state.shownCount)
            assertEquals(1, state.headline.otherCurrencyCount)
        }

    @Test
    fun `a minimum above the maximum narrows nothing and says so`() =
        runTest {
            insert(amountMinor = 3_000, day = TODAY)

            viewModel.filters.onAtLeastTyped("50")
            viewModel.filters.onAtMostTyped("20")
            val state = viewModel.uiState.first { it.amountsCrossed }

            assertNull(state.filter.atLeast)
            assertNull(state.filter.atMost)
            assertEquals(1, state.shownCount)
        }

    @Test
    fun `what was typed in an amount survives the screen being rebuilt`() =
        runTest {
            viewModel.filters.onAtLeastTyped("12,")
            viewModel.uiState.first { it.typedAtLeast == "12," }

            val rebuilt = ledgerViewModel(savedState).uiState.first { !it.loading }

            assertEquals("12,", rebuilt.typedAtLeast)
            assertEquals(Money(1_200, Money.CAD), rebuilt.filter.atLeast)
        }

    @Test
    fun `the ledger narrows to notes containing what was typed, whatever the case and accents`() =
        runTest {
            insert(amountMinor = 1_000, day = TODAY, note = "Cadeau de fête pour Élise")
            insert(amountMinor = 2_000, day = TODAY, note = "Épicerie de la semaine")
            insert(amountMinor = 4_000, day = TODAY)

            viewModel.filters.onNoteTyped("  elise ")
            val state = viewModel.uiState.first { it.filter.note != null }

            assertEquals("elise", state.filter.note)
            assertEquals(Money(-1_000, Money.CAD), state.headline.balance)
        }

    @Test
    fun `a blank note filter narrows nothing`() =
        runTest {
            insert(amountMinor = 4_000, day = TODAY)

            viewModel.filters.onNoteTyped("   ")
            val state = viewModel.uiState.first { it.typedNote == "   " }

            assertFalse(state.filter.isActive)
            assertEquals(1, state.shownCount)
        }

    @Test
    fun `the header counts every filter beside the dates`() =
        runTest {
            val visa = account("Visa")
            insert(amountMinor = 4_000, day = TODAY, accountId = visa, note = "cadeau")

            viewModel.filters.onAccountSelected(visa)
            viewModel.filters.onAtLeastTyped("10")
            viewModel.filters.onAtMostTyped("100")
            viewModel.filters.onNoteTyped("cadeau")
            viewModel.filters.onFromSelected(LAST_MONTH)
            val state = viewModel.uiState.first { it.filter.note != null && it.filter.from != null }

            assertEquals(3, state.filter.countBesideDates)
        }
}
