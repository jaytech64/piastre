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
    private val groups get() = database.categoryGroupDao()

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

    @Test
    fun `giving a merchant's purchases a category leaves a split there alone`() =
        runTest {
            val groceries = category("Épicerie")
            val costco = merchant("COSTCO")
            val split = transactions.insert(row("split", merchantId = costco))
            splitInTwo(split)
            transactions.insert(row("plain", merchantId = costco))

            val waiting = categories.uncategorisedAtMerchant(costco, TransactionKind.EXPENSE, exceptId = null).first()
            val changed = categories.categoriseAtMerchant(costco, TransactionKind.EXPENSE, groceries, exceptId = null)

            assertEquals(1, waiting)
            assertEquals(1, changed)
            assertNull(transactions.byId(split)?.categoryId)
        }

    @Test
    fun `giving the transfers into an account a category leaves a split one alone`() =
        runTest {
            val mortgage = category("Hypothèque")
            val bank = account("Hypothèque")
            val split = transactions.insert(transfer("split", from = account("Chèques"), to = bank))
            splitInTwo(split)

            val waiting = categories.uncategorisedTransfersInto(bank, exceptId = null).first()
            val changed = categories.categoriseTransfersInto(bank, mortgage, exceptId = null)

            assertEquals(0, waiting)
            assertEquals(0, changed)
            assertNull(transactions.byId(split)?.categoryId)
        }

    @Test
    fun `categorising rows picked by hand leaves a split among them alone`() =
        runTest {
            val groceries = category("Épicerie")
            val split = transactions.insert(row("split"))
            splitInTwo(split)
            val plain = transactions.insert(row("plain"))

            val changed = transactions.categorise(listOf(split, plain), groceries)

            assertEquals(1, changed)
            assertNull(transactions.byId(split)?.categoryId)
            assertEquals(groceries, transactions.byId(plain)?.categoryId)
        }

    @Test
    fun `a re-post of a split purchase does not give it its merchant's category`() =
        runTest {
            val groceries = category("Épicerie")
            val split = transactions.insert(row("costco"))
            splitInTwo(split)

            transactions.insertOrUpdate(row("costco", categoryId = groceries))

            assertNull(transactions.byId(split)?.categoryId)
        }

    private suspend fun splitInTwo(transactionId: Long) =
        database.transactionPartDao().replace(
            transactionId,
            List(2) {
                TransactionPartEntity(
                    transactionId = transactionId,
                    position = it,
                    amountMinor = 100,
                    categoryId = null,
                    note = null,
                )
            },
        )

    @Test
    fun `a split is a use of each category its parts carry, once`() =
        runTest {
            val groceries = category("Épicerie")
            val home = category("Maison")
            val bar = category("Bar")
            val costco = transactions.insert(row("costco"))
            database.transactionPartDao().replace(
                costco,
                listOf(groceries, groceries, home).map { categoryId ->
                    TransactionPartEntity(
                        transactionId = costco,
                        position = 0,
                        amountMinor = 100,
                        categoryId = categoryId,
                        note = null,
                    )
                },
            )
            transactions.insert(row("iga", categoryId = groceries))

            val counts = categories.allWithCounts().first().associate { it.category.id to it.transactionCount }
            val order = categories.byUse(TransactionKind.EXPENSE, sinceEpochDay = 0).first().map { it.id }
            val recent = categories.recentlyUsed(TransactionKind.EXPENSE, sinceEpochDay = 0, limit = 5).first()

            assertEquals(mapOf(groceries to 2, home to 1, bar to 0), counts)
            assertEquals(listOf(groceries, home, bar), order)
            assertEquals(listOf(groceries, home), recent.map { it.id })
        }

    @Test
    fun `deleting a group leaves its categories in none, and every transaction keeps its category`() =
        runTest {
            val home = group("Maison")
            val power = category("Électricité")
            val elsewhere = group("Auto")
            val fuel = category("Essence")
            categories.moveToGroup(power, home)
            categories.moveToGroup(fuel, elsewhere)
            val id = transactions.insert(row(categoryId = power))

            groups.delete(home)

            assertNull(categories.allOnce().first { it.id == power }.groupId)
            assertEquals(elsewhere, categories.allOnce().first { it.id == fuel }.groupId)
            assertEquals(power, transactions.byId(id)?.categoryId)
            assertEquals(listOf("Auto"), groups.allOnce().map { it.name })
        }

    @Test
    fun `a group rename onto a name already taken changes nothing`() =
        runTest {
            val home = group("Maison")
            group("Auto")

            assertEquals(0, groups.rename(home, "auto", "AUTO"))
            assertEquals(1, groups.rename(home, "Logement", "LOGEMENT"))
            assertEquals("Logement", groups.allOnce().first { it.id == home }.name)
        }

    private suspend fun category(name: String): Long =
        categories.insert(CategoryEntity(name = name, normalisedName = name.uppercase()))

    private suspend fun group(name: String): Long =
        groups.insert(CategoryGroupEntity(name = name, normalisedName = name.uppercase()))

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
