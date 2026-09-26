// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.data

import io.github.jaytech64.depenses.core.database.DepensesDatabase
import io.github.jaytech64.depenses.core.testing.inMemoryDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CategoryRepositoryTest {
    private lateinit var database: DepensesDatabase
    private lateinit var categories: CategoryRepository

    @Before
    fun open() {
        database = inMemoryDatabase()
        categories = database.categoryRepository()
    }

    @After
    fun close() = database.close()

    @Test
    fun `typing a name you already have picks that category and keeps its spelling`() =
        runTest {
            val groceries = categories.named("Épicerie")

            assertEquals(groceries, categories.named("  epicerie "))
            assertEquals(listOf("Épicerie"), names())
        }

    @Test
    fun `a blank name makes no category`() =
        runTest {
            assertNull(categories.named("   "))
            assertEquals(emptyList<String>(), names())
        }

    @Test
    fun `a rename onto another category's name is refused and changes nothing`() =
        runTest {
            val groceries = categories.named("Épicerie")!!
            categories.named("Restaurants")

            assertFalse(categories.rename(groceries, "RESTAURANTS"))
            assertFalse(categories.rename(groceries, "  "))
            assertTrue(categories.rename(groceries, "Épicerie fine"))
            assertEquals(listOf("Épicerie fine", "Restaurants"), names())
        }

    @Test
    fun `a rename that only changes the spelling of the same name is kept`() =
        runTest {
            val groceries = categories.named("epicerie")!!

            assertTrue(categories.rename(groceries, "Épicerie"))
            assertEquals(listOf("Épicerie"), names())
        }

    @Test
    fun `a deleted category is gone from the list`() =
        runTest {
            val groceries = categories.named("Épicerie")!!
            categories.named("Restaurants")

            categories.delete(groceries)

            assertEquals(listOf("Restaurants"), names())
        }

    private suspend fun names(): List<String> = categories.all().first().map { it.category.name }
}

/** The repository as the app's graph builds it, over [this] database. */
internal fun DepensesDatabase.categoryRepository() = CategoryRepository(categoryDao(), merchantDao(), accountDao())
