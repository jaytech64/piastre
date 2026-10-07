// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.feature.ledger

import androidx.lifecycle.SavedStateHandle
import io.github.jaytech64.depenses.core.data.AccountRepository
import io.github.jaytech64.depenses.core.data.CategoryRepository
import io.github.jaytech64.depenses.core.data.MerchantRepository
import io.github.jaytech64.depenses.core.data.TransactionEditor
import io.github.jaytech64.depenses.core.data.TransactionRepository
import io.github.jaytech64.depenses.core.database.DepensesDatabase
import io.github.jaytech64.depenses.core.model.Money
import io.github.jaytech64.depenses.core.model.Rhythm
import io.github.jaytech64.depenses.core.model.Transaction
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
import java.time.Clock
import java.time.ZoneId
import javax.inject.Provider

/** An amount divided among categories on the form, saved through it into a real database. */
@RunWith(RobolectricTestRunner::class)
class ExpenseFormSplitTest {
    private lateinit var database: DepensesDatabase
    private lateinit var categories: CategoryRepository
    private lateinit var transactions: TransactionRepository
    private lateinit var viewModel: ExpenseFormViewModel

    private val state get() = viewModel.uiState.value

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        database = inMemoryDatabase()
        categories = CategoryRepository(database.categoryDao(), database.merchantDao(), database.accountDao())
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
    fun `splitting moves the chosen category onto the first part, with its amount still to type`() =
        runTest {
            val groceries = categories.named("Épicerie")!!
            open()
            viewModel.onAmountChange("187,42")
            viewModel.category.onPick(groceries)

            viewModel.split.start()

            assertEquals(listOf(groceries), state.parts.map { it.categoryId })
            assertNull(state.categoryId)
            assertEquals(Money(18_742, Money.CAD), state.leftToSplit)
            assertFalse(state.canSave)
        }

    @Test
    fun `a new part arrives holding what is left, and the split can then be saved`() =
        runTest {
            val groceries = categories.named("Épicerie")!!
            val home = categories.named("Maison")!!
            open()
            viewModel.onAmountChange("187,42")
            viewModel.onMerchantChange("Costco")
            viewModel.category.onPick(groceries)
            viewModel.split.start()
            viewModel.split.onAmountChange(0, "120")

            viewModel.split.add()
            viewModel.split.onCategoryChange(1, home)
            viewModel.split.onNoteChange(1, "Ampoules")

            assertEquals(Money(6_742, Money.CAD), state.parts.last().amount)
            assertEquals(Money(0, Money.CAD), state.leftToSplit)
            assertTrue(state.canSave)
            save()
            val saved = only()
            assertEquals(18_742L, saved.amount.minor)
            assertNull(saved.categoryId)
            assertEquals(
                listOf(Triple(groceries, 12_000L, null), Triple(home, 6_742L, "Ampoules")),
                saved.parts.map { Triple(it.categoryId, it.amount.minor, it.note) },
            )
        }

    @Test
    fun `save waits while something is left, and while the parts exceed the amount`() =
        runTest {
            open()
            viewModel.onAmountChange("100")
            viewModel.split.start()
            viewModel.split.onAmountChange(0, "60")
            viewModel.split.add()

            viewModel.split.onAmountChange(1, "30")
            assertEquals(Money(1_000, Money.CAD), state.leftToSplit)
            assertFalse(state.canSave)

            viewModel.split.onAmountChange(1, "50")
            assertEquals(Money(-1_000, Money.CAD), state.leftToSplit)
            assertFalse(state.canSave)

            viewModel.split.onAmountChange(1, "40")
            assertTrue(state.canSave)
        }

    @Test
    fun `changing the amount of a split leaves something to divide again`() =
        runTest {
            open()
            viewModel.onAmountChange("100")
            viewModel.split.start()
            viewModel.split.onAmountChange(0, "60")
            viewModel.split.add()
            assertTrue(state.canSave)

            viewModel.onAmountChange("110")

            assertEquals(Money(1_000, Money.CAD), state.leftToSplit)
            assertFalse(state.canSave)
        }

    @Test
    fun `taking a part away until one is left ends the split under that part's category`() =
        runTest {
            val home = categories.named("Maison")!!
            open()
            viewModel.onAmountChange("100")
            viewModel.split.start()
            viewModel.split.onAmountChange(0, "60")
            viewModel.split.add()
            viewModel.split.onCategoryChange(1, home)

            viewModel.split.remove(0)

            assertFalse(state.isSplit)
            assertEquals(home, state.categoryId)
            assertTrue(state.canSave)
        }

    @Test
    fun `a split is not offered to repeat or to be kept as a template, and drops a rhythm already set`() =
        runTest {
            open()
            viewModel.onAmountChange("100")
            viewModel.repeat.onRhythmChange(Rhythm.MONTHLY)
            assertTrue(state.offersRepeat && state.offersSavingAsTemplate)

            viewModel.split.start()

            assertNull(state.rhythm)
            assertFalse(state.offersRepeat)
            assertFalse(state.offersSavingAsTemplate)
        }

    @Test
    fun `a split opens with its parts, and stopping it saves an ordinary transaction`() =
        runTest {
            val groceries = categories.named("Épicerie")!!
            open()
            viewModel.onAmountChange("100")
            viewModel.onMerchantChange("Costco")
            viewModel.category.onPick(groceries)
            viewModel.split.start()
            viewModel.split.onAmountChange(0, "60")
            viewModel.split.add()
            save()

            open(editing = only().id)
            viewModel.uiState.first { !it.loading }
            assertEquals(listOf(Money(6_000, Money.CAD), Money(4_000, Money.CAD)), state.parts.map { it.amount })

            viewModel.split.stop()
            save()

            val saved = only()
            assertFalse(saved.isSplit)
            assertEquals(groceries, saved.categoryId)
        }

    @Test
    fun `a split gives its merchant no category for next time`() =
        runTest {
            val groceries = categories.named("Épicerie")!!
            open()
            viewModel.onAmountChange("100")
            viewModel.onMerchantChange("Costco")
            viewModel.category.onPick(groceries)
            viewModel.category.onFromNowOnChange(true)
            viewModel.split.start()
            viewModel.split.onAmountChange(0, "60")
            viewModel.split.add()
            save()

            assertNull(
                database
                    .merchantDao()
                    .allOnce()
                    .single()
                    .categoryId,
            )
        }

    private fun open(editing: Long? = null) {
        val merchants = MerchantRepository(database.merchantDao())
        viewModel =
            ExpenseFormViewModel(
                savedStateHandle = SavedStateHandle(editing?.let { mapOf(EXPENSE_ID_ARG to it) } ?: emptyMap()),
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
                accounts = AccountRepository(database.accountDao()),
                categories = categories,
                categoryGroups = database.categoryGroupRepository(),
                recurring = database.recurringRepository(Clock.systemUTC(), ZONE),
                templates = database.templateRepository(),
                clock = Clock.systemUTC(),
                zoneId = Provider { ZONE },
            )
    }

    private suspend fun save() {
        viewModel.save()
        viewModel.uiState.first { it.done }
    }

    private suspend fun only(): Transaction = transactions.confirmed().first().single()

    private companion object {
        val ZONE: ZoneId = ZoneId.of("America/Toronto")
    }
}
