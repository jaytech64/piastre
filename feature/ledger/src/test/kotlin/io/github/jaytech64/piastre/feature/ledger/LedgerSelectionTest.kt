// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.feature.ledger

import androidx.lifecycle.SavedStateHandle
import io.github.jaytech64.piastre.core.model.Category
import io.github.jaytech64.piastre.core.model.Money
import io.github.jaytech64.piastre.core.model.TransactionKind
import io.github.jaytech64.piastre.core.model.TransactionStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Rows picked by a long press, and what giving them a category or deleting them does. */
@RunWith(RobolectricTestRunner::class)
class LedgerSelectionTest : LedgerTest() {
    @Test
    fun `a long press selects a row and a second one takes it back out`() =
        runTest {
            val bill = insert(amountMinor = 8_500, day = TODAY)
            settled()

            viewModel.selection.toggle(bill)
            assertEquals(setOf(bill), viewModel.uiState.first { it.isSelecting }.selected)

            viewModel.selection.toggle(bill)
            assertFalse(viewModel.uiState.first { !it.isSelecting }.isSelecting)
        }

    @Test
    fun `selecting all takes every row the filter shows and nothing still to come`() =
        runTest {
            val internet = category("Internet")
            val bill = insert(amountMinor = 8_500, day = TODAY, categoryId = internet)
            val earlier = insert(amountMinor = 8_500, day = YESTERDAY, categoryId = internet)
            insert(amountMinor = 6_000, day = TODAY)
            insert(amountMinor = 8_500, day = TOMORROW, categoryId = internet)
            viewModel.filters.onCategorySelected(CategoryChoice.Named(Category(internet, "Internet")))
            viewModel.uiState.first { it.filter.category != null }
            viewModel.selection.toggle(bill)
            assertFalse(viewModel.uiState.first { it.isSelecting }.allSelected)

            viewModel.selection.all()

            val state = viewModel.uiState.first { it.selected.size > 1 }
            assertEquals(setOf(bill, earlier), state.selected)
            assertTrue(state.allSelected)
        }

    @Test
    fun `a filter narrows the selection to the rows it still shows`() =
        runTest {
            val internet = category("Internet")
            val shown = insert(amountMinor = 8_500, day = TODAY, categoryId = internet)
            val hidden = insert(amountMinor = 6_000, day = TODAY)
            savedState["selectedRowIds"] = longArrayOf(shown, hidden)

            viewModel.filters.onCategorySelected(CategoryChoice.Named(Category(internet, "Internet")))

            assertEquals(setOf(shown), viewModel.uiState.first { it.filter.category != null }.selected)
        }

    @Test
    fun `the selection survives the screen being rebuilt`() =
        runTest {
            val bill = insert(amountMinor = 8_500, day = TODAY)
            settled()
            viewModel.selection.toggle(bill)
            viewModel.uiState.first { it.isSelecting }

            viewModel =
                ledgerViewModel(
                    SavedStateHandle(mapOf("selectedRowIds" to savedState.get<LongArray>("selectedRowIds"))),
                )

            assertEquals(setOf(bill), viewModel.uiState.first { it.isSelecting }.selected)
        }

    @Test
    fun `a row added while selecting stays out of the selection`() =
        runTest {
            val bill = insert(amountMinor = 8_500, day = TODAY)
            select(bill)

            insert(amountMinor = 6_000, day = TODAY, merchantRaw = "VIDEOTRON")
            val state = viewModel.uiState.first { it.days.sumOf { day -> day.transactions.size } == 2 }

            assertEquals(setOf(bill), state.selected)
        }

    @Test
    fun `a selected row edited while selecting stays selected and is counted as it now reads`() =
        runTest {
            val bill = insert(amountMinor = 8_500, day = TODAY)
            val other = insert(amountMinor = 6_000, day = TODAY, merchantRaw = "VIDEOTRON")
            select(bill, other)

            val dao = database.transactionDao()
            dao.update(dao.byId(bill)!!.copy(amountMinor = 9_000))
            val state = viewModel.uiState.first { it.selectionSummary?.expenses == Money(15_000, Money.CAD) }

            assertEquals(setOf(bill, other), state.selected)
        }

    @Test
    fun `categorising the selection gives the category to those rows alone and ends it`() =
        runTest {
            val phone = category("Cellulaire")
            val internet = category("Internet")
            val first = insert(amountMinor = 8_500, day = TODAY, categoryId = phone)
            val second = insert(amountMinor = 8_000, day = YESTERDAY, categoryId = phone)
            val other = insert(amountMinor = 6_000, day = TODAY, categoryId = phone)
            select(first, second)

            viewModel.selection.categorise(internet)
            val state = viewModel.uiState.first { !it.isSelecting && it.categoryOf(first) == internet }

            assertEquals(internet, state.categoryOf(second))
            assertEquals(phone, state.categoryOf(other))
        }

    @Test
    fun `a category added from the sheet is given to the selection`() =
        runTest {
            val bill = insert(amountMinor = 8_500, day = TODAY)
            select(bill)

            viewModel.selection.categoriseAsNew("Internet", null, null)
            val state = viewModel.uiState.first { it.categoryOf(bill) != null }

            assertEquals("Internet", state.row(bill).categoryName)
            assertFalse(state.isSelecting)
        }

