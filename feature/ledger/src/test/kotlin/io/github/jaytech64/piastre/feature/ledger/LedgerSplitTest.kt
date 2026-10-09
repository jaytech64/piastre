// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.feature.ledger

import io.github.jaytech64.piastre.core.database.TransactionPartEntity
import io.github.jaytech64.piastre.core.model.AccountType
import io.github.jaytech64.piastre.core.model.Category
import io.github.jaytech64.piastre.core.model.Money
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

    @Test
    fun `the account a part goes into shows the split at that share, as a transfer that reached it`() =
        runTest {
            val chequing = account("Chèques")
            val mortgage = account("Hypothèque", AccountType.BANK)
            val payment = mortgagePayment(chequing, mortgage)

            viewModel.filters.onAccountSelected(mortgage)
            val state = viewModel.uiState.first { it.filter.account?.id == mortgage && it.days.isNotEmpty() }

            assertEquals(
                listOf(50_000L),
                state.days
                    .single()
                    .transactions
                    .map { it.amount.minor },
            )
            assertEquals(mapOf(payment to Money(120_000, Money.CAD)), state.wholes)
            assertEquals(Money(50_000, Money.CAD), state.headline.transfers)
            assertEquals(Money(0, Money.CAD), state.headline.expenses)
            assertEquals(Money(50_000, Money.CAD), state.days.single().total)
        }

    @Test
    fun `the account a split was paid from shows it whole, its share moved on apart from what was spent`() =
        runTest {
            val chequing = account("Chèques")
            val mortgage = account("Hypothèque", AccountType.BANK)
            mortgagePayment(chequing, mortgage)

            viewModel.filters.onAccountSelected(chequing)
            val state = viewModel.uiState.first { it.filter.account?.id == chequing && it.days.isNotEmpty() }

            assertEquals(Money(70_000, Money.CAD), state.headline.expenses)
            assertEquals(Money(-50_000, Money.CAD), state.headline.transfers)
            assertEquals(Money(-120_000, Money.CAD), state.headline.balance)
            assertEquals(emptyMap<Long, Money>(), state.wholes)
        }

    @Test
    fun `each account's ledger adds up to what its balance moved by`() =
        runTest {
            val chequing = account("Chèques")
            val mortgage = account("Hypothèque", AccountType.BANK)
            mortgagePayment(chequing, mortgage)

            for (account in listOf(chequing, mortgage)) {
                viewModel.filters.onAccountSelected(account)
                val state = viewModel.uiState.first { it.filter.account?.id == account && it.days.isNotEmpty() }

                assertEquals(state.accounts.single { it.id == account }.balance, state.headline.balance)
            }
        }

    @Test
    fun `a share moved between your own pockets is not spent, and the row still shows the whole payment`() =
        runTest {
            val card = account("Visa")
            val cash = account("Comptant", AccountType.CASH)
            val costco = insert(amountMinor = 18_742, day = TODAY, accountId = card)
            split(costco, 14_742L to null, intoAccount = 4_000L to cash)

            val state = viewModel.uiState.first { it.days.isNotEmpty() }

            assertEquals(Money(14_742, Money.CAD), state.headline.expenses)
            assertEquals(Money(-14_742, Money.CAD), state.days.single().total)
            assertEquals(
                18_742L,
                state.days
                    .single()
                    .transactions
                    .single()
                    .amount.minor,
            )
        }

    @Test
    fun `narrowed to the account and a category the share is not under, the split is left out`() =
        runTest {
            val chequing = account("Chèques")
            val mortgage = account("Hypothèque", AccountType.BANK)
            val interest = category("Intérêts")
            val payment = insert(amountMinor = 120_000, day = TODAY, accountId = chequing)
            split(payment, 70_000L to interest, intoAccount = 50_000L to mortgage)

            viewModel.filters.onAccountSelected(mortgage)
            viewModel.filters.onCategorySelected(CategoryChoice.Named(Category(interest, "Intérêts")))
            val state = viewModel.uiState.first { it.filter.account != null && it.filter.category != null }

            assertEquals(emptyList<LedgerDay>(), state.days)
        }

    /** 1 200,00 from [chequing]: 700,00 of interest spent, 500,00 of capital into [mortgage]. */
    private suspend fun mortgagePayment(
        chequing: Long,
        mortgage: Long,
    ): Long {
        val payment = insert(amountMinor = 120_000, day = TODAY, accountId = chequing)
        split(payment, 70_000L to null, intoAccount = 50_000L to mortgage)
        return payment
    }

    private suspend fun split(
        transactionId: Long,
        vararg parts: Pair<Long, Long?>,
        intoAccount: Pair<Long, Long>? = null,
    ) = database.transactionPartDao().replace(
        transactionId,
        parts.map { (amountMinor, categoryId) -> part(amountMinor, categoryId) } +
            listOfNotNull(intoAccount?.let { (amountMinor, accountId) -> part(amountMinor, toAccountId = accountId) }),
    )

    private fun part(
        amountMinor: Long,
        categoryId: Long? = null,
        toAccountId: Long? = null,
    ) = TransactionPartEntity(
        transactionId = 0,
        position = 0,
        amountMinor = amountMinor,
        categoryId = categoryId,
        note = null,
        toAccountId = toAccountId,
    )
}
