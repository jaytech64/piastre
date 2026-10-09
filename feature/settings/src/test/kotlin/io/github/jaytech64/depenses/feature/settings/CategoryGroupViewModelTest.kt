// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.feature.settings

import androidx.lifecycle.SavedStateHandle
import io.github.jaytech64.depenses.core.data.CategoryGroupRepository
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

/** A group's page, and the page of categories in none, against a real database. */
@RunWith(RobolectricTestRunner::class)
class CategoryGroupViewModelTest {
    private lateinit var database: DepensesDatabase
    private lateinit var repository: CategoryRepository
    private lateinit var groups: CategoryGroupRepository
    private lateinit var viewModel: CategoryGroupViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        database = inMemoryDatabase()
        repository = CategoryRepository(database.categoryDao(), database.merchantDao(), database.accountDao())
        groups = CategoryGroupRepository(database.categoryGroupDao(), database.categoryDao())
        viewModel = page(groupId = null)
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
            viewModel.save("Épicerie", null, null)

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
            viewModel.save("restaurants", null, null)

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
            viewModel.save("Épicerie", null, null)

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

    @Test
    fun `a category added with an icon carries it, and an edit changes it or takes it away`() =
        runTest {
            viewModel.edit(CategoryEdit.New)
            viewModel.save("Épicerie", null, "shopping_cart")
            val added = viewModel.categories.first { it.isNotEmpty() }.single()
            assertEquals("shopping_cart", added.category.icon)

            viewModel.edit(CategoryEdit.Existing(added))
            viewModel.save("Épicerie", null, "storefront")
            val changed = viewModel.categories.first { it.single().category.icon != "shopping_cart" }.single()
            assertEquals("storefront", changed.category.icon)

            viewModel.edit(CategoryEdit.Existing(changed))
            viewModel.save("Épicerie", null, null)
            assertEquals(
                null,
                viewModel.categories
                    .first { it.single().category.icon != "storefront" }
                    .single()
                    .category.icon,
            )
        }

    @Test
    fun `a category added on a group's page is in that group`() =
        runTest {
            val home = groups.named("Maison")!!
            val page = page(home)

            page.edit(CategoryEdit.New)
            page.save("Électricité", home, null)

            assertEquals(listOf("Électricité"), page.categories.first { it.isNotEmpty() }.map { it.category.name })
            assertEquals(emptyList<Any>(), groups.categoriesIn(null).first())
        }

    @Test
    fun `moving a category takes it off this page`() =
        runTest {
            val home = groups.named("Maison")!!
            val car = groups.named("Auto")!!
            repository.named("Assurance", home)
            val page = page(home)
            val insurance = page.categories.first { it.isNotEmpty() }.single()

            page.edit(CategoryEdit.Existing(insurance))
            page.save("Assurance", car, null)

            assertEquals(emptyList<Any>(), page.categories.first { it.isEmpty() })
            assertEquals(listOf("Assurance"), groups.categoriesIn(car).first().map { it.category.name })
        }

    @Test
    fun `a refused rename moves nothing`() =
        runTest {
            val home = groups.named("Maison")!!
            val car = groups.named("Auto")!!
            repository.named("Assurance", home)
            repository.named("Essence", car)
            val page = page(home)
            val insurance = page.categories.first { it.isNotEmpty() }.single()

            page.edit(CategoryEdit.Existing(insurance))
            page.save("essence", car, null)

            page.editing.first { (it as? CategoryEdit.Existing)?.refused == true }
            assertEquals(listOf("Assurance"), groups.categoriesIn(home).first().map { it.category.name })
        }

    @Test
    fun `a group rename onto another group's name keeps the dialog open and says so`() =
        runTest {
            val home = groups.named("Maison")!!
            groups.named("Auto")
            val page = page(home)

            page.edit(CategoryEdit.RenameGroup())
            page.renameGroup("auto")

            assertEquals(
                CategoryEdit.RenameGroup(refused = true),
                page.editing.first { it != CategoryEdit.RenameGroup() },
            )
            page.renameGroup("Logement")
            assertEquals("Logement", page.group.first { it?.name == "Logement" }?.name)
        }

    @Test
    fun `deleting the group closes its page and leaves its categories in none`() =
        runTest {
            val home = groups.named("Maison")!!
            repository.named("Assurance", home)
            val page = page(home)

            page.deleteGroup()

            assertTrue(page.deleted.first { it })
            assertEquals(listOf("Assurance"), viewModel.categories.first { it.isNotEmpty() }.map { it.category.name })
        }

    private fun page(groupId: Long?) =
        CategoryGroupViewModel(
            SavedStateHandle(groupId?.let { mapOf(CATEGORY_GROUP_ID_ARG to it) }.orEmpty()),
            repository,
            groups,
        )
}
