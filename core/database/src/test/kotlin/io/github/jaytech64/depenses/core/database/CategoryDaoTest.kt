// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.jaytech64.depenses.core.model.AccountOrigin
import io.github.jaytech64.depenses.core.model.AccountType
import io.github.jaytech64.depenses.core.model.TransactionKind
import io.github.jaytech64.depenses.core.model.TransactionSource
import io.github.jaytech64.depenses.core.model.TransactionStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CategoryDaoTest {
    private lateinit var database: DepensesDatabase
    private val transactions get() = database.transactionDao()
    private val categories get() = database.categoryDao()

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
    fun `a re-post keeps the category you gave the purchase`() =
        runTest {
            val groceries = category("Épicerie")
            val restaurants = category("Restaurants")
            val id = transactions.insertOrUpdate(row(categoryId = null))
            transactions.update(transactions.byId(id)!!.copy(categoryId = groceries))

            transactions.insertOrUpdate(row(categoryId = restaurants))

            assertEquals(groceries, transactions.byId(id)?.categoryId)
        }

    @Test
    fun `a re-post of a purchase with no category takes the one its reading carries`() =
        runTest {
            val groceries = category("Épicerie")
            val id = transactions.insertOrUpdate(row(categoryId = null))

            transactions.insertOrUpdate(row(categoryId = groceries))

            assertEquals(groceries, transactions.byId(id)?.categoryId)
        }

    @Test
    fun `a merchant's other purchases with no category take it, and the rest keep theirs`() =
        runTest {
            val metro = merchant("METRO PLUS")
            val groceries = category("Épicerie")
            val home = category("Maison")
            val chosen = transactions.insert(row("a", merchantId = metro))
            val blank = transactions.insert(row("b", merchantId = metro))
            val filedElsewhere = transactions.insert(row("c", merchantId = metro, categoryId = home))
            val waiting = transactions.insert(row("d", merchantId = metro, status = TransactionStatus.NEEDS_REVIEW))
            val dismissed = transactions.insert(row("e", merchantId = metro, status = TransactionStatus.DISMISSED))
            val refund = transactions.insert(row("f", merchantId = metro, kind = TransactionKind.INCOME))
            val elsewhere = transactions.insert(row("g", merchantId = merchant("ZOO CAFE")))

            assertEquals(2, categories.uncategorisedAtMerchant(metro, TransactionKind.EXPENSE, chosen).first())
            val changed = categories.categoriseAtMerchant(metro, TransactionKind.EXPENSE, groceries, chosen)

            assertEquals(2, changed)
            assertEquals(groceries, transactions.byId(blank)?.categoryId)
            assertEquals(groceries, transactions.byId(waiting)?.categoryId)
            assertEquals(home, transactions.byId(filedElsewhere)?.categoryId)
            assertNull(transactions.byId(chosen)?.categoryId)
            assertNull(transactions.byId(dismissed)?.categoryId)
            assertNull(transactions.byId(refund)?.categoryId)
            assertNull(transactions.byId(elsewhere)?.categoryId)
        }

    @Test
    fun `categorising a merchant's purchases does not lock the parser out of them`() =
        runTest {
            val metro = merchant("METRO PLUS")
            val id = transactions.insert(row(merchantId = metro))

            categories.categoriseAtMerchant(metro, TransactionKind.EXPENSE, category("Épicerie"), exceptId = null)

            assertNull(transactions.byId(id)?.reviewedAtUtcMillis)
        }

    @Test
    fun `transfers into an account with no category take it`() =
        runTest {
            val debit = account("Débit")
            val mortgage = account("Prêt hypothécaire")
            val visa = account("Visa")
            val principal = category("Hypothèque")
            val march = transactions.insert(transfer("march", from = debit, to = mortgage))
            val april = transactions.insert(transfer("april", from = debit, to = mortgage))
            val cardPayment = transactions.insert(transfer("visa", from = debit, to = visa))

            assertEquals(1, categories.uncategorisedTransfersInto(mortgage, april).first())
            categories.categoriseTransfersInto(mortgage, principal, april)

            assertEquals(principal, transactions.byId(march)?.categoryId)
            assertNull(transactions.byId(april)?.categoryId)
            assertNull(transactions.byId(cardPayment)?.categoryId)
        }

    @Test
    fun `the category a purchase carries is named beside it`() =
        runTest {
            val id = transactions.insert(row(categoryId = category("Épicerie")))

            assertEquals("Épicerie", transactions.withMerchantById(id)?.categoryName)
        }

    @Test
    fun `deleting a category leaves its purchases, merchants and accounts without one`() =
        runTest {
            val groceries = category("Épicerie")
            val metro = merchant("METRO PLUS")
            val mortgage = account("Prêt hypothécaire")
            database.merchantDao().setCategory(metro, groceries)
            database.accountDao().setCategory(mortgage, groceries)
            val id = transactions.insert(row(merchantId = metro, categoryId = groceries))

            categories.delete(groceries)

            assertNull(transactions.byId(id)?.categoryId)
            assertNull(database.merchantDao().byId(metro)?.categoryId)
            assertNull(
                database
                    .accountDao()
                    .allOnce()
                    .single()
                    .categoryId,
            )
            assertEquals(emptyList<CategoryEntity>(), categories.allOnce())
        }

    @Test
    fun `a rename onto a name already taken changes nothing`() =
        runTest {
            val groceries = category("Épicerie")
            category("Restaurants")

            assertEquals(0, categories.rename(groceries, "restaurants", "RESTAURANTS"))
            assertEquals(1, categories.rename(groceries, "Épicerie fine", "EPICERIE FINE"))
            assertEquals("Épicerie fine", categories.allOnce().first { it.id == groceries }.name)
        }

    @Test
    fun `the categories you use most for a kind come first, and unused ones by name`() =
        runTest {
            val salary = category("Salaire")
            val groceries = category("Épicerie")
            val transport = category("Transport")
            val bar = category("Bar")
            transactions.insert(row("a", categoryId = transport))
            transactions.insert(row("b", categoryId = transport))
            transactions.insert(row("c", categoryId = groceries))
            transactions.insert(row("d", categoryId = salary, kind = TransactionKind.INCOME))

            val order = categories.byUse(TransactionKind.EXPENSE, sinceEpochDay = 0).first().map { it.id }

            assertEquals(listOf(transport, groceries, bar, salary), order)
        }

    @Test
    fun `a category counts its confirmed transactions and what gives it automatically`() =
        runTest {
            val groceries = category("Épicerie")
            database.merchantDao().setCategory(merchant("METRO PLUS"), groceries)
            database.merchantDao().setCategory(merchant("IGA"), groceries)
            database.accountDao().setCategory(account("Épargne"), groceries)
            transactions.insert(row("a", categoryId = groceries))
            transactions.insert(row("b", categoryId = groceries, status = TransactionStatus.NEEDS_REVIEW))

            val counted = categories.allWithCounts().first().single()

            assertEquals(1, counted.transactionCount)
            assertEquals(2, counted.merchantCount)
            assertEquals(1, counted.accountCount)
        }

    private suspend fun category(name: String): Long =
        categories.insert(CategoryEntity(name = name, normalisedName = name.uppercase()))

    private suspend fun merchant(name: String): Long =
        database.merchantDao().insert(MerchantEntity(displayName = name, normalisedName = name))

    private suspend fun account(name: String): Long =
        database.accountDao().insertLast(
            AccountEntity(
                name = name,
                type = AccountType.BANK,
                issuerLabel = null,
                last4 = null,
                origin = AccountOrigin.USER,
            ),
        )

    private fun transfer(
        sourceKey: String,
        from: Long,
        to: Long,
    ) = row(sourceKey, kind = TransactionKind.TRANSFER).copy(accountId = from, toAccountId = to)

    private fun row(
        sourceKey: String = TAG,
        merchantId: Long? = null,
        categoryId: Long? = null,
        status: TransactionStatus = TransactionStatus.CONFIRMED,
        kind: TransactionKind = TransactionKind.EXPENSE,
    ) = ExpenseTransactionEntity(
        rawEventId = null,
        amountMinor = 4_000,
        currency = "CAD",
        occurredAtUtcMillis = 1_788_740_166_564,
        zoneId = "America/Toronto",
        localDateEpochDay = 20_000,
        merchantId = merchantId,
        merchantRaw = "METRO PLUS",
        categoryId = categoryId,
        source = TransactionSource.NOTIFICATION,
        status = status,
        reviewReason = null,
        reviewedAtUtcMillis = null,
        confidence = 1f,
        parserId = "google-wallet",
        parserVersion = 1,
        sourcePackage = WALLET,
        sourceKey = sourceKey,
        cardLabel = null,
        cardLast4 = "0000",
        note = null,
        kind = kind,
    )

    private companion object {
        const val WALLET = "com.google.android.apps.walletnfcrel"
        const val TAG = "tag-1"
    }
}
