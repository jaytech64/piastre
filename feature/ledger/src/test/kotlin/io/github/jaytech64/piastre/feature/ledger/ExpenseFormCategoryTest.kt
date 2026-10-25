// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.feature.ledger

import androidx.lifecycle.SavedStateHandle
import io.github.jaytech64.piastre.core.data.AccountRepository
import io.github.jaytech64.piastre.core.data.CategoryReach
import io.github.jaytech64.piastre.core.data.CategoryRepository
import io.github.jaytech64.piastre.core.data.MerchantRepository
import io.github.jaytech64.piastre.core.data.TransactionEditor
import io.github.jaytech64.piastre.core.data.TransactionRepository
import io.github.jaytech64.piastre.core.database.PiastreDatabase
import io.github.jaytech64.piastre.core.model.AccountType
import io.github.jaytech64.piastre.core.model.Money
import io.github.jaytech64.piastre.core.model.Transaction
import io.github.jaytech64.piastre.core.model.TransactionKind
import io.github.jaytech64.piastre.core.testing.inMemoryDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Provider

/** The category field and the offer beside it, saved through the form into a real database. */
@RunWith(RobolectricTestRunner::class)
class ExpenseFormCategoryTest {
    private lateinit var database: PiastreDatabase
    private lateinit var categories: CategoryRepository
    private lateinit var transactions: TransactionRepository
    private lateinit var accounts: AccountRepository
    private lateinit var viewModel: ExpenseFormViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        database = inMemoryDatabase()
        categories = CategoryRepository(database.categoryDao(), database.merchantDao(), database.accountDao())
        accounts = AccountRepository(database.accountDao())
        transactions =
            TransactionRepository(
                database,
                database.transactionDao(),
                MerchantRepository(database.merchantDao()),
                categories,
                Provider { ZONE },
            )
    }

    @After
    fun tearDown() {
        database.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `a category picked on the form is saved on the purchase`() =
        runTest {
            val groceries = categories.named("Épicerie")!!
            open()

            viewModel.onAmountChange("40,00")
            viewModel.onMerchantChange("Metro Plus")
            viewModel.category.onPick(groceries)
            save()

            assertEquals(groceries, only("Metro Plus").categoryId)
        }

    @Test
    fun `nothing is offered until a category is chosen`() =
        runTest {
            addPurchase("Metro Plus")
            open()

            viewModel.onMerchantChange("Metro Plus")

            assertNull(viewModel.category.offer.value)
        }

    @Test
    fun `the offer counts the merchant's other purchases with no category, and ticking it reaches them`() =
        runTest {
            val groceries = categories.named("Épicerie")!!
            val filed = addPurchase("Metro Plus", categories.named("Maison"))
            val blank = addPurchase("Metro Plus")
            addPurchase("Metro Plus")
            open()

            viewModel.onAmountChange("40,00")
            viewModel.onMerchantChange("metro plus")
            viewModel.category.onPick(groceries)
            val offer = viewModel.category.offer.first { it?.othersCount == 2 }!!
            assertEquals(PlaceName.AtMerchant("Metro Plus"), offer.place)
            assertFalse(offer.others)
            assertFalse(offer.fromNowOn)

            viewModel.category.onOthersChange(true)
            viewModel.category.offer.first { it?.others == true }
            save()

            assertEquals(groceries, transactions.byId(blank)?.categoryId)
            assertEquals(categories.named("Maison"), transactions.byId(filed)?.categoryId)
        }

    @Test
    fun `from now on, the next entry at that merchant arrives with the category filled in`() =
        runTest {
            val groceries = categories.named("Épicerie")!!
            open()
            viewModel.onAmountChange("40,00")
            viewModel.onMerchantChange("Metro Plus")
            viewModel.category.onPick(groceries)
            viewModel.category.offer.first { it != null }
            viewModel.category.onFromNowOnChange(true)
            viewModel.category.offer.first { it?.fromNowOn == true }
            save()

            open()
            viewModel.knownMerchants.first { it.isNotEmpty() }
            viewModel.onMerchantChange("Metro Plus")

            assertEquals(groceries, viewModel.uiState.value.categoryId)
            assertTrue(
                viewModel.category.offer
                    .first { it?.place == PlaceName.AtMerchant("Metro Plus") }!!
                    .fromNowOn,
            )
        }

    @Test
    fun `an expense at a merchant arrives with no category when only its income gives one`() =
        runTest {
            addPurchase("Horizon Santé", categories.named("Pharmacie"))
            val pay = categories.named("Paye")!!
            addPurchase("Horizon Santé", pay, CategoryReach(others = false, fromNowOn = true), TransactionKind.INCOME)
            open()
            viewModel.knownMerchants.first { it.isNotEmpty() }

            viewModel.onMerchantChange("Horizon Santé")

            assertNull(viewModel.uiState.value.categoryId)
        }

    @Test
    fun `a category you chose is not replaced by the merchant's`() =
        runTest {
            val groceries = categories.named("Épicerie")!!
            addPurchase("Metro Plus", groceries, CategoryReach(others = false, fromNowOn = true))
            open()
            viewModel.knownMerchants.first { it.isNotEmpty() }

            viewModel.category.onPick(null)
            viewModel.onMerchantChange("Metro Plus")

            assertNull(viewModel.uiState.value.categoryId)
        }

    @Test
    fun `editing a purchase whose merchant gives its category, unticking it stops the merchant giving it`() =
        runTest {
            val groceries = categories.named("Épicerie")!!
            val id = addPurchase("Metro Plus", groceries, CategoryReach(others = false, fromNowOn = true))
            open(editing = id)
            viewModel.uiState.first { !it.loading }

            viewModel.category.offer.first { it?.fromNowOn == true }
            viewModel.category.onFromNowOnChange(false)
            viewModel.category.offer.first { it?.fromNowOn == false }
            save()

            assertNull(
                database
                    .merchantDao()
                    .allOnce()
                    .single()
                    .expenseCategoryId,
            )
            assertEquals(groceries, transactions.byId(id)?.categoryId)
        }

    @Test
    fun `a merchant giving another category says which one ticking would replace`() =
        runTest {
            addPurchase("Metro Plus", categories.named("Restaurants"), CategoryReach(others = false, fromNowOn = true))
            val groceries = categories.named("Épicerie")!!
            open()
            viewModel.knownMerchants.first { it.isNotEmpty() }
            viewModel.onMerchantChange("Metro Plus")

            viewModel.category.onPick(groceries)

            assertEquals(
                "Restaurants",
                viewModel.category.offer
                    .first { it?.replacing != null }!!
                    .replacing,
            )
        }

    @Test
    fun `a merchant typed for the first time can give its category from now on`() =
        runTest {
            val groceries = categories.named("Épicerie")!!
            open()
            viewModel.onAmountChange("12,50")
            viewModel.onMerchantChange("Fruiterie du marché")
            viewModel.category.onPick(groceries)
            assertEquals(
                0,
                viewModel.category.offer
                    .first { it != null }!!
                    .othersCount,
            )

            viewModel.category.onFromNowOnChange(true)
            viewModel.category.offer.first { it?.fromNowOn == true }
            save()

            assertEquals(
                groceries,
                database
                    .merchantDao()
                    .allOnce()
                    .single()
                    .expenseCategoryId,
            )
        }

    @Test
    fun `a transfer's offer is about the account it goes into`() =
        runTest {
            val mortgage = categories.named("Hypothèque")!!
            val debit = accounts.add("Débit", AccountType.BANK, last4 = "")
            val loan = accounts.add("Prêt hypothécaire", AccountType.BANK, last4 = "")
            val march = addTransfer(debit, loan)
            open()
            viewModel.knownAccounts.first { it.size == 2 }

            viewModel.onKindChange(TransactionKind.TRANSFER)
            viewModel.onAmountChange("1 104,37")
            viewModel.onAccountChange(AccountSlot.SOURCE, debit)
            viewModel.onAccountChange(AccountSlot.DESTINATION, loan)
            viewModel.category.onPick(mortgage)
            val offer = viewModel.category.offer.first { it?.othersCount == 1 }!!
            viewModel.category.onOthersChange(true)
            viewModel.category.onFromNowOnChange(true)
            viewModel.category.offer.first { it?.others == true && it.fromNowOn }
            save()

            assertEquals(loan, (offer.place as PlaceName.IntoAccount).account.id)
            assertEquals(mortgage, transactions.byId(march)?.categoryId)
            assertEquals(
                mortgage,
                accounts
                    .all(LocalDate.now())
                    .first()
                    .single { it.id == loan }
                    .categoryId,
            )
        }

    @Test
    fun `a category added from the form is picked`() =
        runTest {
            open()

            viewModel.category.add("Hypothèque", null, null)

            val picked = viewModel.uiState.first { it.categoryId != null }.categoryId
            assertEquals(categories.named("hypotheque"), picked)
            assertTrue(viewModel.uiState.value.categoryChosen)
        }

    @Test
    fun `a category added from the form goes into the group chosen with it`() =
        runTest {
            val home = database.categoryGroupRepository().named("Maison")!!
            open()

            viewModel.category.add("Électricité", home, null)

            val picked = viewModel.uiState.first { it.categoryId != null }.categoryId
            assertEquals(
                listOf(picked),
                database
                    .categoryGroupRepository()
                    .categoriesIn(home)
                    .first()
                    .map { it.category.id },
            )
        }

    @Test
    fun `the top of the list is what you have been picking, and nothing you have not`() =
        runTest {
            val groceries = categories.named("Épicerie")!!
            val restaurants = categories.named("Restaurants")!!
            categories.named("Vêtements")
            addPurchase("Metro Plus", groceries)
            addPurchase("IGA", groceries)
            addPurchase("Zoo Cafe", restaurants)
            open()

            assertEquals(
                listOf("Épicerie", "Restaurants"),
                viewModel.category.recent
                    .first { it.isNotEmpty() }
                    .map { it.name },
            )
        }

    /** Opens the form the way the screen does, with every list it draws collected. */
    private fun TestScope.open(editing: Long? = null) {
        viewModel =
            ExpenseFormViewModel(
                savedStateHandle = SavedStateHandle(editing?.let { mapOf(EXPENSE_ID_ARG to it) } ?: emptyMap()),
                transactions = transactions,
                editor =
                    TransactionEditor(
                        database,
                        database.transactionDao(),
                        MerchantRepository(database.merchantDao()),
                        categories,
                        Clock.systemUTC(),
                    ),
                merchants = MerchantRepository(database.merchantDao()),
                accounts = accounts,
                categories = categories,
                categoryGroups = database.categoryGroupRepository(),
                recurring = database.recurringRepository(Clock.systemUTC(), ZONE),
                templates = database.templateRepository(),
                clock = Clock.systemUTC(),
                zoneId = Provider { ZONE },
            )
        backgroundScope.launch { viewModel.category.offer.collect {} }
        backgroundScope.launch { viewModel.category.known.collect {} }
        backgroundScope.launch { viewModel.category.recent.collect {} }
    }

    private suspend fun save() {
        viewModel.save()
        viewModel.uiState.first { it.done }
    }

    private suspend fun only(merchant: String): Transaction =
        transactions.confirmed().first().single { it.displayMerchant == merchant }

    private suspend fun addPurchase(
        merchant: String,
        categoryId: Long? = null,
        reach: CategoryReach? = null,
        kind: TransactionKind = TransactionKind.EXPENSE,
    ): Long =
        transactions.addManual(
            amount = Money(4_000, Money.CAD),
            merchantName = merchant,
            occurredAt = Instant.parse("2026-09-24T11:00:00Z"),
            note = null,
            kind = kind,
            accountId = null,
            toAccountId = null,
            categoryId = categoryId,
            reach = reach,
        )

    private suspend fun addTransfer(
        from: Long,
        to: Long,
    ): Long =
        transactions.addManual(
            amount = Money(110_437, Money.CAD),
            merchantName = null,
            occurredAt = Instant.parse("2026-09-01T11:00:00Z"),
            note = null,
            kind = TransactionKind.TRANSFER,
            accountId = from,
            toAccountId = to,
            categoryId = null,
            reach = null,
        )

    private companion object {
        val ZONE: ZoneId = ZoneId.of("America/Toronto")
    }
}
