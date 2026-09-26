// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.feature.settings

import io.github.jaytech64.depenses.core.data.CategoryRepository
import io.github.jaytech64.depenses.core.database.DepensesDatabase
import io.github.jaytech64.depenses.core.testing.inMemoryDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The Categories section's rules, against a real database. */
@RunWith(RobolectricTestRunner::class)
class CategoriesViewModelTest {
    private lateinit var database: DepensesDatabase
    private lateinit var repository: CategoryRepository
    private lateinit var viewModel: CategoriesViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        database = inMemoryDatabase()
        repository = CategoryRepository(database.categoryDao(), database.merchantDao(), database.accountDao())
        viewModel = CategoriesViewModel(repository)
    }

    @After
    fun tearDown() {
        database.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `a category added here is listed`() =
        runTest {
            viewModel.edit(CategoryEdit.New)
            viewModel.save("Épicerie")

            assertEquals(
                "Épicerie",
                viewModel.categories
                    .first { it.isNotEmpty() }
                    .single()
                    .category.name,
            )
            assertEquals(null, viewModel.editing.first { it == null })
        }

    @Test
    fun `a rename onto a name already taken keeps the dialog open and says so`() =
        runTest {
            repository.named("Épicerie")
            repository.named("Restaurants")
            val groceries = viewModel.categories.first { it.size == 2 }.first { it.category.name == "Épicerie" }

            viewModel.edit(CategoryEdit.Existing(groceries))
            viewModel.save("restaurants")

            val edit = viewModel.editing.first { (it as? CategoryEdit.Existing)?.refused == true }
            assertTrue(edit is CategoryEdit.Existing)
            assertEquals(listOf("Épicerie", "Restaurants"), viewModel.categories.value.map { it.category.name })
        }

    @Test
    fun `a rename that goes through closes the dialog`() =
        runTest {
            repository.named("Epicerie")
            val groceries = viewModel.categories.first { it.isNotEmpty() }.single()

            viewModel.edit(CategoryEdit.Existing(groceries))
            viewModel.save("Épicerie")

            viewModel.editing.first { it == null }
            assertEquals(
                "Épicerie",
                viewModel.categories
                    .first { it.single().category.name != "Epicerie" }
                    .single()
                    .category.name,
            )
        }

    @Test
    fun `a deleted category leaves the list`() =
        runTest {
            repository.named("Épicerie")
            val groceries = viewModel.categories.first { it.isNotEmpty() }.single()

            viewModel.edit(CategoryEdit.ConfirmDelete(groceries))
            viewModel.delete(groceries)

            assertEquals(emptyList<Any>(), viewModel.categories.first { it.isEmpty() })
            assertEquals(null, viewModel.editing.value)
        }
}
