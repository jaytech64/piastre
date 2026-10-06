// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.jaytech64.depenses.core.model.TransactionSource
import io.github.jaytech64.depenses.core.model.TransactionStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TransactionPartDaoTest {
    private lateinit var database: DepensesDatabase
    private val parts get() = database.transactionPartDao()
    private val transactions get() = database.transactionDao()

    @Before
    fun open() {
        database =
            Room
                .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), DepensesDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }

    @After
    fun close() = database.close()

    @Test
    fun `the parts of a transaction come back in the order they were given, each with its category`() =
        runTest {
            val groceries =
                database.categoryDao().insert(
                    CategoryEntity(name = "Épicerie", normalisedName = "EPICERIE"),
                )
            val costco = transactions.insert(transaction("costco"))

            parts.replace(costco, listOf(part(12_000, groceries), part(6_742, categoryId = null)))

            val read = parts.ofTransaction(costco)
            assertEquals(listOf(12_000L, 6_742L), read.map { it.part.amountMinor })
            assertEquals(listOf("Épicerie", null), read.map { it.categoryName })
        }

    @Test
    fun `replacing the parts leaves none of the earlier ones`() =
        runTest {
            val costco = transactions.insert(transaction("costco"))
            parts.replace(costco, listOf(part(10_000), part(8_742)))

            parts.replace(costco, listOf(part(18_000), part(700), part(42)))

            assertEquals(listOf(18_000L, 700L, 42L), parts.ofTransaction(costco).map { it.part.amountMinor })
        }

    @Test
    fun `replacing the parts with none leaves the transaction unsplit`() =
        runTest {
            val costco = transactions.insert(transaction("costco"))
            parts.replace(costco, listOf(part(10_000), part(8_742)))

            parts.replace(costco, emptyList())

            assertEquals(emptyList<PartWithCategory>(), parts.allWithNames().first())
        }

    @Test
    fun `deleting a transaction deletes its parts and no other's`() =
        runTest {
            val costco = transactions.insert(transaction("costco"))
            val pharmacy = transactions.insert(transaction("pharmacy"))
            parts.replace(costco, listOf(part(10_000), part(8_742)))
            parts.replace(pharmacy, listOf(part(1_500), part(500)))

            transactions.deleteById(costco)

            assertEquals(listOf(pharmacy, pharmacy), parts.allOnce().map { it.transactionId })
        }

    private fun part(
        amountMinor: Long,
        categoryId: Long? = null,
    ) = TransactionPartEntity(
        transactionId = 0,
        position = 0,
        amountMinor = amountMinor,
        categoryId = categoryId,
        note = null,
    )

    private fun transaction(sourceKey: String) =
        ExpenseTransactionEntity(
            rawEventId = null,
            amountMinor = 18_742,
            currency = "CAD",
            occurredAtUtcMillis = 1_788_740_166_564L,
            zoneId = "America/Toronto",
            localDateEpochDay = 20_707,
            merchantId = null,
            merchantRaw = "COSTCO",
            categoryId = null,
            source = TransactionSource.MANUAL,
            status = TransactionStatus.CONFIRMED,
            reviewReason = null,
            reviewedAtUtcMillis = null,
            confidence = 1f,
            parserId = null,
            parserVersion = null,
            sourcePackage = "manual",
            sourceKey = sourceKey,
            cardLabel = null,
            cardLast4 = null,
            note = null,
        )
}
