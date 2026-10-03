// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.feature.ledger

import androidx.lifecycle.SavedStateHandle
import io.github.jaytech64.depenses.core.data.AccountRepository
import io.github.jaytech64.depenses.core.data.CategoryRepository
import io.github.jaytech64.depenses.core.data.MerchantRepository
import io.github.jaytech64.depenses.core.data.TemplateRepository
import io.github.jaytech64.depenses.core.data.TransactionEditor
import io.github.jaytech64.depenses.core.data.TransactionRepository
import io.github.jaytech64.depenses.core.database.DepensesDatabase
import io.github.jaytech64.depenses.core.model.AccountType
import io.github.jaytech64.depenses.core.model.Money
import io.github.jaytech64.depenses.core.model.TemplateContent
import io.github.jaytech64.depenses.core.model.TransactionKind
import io.github.jaytech64.depenses.core.testing.inMemoryDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
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
import java.time.ZoneId
import javax.inject.Provider

/** Templates in the transaction form, against a real database. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ExpenseFormTemplateTest {
    private lateinit var database: DepensesDatabase
    private lateinit var transactions: TransactionRepository
    private lateinit var accounts: AccountRepository
    private lateinit var categories: CategoryRepository
    private lateinit var templates: TemplateRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        database = inMemoryDatabase()
        accounts = AccountRepository(database.accountDao())
        categories = CategoryRepository(database.categoryDao(), database.merchantDao(), database.accountDao())
        transactions =
            TransactionRepository(
                database,
                database.transactionDao(),
                MerchantRepository(database.merchantDao()),
                categories,
                Provider { ZONE },
            )
        templates = database.templateRepository()
    }

    @After
    fun tearDown() {
        database.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `a new template needs a name and nothing else`() =
        runTest {
            val form = open(template = NEW_TEMPLATE_ID)
            assertFalse(form.uiState.value.canSave)

            form.template.onNameChange("Épicerie")

            assertTrue(form.uiState.value.canSave)
        }

    @Test
    fun `an amount half typed keeps a template from being saved`() =
        runTest {
            val form = open(template = NEW_TEMPLATE_ID)
            form.template.onNameChange("Épicerie")

            form.onAmountChange("abc")

            assertFalse(form.uiState.value.canSave)
        }

    @Test
    fun `saving a new template writes the template and no transaction`() =
        runTest {
            val cash = accounts.add("Comptant", AccountType.CASH, last4 = null)
            val restaurants = categories.named("Restaurants")
            val form = open(template = NEW_TEMPLATE_ID)
            form.template.onNameChange("Café matin")
            form.onAmountChange("4,75")
            form.onMerchantChange("Zoo Cafe")
            form.onAccountChange(AccountSlot.SOURCE, cash)
            form.category.onPick(restaurants)

            form.save()
            advanceUntilIdle()

            assertTrue(form.uiState.value.done)
            val saved = templates.all().first().single()
            assertEquals("Café matin", saved.name)
            assertEquals(Money(475, Money.CAD), saved.amount)
            assertEquals("Zoo Cafe", saved.merchantName)
            assertEquals(cash, saved.accountId)
            assertEquals(restaurants, saved.categoryId)
            assertTrue(transactions.confirmed().first().isEmpty())
        }

    @Test
    fun `a template with no amount is saved with none`() =
        runTest {
            val form = open(template = NEW_TEMPLATE_ID)
            form.template.onNameChange("Épicerie")
            form.onMerchantChange("Metro Plus")

            form.save()
            advanceUntilIdle()

            assertNull(
                templates
                    .all()
                    .first()
                    .single()
                    .amount,
            )
        }

    @Test
    fun `a template opens as it was saved, and saving it changes that one`() =
        runTest {
            val id = templates.save(coffee)
            val form = open(template = id)
            assertEquals("Café matin", form.uiState.value.templateName)
            assertEquals("Zoo Cafe", form.uiState.value.merchantText)
            assertEquals(Money(475, Money.CAD), form.uiState.value.amount)

            form.onAmountChange("5,25")
            form.save()
            advanceUntilIdle()

            assertEquals(listOf(Money(525, Money.CAD)), templates.all().first().map { it.amount })
        }

    @Test
    fun `deleting a template from the form removes it`() =
        runTest {
            val form = open(template = templates.save(coffee))

            form.delete()
            advanceUntilIdle()

            assertTrue(form.uiState.value.done)
            assertEquals(0, templates.count().first())
        }

    @Test
    fun `a template that is gone closes the form`() =
        runTest {
            val form = open(template = 41)

            assertTrue(form.uiState.value.done)
        }

    private fun TestScope.open(template: Long? = null): ExpenseFormViewModel {
        val merchants = MerchantRepository(database.merchantDao())
        val form =
            ExpenseFormViewModel(
                savedStateHandle = SavedStateHandle(template?.let { mapOf(TEMPLATE_ID_ARG to it) } ?: emptyMap()),
                transactions = transactions,
                editor =
                    TransactionEditor(
                        database,
                        database.transactionDao(),
                        merchants,
                        categories,
                        Clock.systemUTC(),
                    ),
                merchants = merchants,
                accounts = accounts,
                categories = categories,
                categoryGroups = database.categoryGroupRepository(),
                recurring = database.recurringRepository(Clock.systemUTC(), ZONE),
                templates = templates,
                clock = Clock.systemUTC(),
                zoneId = Provider { ZONE },
            )
        advanceUntilIdle()
        return form
    }

    private val coffee =
        TemplateContent(
            name = "Café matin",
            kind = TransactionKind.EXPENSE,
            amount = Money(475, Money.CAD),
            merchantName = "Zoo Cafe",
            accountId = null,
            toAccountId = null,
            categoryId = null,
            note = null,
        )

    private companion object {
        val ZONE: ZoneId = ZoneId.of("America/Toronto")
    }
}
