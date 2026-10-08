// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.jaytech64.depenses.core.model.AccountOrigin
import io.github.jaytech64.depenses.core.model.AccountType
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

    @Test
    fun `a part that goes into an account comes back with that account`() =
        runTest {
            val mortgage = account("Hypothèque", AccountType.BANK)
            val payment = transactions.insert(transaction("payment"))

            parts.replace(payment, listOf(part(70_000), part(50_000, toAccountId = mortgage)))

            val read = parts.ofTransaction(payment)
            assertEquals(listOf(null, "Hypothèque"), read.map { it.toAccountName })
            assertEquals(listOf(null, AccountType.BANK), read.map { it.toAccountType })
        }

    @Test
    fun `a part adds its share to the account it goes into, and the transaction's account gives up the whole`() =
        runTest {
            val chequing = account("Chèques", AccountType.CARD)
            val mortgage = account("Hypothèque", AccountType.BANK)
            val payment = transactions.insert(transaction("payment", amountMinor = 120_000, accountId = chequing))
            parts.replace(payment, listOf(part(70_000), part(50_000, toAccountId = mortgage)))

            val balances = balances(throughDay = DAY)

            assertEquals(-120_000L, balances[chequing])
            assertEquals(50_000L, balances[mortgage])
        }

    @Test
    fun `a part dated after the day asked moves no account yet`() =
        runTest {
            val chequing = account("Chèques", AccountType.CARD)
            val mortgage = account("Hypothèque", AccountType.BANK)
            val payment = transactions.insert(transaction("payment", amountMinor = 120_000, accountId = chequing))
            parts.replace(payment, listOf(part(70_000), part(50_000, toAccountId = mortgage)))

            assertEquals(0L, balances(throughDay = DAY - 1)[mortgage])
        }

    @Test
    fun `a part of a row waiting for review moves no account`() =
        runTest {
            val mortgage = account("Hypothèque", AccountType.BANK)
            val payment =
                transactions.insert(
                    transaction("payment", amountMinor = 120_000, status = TransactionStatus.NEEDS_REVIEW),
                )
            parts.replace(payment, listOf(part(70_000), part(50_000, toAccountId = mortgage)))

            assertEquals(0L, balances(throughDay = DAY)[mortgage])
        }

    @Test
    fun `an account a part goes into is in use, and cannot be deleted`() =
        runTest {
            val mortgage = account("Hypothèque", AccountType.BANK)
            val payment = transactions.insert(transaction("payment"))
            parts.replace(payment, listOf(part(70_000), part(50_000, toAccountId = mortgage)))

            val counted =
                database
                    .accountDao()
                    .allWithCounts("CAD", DAY)
                    .first()
                    .single()

            assertEquals(1, counted.transactionCount)
            assertEquals(0, database.accountDao().deleteIfUnused(mortgage))
        }

    private suspend fun balances(throughDay: Int): Map<Long, Long> =
        database
            .accountDao()
            .allWithCounts("CAD", throughDay)
            .first()
            .associate { it.account.id to it.balanceMinor }

    private suspend fun account(
        name: String,
        type: AccountType,
    ) = database.accountDao().insert(
        AccountEntity(name = name, type = type, issuerLabel = null, last4 = null, origin = AccountOrigin.USER),
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

    private fun transaction(
        sourceKey: String,
        amountMinor: Long = 18_742,
        accountId: Long? = null,
        status: TransactionStatus = TransactionStatus.CONFIRMED,
    ) = ExpenseTransactionEntity(
        rawEventId = null,
        amountMinor = amountMinor,
        currency = "CAD",
        occurredAtUtcMillis = 1_788_740_166_564L,
        zoneId = "America/Toronto",
        localDateEpochDay = DAY,
        merchantId = null,
        merchantRaw = "COSTCO",
        categoryId = null,
        source = TransactionSource.MANUAL,
        status = status,
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
        accountId = accountId,
    )

    private companion object {
        const val DAY = 20_707
    }
}
