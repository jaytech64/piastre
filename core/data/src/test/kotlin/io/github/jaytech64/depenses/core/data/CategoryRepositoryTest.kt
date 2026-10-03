// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.data

import io.github.jaytech64.depenses.core.database.DepensesDatabase
import io.github.jaytech64.depenses.core.database.ExpenseTransactionEntity
import io.github.jaytech64.depenses.core.model.TransactionKind
import io.github.jaytech64.depenses.core.model.TransactionSource
import io.github.jaytech64.depenses.core.model.TransactionStatus
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

    @Test
    fun `a group counts its categories and the transactions they carry, and the ungrouped come last`() =
        runTest {
            val home = categories.namedGroup("Maison")!!
            val power = categories.named("Électricité", home)!!
            val insurance = categories.named("Assurance", home)!!
            categories.namedGroup("Auto")
            val loose = categories.named("Logement")!!
            record("a", power)
            record("b", power)
            record("c", insurance)
            record("d", insurance, TransactionStatus.NEEDS_REVIEW)
            record("e", loose)

            val usage = categories.groupUsage().first()

            assertEquals(
                listOf("Auto" to (0 to 0), "Maison" to (2 to 3), null to (1 to 1)),
                usage.map { it.group?.name to (it.categoryCount to it.transactionCount) },
            )
        }

    @Test
    fun `with every category in a group, no ungrouped entry is shown`() =
        runTest {
            categories.named("Électricité", categories.namedGroup("Maison"))

            assertEquals(listOf<String?>("Maison"), categories.groupUsage().first().map { it.group?.name })
        }

    @Test
    fun `typing a category you already have leaves it in its group`() =
        runTest {
            val home = categories.namedGroup("Maison")!!
            val power = categories.named("Électricité", home)!!

            assertEquals(power, categories.named("electricite", categories.namedGroup("Auto")))
            assertEquals(listOf("Électricité"), categories.inGroup(home).first().map { it.category.name })
        }

    @Test
    fun `moving a category takes it out of one group and into another`() =
        runTest {
            val home = categories.namedGroup("Maison")!!
            val car = categories.namedGroup("Auto")!!
            val insurance = categories.named("Assurance", home)!!

            categories.moveToGroup(insurance, car)

            assertEquals(emptyList<String>(), categories.inGroup(home).first().map { it.category.name })
            assertEquals(listOf("Assurance"), categories.inGroup(car).first().map { it.category.name })
        }

    @Test
    fun `deleting a group leaves its categories in none`() =
        runTest {
            val home = categories.namedGroup("Maison")!!
            categories.named("Assurance", home)

            categories.deleteGroup(home)

            assertEquals(listOf("Assurance"), categories.inGroup(null).first().map { it.category.name })
            assertEquals(emptyList<String>(), categories.groups().first().map { it.name })
        }

    @Test
    fun `a group rename onto another group's name is refused`() =
        runTest {
            val home = categories.namedGroup("Maison")!!
            categories.namedGroup("Auto")

            assertFalse(categories.renameGroup(home, "auto"))
            assertTrue(categories.renameGroup(home, "Logement"))
            assertEquals(home, categories.namedGroup("LOGEMENT"))
        }

    private suspend fun record(
        key: String,
        categoryId: Long,
        status: TransactionStatus = TransactionStatus.CONFIRMED,
    ) = database.transactionDao().insert(
        ExpenseTransactionEntity(
            rawEventId = null,
            amountMinor = 1_000,
            currency = "CAD",
            occurredAtUtcMillis = 0,
            zoneId = "America/Toronto",
            localDateEpochDay = 0,
            merchantId = null,
            merchantRaw = null,
            categoryId = categoryId,
            source = TransactionSource.MANUAL,
            status = status,
            reviewReason = null,
            reviewedAtUtcMillis = null,
            confidence = 1f,
            parserId = null,
            parserVersion = null,
            sourcePackage = "manual",
            sourceKey = key,
            cardLabel = null,
            cardLast4 = null,
            note = null,
            kind = TransactionKind.EXPENSE,
        ),
    )

    private suspend fun names(): List<String> = categories.all().first().map { it.category.name }
}

/** The repository as the app's graph builds it, over [this] database. */
internal fun DepensesDatabase.categoryRepository() = CategoryRepository(categoryDao(), merchantDao(), accountDao())
