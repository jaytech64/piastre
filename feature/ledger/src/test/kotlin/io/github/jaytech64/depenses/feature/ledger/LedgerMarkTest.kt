// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.feature.ledger

import io.github.jaytech64.depenses.core.database.TransactionPartEntity
import io.github.jaytech64.depenses.core.designsystem.CATEGORY_ICONS
import io.github.jaytech64.depenses.core.designsystem.Mark
import io.github.jaytech64.depenses.core.model.TransactionKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** What stands in the circle a ledger row starts with. */
@RunWith(RobolectricTestRunner::class)
class LedgerMarkTest : LedgerTest() {
    @Test
    fun `a row shows the icon its category carries`() =
        runTest {
            insert(amountMinor = 4_000, day = TODAY, categoryId = category("Épicerie", icon = "shopping_cart"))

            assertEquals(icon("shopping_cart"), onlyMark())
        }

    @Test
    fun `a category with no icon keeps its initials, and so does one whose icon this build does not know`() =
        runTest {
            val fine = category("Épicerie fine")
            insert(amountMinor = 4_000, day = TODAY, categoryId = fine)
            assertEquals(Mark.Letters("ÉF"), onlyMark())

            categoryGroupRepository().setIcon(fine, "an_icon_from_later")
            val later = viewModel.uiState.first { it.rows().singleOrNull()?.categoryIcon != null }
            assertEquals(Mark.Letters("ÉF"), later.marks().single())
        }

    @Test
    fun `changing a category's icon changes the rows it already has`() =
        runTest {
            val groceries = category("Épicerie", icon = "shopping_cart")
            insert(amountMinor = 4_000, day = TODAY, categoryId = groceries)

            categoryGroupRepository().setIcon(groceries, "storefront")

            assertEquals(
                icon("storefront"),
                viewModel.uiState
                    .first { it.marks() == listOf(icon("storefront")) }
                    .marks()
                    .single(),
            )
        }

    @Test
    fun `a row with no category shows its merchant's initials, and a transfer its arrow`() =
        runTest {
            insert(amountMinor = 4_000, day = TODAY, merchantRaw = "Metro Plus")
            assertEquals(Mark.Letters("MP"), onlyMark())

            assertEquals(Mark.Letters(ARROW), onlyRow().copy(kind = TransactionKind.TRANSFER).mark())
        }

    @Test
    fun `a split in two shows both categories, the first part's first, with a letter for one with no icon`() =
        runTest {
            val costco = insert(amountMinor = 18_742, day = TODAY)
            split(costco, category("Épicerie", icon = "shopping_cart"), category("Maison"))

            assertEquals(Mark.Halves(icon("shopping_cart"), Mark.Letters("M")), onlyMark())
        }

    @Test
    fun `a half nobody has given a category shows nothing`() =
        runTest {
            val costco = insert(amountMinor = 18_742, day = TODAY)
            split(costco, null, category("Maison", icon = "home"))

            assertEquals(Mark.Halves(null, icon("home")), onlyMark())
        }

    @Test
    fun `a split in two under one category shows that category whole`() =
        runTest {
            val groceries = category("Épicerie", icon = "shopping_cart")
            val costco = insert(amountMinor = 18_742, day = TODAY)
            split(costco, groceries, groceries)

            assertEquals(icon("shopping_cart"), onlyMark())
        }

    @Test
    fun `a split in three, or in two with no category at all, only shows that it is a split`() =
        runTest {
            val costco = insert(amountMinor = 18_742, day = TODAY)
            split(costco, category("Épicerie", icon = "shopping_cart"), category("Maison", icon = "home"), null)
            assertEquals(Mark.Split, onlyMark())

            split(costco, null, null)
            assertEquals(
                Mark.Split,
                viewModel.uiState
                    .first {
                        it
                            .rows()
                            .single()
                            .parts.size == 2
                    }.marks()
                    .single(),
            )
        }

    private fun icon(name: String) = Mark.Icon(CATEGORY_ICONS.getValue(name))

    private suspend fun category(
        name: String,
        icon: String?,
    ): Long = categoryRepository().named(name, icon = icon)!!

    private suspend fun onlyRow() =
        viewModel.uiState
            .first { it.days.isNotEmpty() }
            .rows()
            .single()

    private suspend fun onlyMark() = onlyRow().mark()

    private fun LedgerUiState.rows() = days.flatMap { it.transactions }

    private fun LedgerUiState.marks() = rows().map { it.mark() }

    /** Divides [transactionId] evenly among [categoryIds], in that order. */
    private suspend fun split(
        transactionId: Long,
        vararg categoryIds: Long?,
    ) = database.transactionPartDao().replace(
        transactionId,
        categoryIds.map {
            TransactionPartEntity(
                transactionId = 0,
                position = 0,
                amountMinor = 1_000,
                categoryId = it,
                note = null,
                toAccountId = null,
            )
        },
    )
}