    @Test
    fun `the sheet says how many selected rows each category already holds`() =
        runTest {
            val phone = category("Cellulaire")
            val internet = category("Internet")
            val groceries = category("Épicerie")
            val first = insert(amountMinor = 8_500, day = TODAY, categoryId = phone)
            val second = insert(amountMinor = 8_000, day = TODAY, categoryId = internet)
            val third = insert(amountMinor = 7_000, day = TODAY)
            insert(amountMinor = 4_000, day = TODAY, categoryId = groceries)
            select(first, second, third)

            val counts =
                viewModel.uiState
                    .first { it.selected.size == 3 }
                    .selectionCategories
                    .associate { it.category?.id to it.selectedCount }

            assertEquals(mapOf(null to 1, phone to 1, internet to 1, groceries to 0), counts)
        }

    @Test
    fun `no category comes first, then the categories used most lately`() =
        runTest {
            val rare = category("Assurance")
            val often = category("Épicerie")
            val unused = category("Voyage")
            insert(amountMinor = 4_000, day = TODAY, categoryId = often)
            insert(amountMinor = 4_100, day = YESTERDAY, categoryId = often)
            insert(amountMinor = 9_000, day = TODAY, categoryId = rare)
            val bill = insert(amountMinor = 8_500, day = TODAY)
            select(bill)

            val order =
                viewModel.uiState
                    .first { it.isSelecting }
                    .selectionCategories
                    .map { it.category?.id }

            assertEquals(listOf(null, often, rare, unused), order)
        }

    @Test
    fun `deleting the selection takes those rows out of the ledger and ends it`() =
        runTest {
            val first = insert(amountMinor = 8_500, day = TODAY)
            val second = insert(amountMinor = 8_000, day = TODAY)
            val kept = insert(amountMinor = 6_000, day = TODAY)
            select(first, second)

            viewModel.selection.delete()
            val state = viewModel.uiState.first { it.days.flatMap { day -> day.transactions }.size == 1 }

            assertEquals(
                kept,
                state.days
                    .single()
                    .transactions
                    .single()
                    .id,
            )
            assertFalse(state.isSelecting)
            assertEquals(TransactionStatus.DISMISSED, database.transactionDao().byId(first)?.status)
        }

    @Test
    fun `nothing selected offers nothing to categorise`() =
        runTest {
            insert(amountMinor = 8_500, day = TODAY)

            val state = settled()

            assertTrue(state.selectionCategories.isEmpty())
            assertNull(state.selected.firstOrNull())
        }

    @Test
    fun `a whole day selected adds up to what its header says`() =
        runTest {
            val pay = insert(amountMinor = 140_000, day = TODAY, kind = TransactionKind.INCOME)
            val groceries = insert(amountMinor = 12_025, day = TODAY)
            val coffee = insert(amountMinor = 4_525, day = TODAY)
            insert(amountMinor = 9_900, day = YESTERDAY)
            select(pay, groceries, coffee)

            val state = viewModel.uiState.first { it.selected.size == 3 }
            val summary = state.selectionSummary!!

            assertEquals(state.days.single { it.date == TODAY }.total, summary.balance)
            assertEquals(Money(140_000, Money.CAD), summary.income)
            assertEquals(Money(16_550, Money.CAD), summary.expenses)
            assertEquals(Money(123_450, Money.CAD), summary.balance)
        }

    @Test
    fun `a selected card payment is worth nothing to the whole ledger and moves the account it touches`() =
        runTest {
            val desrives = account("Desrives")
            val visa = account("Visa")
            val payment =
                insert(
                    amountMinor = 50_000,
                    day = TODAY,
                    kind = TransactionKind.TRANSFER,
                    accountId = desrives,
                    toAccountId = visa,
                )
            select(payment)

            assertEquals(
                Money(0, Money.CAD),
                viewModel.uiState
                    .first { it.isSelecting }
                    .selectionSummary!!
                    .balance,
            )

            viewModel.filters.onAccountSelected(desrives)
            val onDesrives = viewModel.uiState.first { it.filter.account != null }.selectionSummary!!

            assertEquals(Money(-50_000, Money.CAD), onDesrives.transfers)
            assertEquals(Money(-50_000, Money.CAD), onDesrives.balance)
        }

    @Test
    fun `a selected purchase in another currency is counted apart`() =
        runTest {
            val bill = insert(amountMinor = 8_500, day = TODAY)
            val order = insert(amountMinor = 745, day = TODAY, currency = "USD")
            select(bill, order)

            val summary = viewModel.uiState.first { it.selected.size == 2 }.selectionSummary!!

            assertEquals(Money(-8_500, Money.CAD), summary.balance)
            assertEquals(1, summary.otherCurrencyCount)
        }

    @Test
    fun `a row that leaves the screen leaves the total`() =
        runTest {
            val internet = category("Internet")
            val shown = insert(amountMinor = 8_500, day = TODAY, categoryId = internet)
            val hidden = insert(amountMinor = 6_000, day = TODAY)
            select(shown, hidden)

            viewModel.filters.onCategorySelected(CategoryChoice.Named(Category(internet, "Internet")))
            val summary = viewModel.uiState.first { it.filter.category != null }.selectionSummary!!

            assertEquals(Money(8_500, Money.CAD), summary.expenses)
        }

    @Test
    fun `nothing selected adds up to nothing`() =
        runTest {
            insert(amountMinor = 8_500, day = TODAY)

            assertNull(settled().selectionSummary)
        }

    private suspend fun select(vararg ids: Long) {
        settled()
        ids.forEach(viewModel.selection::toggle)
        viewModel.uiState.first { it.selected == ids.toSet() }
    }

    private fun LedgerUiState.row(id: Long) = days.flatMap { it.transactions }.single { it.id == id }

    private fun LedgerUiState.categoryOf(id: Long) = row(id).categoryId
}
