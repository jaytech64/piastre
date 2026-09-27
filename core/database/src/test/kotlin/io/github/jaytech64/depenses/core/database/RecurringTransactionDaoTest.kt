// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.jaytech64.depenses.core.model.AccountOrigin
import io.github.jaytech64.depenses.core.model.AccountType
import io.github.jaytech64.depenses.core.model.Rhythm
import io.github.jaytech64.depenses.core.model.TransactionKind
import io.github.jaytech64.depenses.core.model.TransactionSource
import io.github.jaytech64.depenses.core.model.TransactionStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RecurringTransactionDaoTest {
    private lateinit var database: DepensesDatabase
    private val recurring get() = database.recurringTransactionDao()
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
    fun `stopping a schedule keeps every row it wrote, and they stop naming it`() =
        runTest {
            val id = recurring.insert(schedule())
            val written = transactions.insert(row(recurringId = id))

            recurring.stop(id)

            assertNull(recurring.byId(id))
            val kept = transactions.byId(written)
            assertNotNull(kept)
            assertNull(kept?.recurringId)
        }

    @Test
    fun `the schedules are listed soonest first, the ended ones last`() =
        runTest {
            recurring.insert(schedule(uuid = "a", nextDueEpochDay = 20_030))
            recurring.insert(schedule(uuid = "b", nextDueEpochDay = null))
            recurring.insert(schedule(uuid = "c", nextDueEpochDay = 20_010))

            val order = recurring.allWithNames().first().map { it.recurring.uuid }

            assertEquals(listOf("c", "a", "b"), order)
        }

    @Test
    fun `only a schedule whose day has come is due`() =
        runTest {
            recurring.insert(schedule(uuid = "today", nextDueEpochDay = 20_000))
            recurring.insert(schedule(uuid = "tomorrow", nextDueEpochDay = 20_001))
            recurring.insert(schedule(uuid = "ended", nextDueEpochDay = null))

            assertEquals(listOf("today"), recurring.dueBy(20_000).map { it.uuid })
        }

    @Test
    fun `an import of a schedule already here adds nothing`() =
        runTest {
            recurring.insert(schedule(uuid = "same"))

            recurring.insertOrIgnore(schedule(uuid = "same", amountMinor = 1))

            assertEquals(listOf(8_500L), recurring.allOnce().map { it.amountMinor })
        }

    @Test
    fun `an account a schedule still uses cannot be deleted`() =
        runTest {
            val accounts = database.accountDao()
            val account =
                accounts.insert(
                    AccountEntity(
                        name = "Chèques",
                        type = AccountType.BANK,
                        issuerLabel = null,
                        last4 = null,
                        origin = AccountOrigin.USER,
                    ),
                )
            recurring.insert(schedule(accountId = account))

            assertEquals(0, accounts.deleteIfUnused(account))
        }

    @Test
    fun `deleting a category takes it off the schedules that gave it`() =
        runTest {
            val categories = database.categoryDao()
            val internet = categories.insert(CategoryEntity(name = "Internet", normalisedName = "internet"))
            val id = recurring.insert(schedule(categoryId = internet))

            categories.delete(internet)

            assertNull(recurring.byId(id)?.categoryId)
        }

    private fun schedule(
        uuid: String = "5d1b7e1c",
        amountMinor: Long = 8_500,
        accountId: Long? = null,
        categoryId: Long? = null,
        nextDueEpochDay: Int? = 20_030,
    ) = RecurringTransactionEntity(
        uuid = uuid,
        amountMinor = amountMinor,
        currency = "CAD",
        kind = TransactionKind.EXPENSE,
        merchantId = null,
        merchantRaw = "Vidéotron",
        accountId = accountId,
        toAccountId = null,
        categoryId = categoryId,
        note = null,
        rhythm = Rhythm.MONTHLY,
        firstEpochDay = 20_000,
        lastEpochDay = null,
        nextDueEpochDay = nextDueEpochDay,
    )

    private fun row(recurringId: Long) =
        ExpenseTransactionEntity(
            rawEventId = null,
            amountMinor = 8_500,
            currency = "CAD",
            occurredAtUtcMillis = 1_788_740_166_564,
            zoneId = "America/Toronto",
            localDateEpochDay = 20_000,
            merchantId = null,
            merchantRaw = "Vidéotron",
            categoryId = null,
            source = TransactionSource.MANUAL,
            status = TransactionStatus.CONFIRMED,
            reviewReason = null,
            reviewedAtUtcMillis = null,
            confidence = 1f,
            parserId = null,
            parserVersion = null,
            sourcePackage = "manual",
            sourceKey = "typed",
            cardLabel = null,
            cardLast4 = null,
            note = null,
            recurringId = recurringId,
        )
}
