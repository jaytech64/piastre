// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.feature.ledger

import io.github.jaytech64.depenses.core.database.TransactionPartEntity
import io.github.jaytech64.depenses.core.model.Category
import io.github.jaytech64.depenses.core.model.Money
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A transaction divided among categories, as the ledger shows and counts it. */
@RunWith(RobolectricTestRunner::class)
class LedgerSplitTest : LedgerTest() {
    @Test
    fun `a split is one row counted whole while no category narrows the ledger`() =
        runTest {
            val costco = insert(amountMinor = 18_742, day = TODAY)
            split(costco, 12_000L to category("Épicerie"), 6_742L to category("Maison"))

            val state =
                viewModel.uiState.first {
                    it.days.isNotEmpty() &&
                        it.days
                            .single()
                            .transactions
                            .single()
                            .isSplit
                }

            assertEquals(Money(-18_742, Money.CAD), state.headline.balance)
            assertEquals(Money(-18_742, Money.CAD), state.days.single().total)
            assertEquals(emptyMap<Long, Money>(), state.wholes)
        }

    @Test
    fun `narrowed to a category, a split counts for its part there and says what it is a share of`() =
        runTest {
            val groceries = category("Épicerie")
            val costco = insert(amountMinor = 18_742, day = TODAY)
            split(costco, 12_000L to groceries, 6_742L to category("Maison"))
            insert(amountMinor = 3_000, day = TODAY, categoryId = groceries, merchantRaw = "IGA")

            viewModel.filters.onCategorySelected(CategoryChoice.Named(Category(groceries, "Épicerie")))
            val state = viewModel.uiState.first { it.filter.category != null && it.wholes.isNotEmpty() }

            assertEquals(Money(-15_000, Money.CAD), state.headline.balance)
            assertEquals(Money(-15_000, Money.CAD), state.days.single().total)
            assertEquals(
                setOf(12_000L, 3_000L),
                state.days
                    .single()
                    .transactions
                    .mapTo(mutableSetOf()) { it.amount.minor },
            )
            assertEquals(mapOf(costco to Money(18_742, Money.CAD)), state.wholes)
        }

    @Test
    fun `no category finds the part of a split nobody has labelled`() =
        runTest {
            val costco = insert(amountMinor = 18_742, day = TODAY)
            split(costco, 12_000L to category("Épicerie"), 6_742L to null)

            viewModel.filters.onCategorySelected(CategoryChoice.Uncategorised)
            val state = viewModel.uiState.first { it.filter.category != null && it.wholes.isNotEmpty() }

            assertEquals(Money(-6_742, Money.CAD), state.headline.balance)
        }

    @Test
    fun `a split whose parts are all under the category is shown whole, with nothing to add`() =
        runTest {
            val groceries = category("Épicerie")
            val costco = insert(amountMinor = 18_742, day = TODAY)
            split(costco, 12_000L to groceries, 6_742L to groceries)

            viewModel.filters.onCategorySelected(CategoryChoice.Named(Category(groceries, "Épicerie")))
            val state = viewModel.uiState.first { it.filter.category != null && it.days.isNotEmpty() }

            assertEquals(Money(-18_742, Money.CAD), state.headline.balance)
            assertEquals(emptyMap<Long, Money>(), state.wholes)
        }

    @Test
    fun `an amount filter compares the share the row shows`() =
        runTest {
            val groceries = category("Épicerie")
            val costco = insert(amountMinor = 18_742, day = TODAY)
            split(costco, 12_000L to groceries, 6_742L to category("Maison"))

            viewModel.filters.onCategorySelected(CategoryChoice.Named(Category(groceries, "Épicerie")))
            viewModel.filters.onAtLeastTyped("150")
            val state = viewModel.uiState.first { it.filter.category != null && it.filter.atLeast != null }

            assertEquals(0, state.shownCount)
        }

    @Test
    fun `the filter page counts a split under each category it touches`() =
        runTest {
            val groceries = category("Épicerie")
            val home = category("Maison")
            val costco = insert(amountMinor = 18_742, day = TODAY)
            split(costco, 12_000L to groceries, 5_000L to home, 1_742L to null)
            insert(amountMinor = 3_000, day = TODAY, categoryId = groceries, merchantRaw = "IGA")

            val state = viewModel.uiState.first { it.categories.size == 3 }

            val counts =
                state.categories.associate { option ->
                    ((option.value as? CategoryChoice.Named)?.category?.name) to option.count
                }
            assertEquals(mapOf(null to 1, "Épicerie" to 2, "Maison" to 1), counts)
        }

    @Test
    fun `categorising a selection leaves a split in it as it is, and the sheet counts it`() =
        runTest {
            val groceries = category("Épicerie")
            val internet = category("Internet")
            val costco = insert(amountMinor = 18_742, day = TODAY)
            split(costco, 12_000L to groceries, 6_742L to null)
            val plain = insert(amountMinor = 3_000, day = TODAY, merchantRaw = "VIDEOTRON")
            viewModel.uiState.first {
                it.days.isNotEmpty() && it.days
                    .single()
                    .transactions.size == 2
            }

            viewModel.selection.all()
            val selecting = viewModel.uiState.first { it.selected.size == 2 }
            assertEquals(1, selecting.selectedSplitCount)

            viewModel.selection.categorise(internet)
            viewModel.uiState.first { !it.isSelecting }

            val repository = transactionRepository()
            assertEquals(internet, repository.byId(plain)?.categoryId)
            assertNull(repository.byId(costco)?.categoryId)
            assertEquals(listOf(groceries, null), repository.byId(costco)?.parts?.map { it.categoryId })
        }

    private suspend fun split(
        transactionId: Long,
        vararg parts: Pair<Long, Long?>,
    ) = database.transactionPartDao().replace(
        transactionId,
        parts.map { (amountMinor, categoryId) ->
            TransactionPartEntity(
                transactionId = transactionId,
                position = 0,
                amountMinor = amountMinor,
                categoryId = categoryId,
                note = null,
            )
        },
    )
}
