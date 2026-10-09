// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.jaytech64.piastre.core.model.AccountOrigin
import io.github.jaytech64.piastre.core.model.AccountType
import io.github.jaytech64.piastre.core.model.TransactionKind
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
class TransactionTemplateDaoTest {
    private lateinit var database: PiastreDatabase
    private val templates get() = database.transactionTemplateDao()

    @Before
    fun open() {
        database =
            Room
                .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), PiastreDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }

    @After
    fun close() = database.close()

    @Test
    fun `the templates are listed last used first, then the never used, newest first`() =
        runTest {
            templates.insert(template(uuid = "never used, older"))
            templates.insert(template(uuid = "used long ago", lastUsedAtUtcMillis = 1_000))
            templates.insert(template(uuid = "used lately", lastUsedAtUtcMillis = 2_000))
            templates.insert(template(uuid = "never used, newer"))

            val order = templates.allWithNames().first().map { it.template.uuid }

            assertEquals(listOf("used lately", "used long ago", "never used, newer", "never used, older"), order)
        }

    @Test
    fun `using a template moves it to the front`() =
        runTest {
            val coffee = templates.insert(template(uuid = "coffee"))
            templates.insert(template(uuid = "bus"))

            templates.markUsed(coffee, atUtcMillis = 5_000)

            assertEquals(listOf("coffee", "bus"), templates.allWithNames().first().map { it.template.uuid })
        }

    @Test
    fun `an import of a template already here adds nothing`() =
        runTest {
            templates.insert(template(uuid = "same"))

            templates.insertOrIgnore(template(uuid = "same", amountMinor = 1))

            assertEquals(listOf<Long?>(475), templates.allOnce().map { it.amountMinor })
        }

    @Test
    fun `an account a template still uses cannot be deleted`() =
        runTest {
            val accounts = database.accountDao()
            val account =
                accounts.insert(
                    AccountEntity(
                        name = "Comptant",
                        type = AccountType.CASH,
                        issuerLabel = null,
                        last4 = null,
                        origin = AccountOrigin.USER,
                    ),
                )
            templates.insert(template(toAccountId = account))

            assertEquals(0, accounts.deleteIfUnused(account))
        }

    @Test
    fun `deleting a category takes it off the templates that gave it`() =
        runTest {
            val categories = database.categoryDao()
            val restaurants = categories.insert(CategoryEntity(name = "Restaurants", normalisedName = "restaurants"))
            val id = templates.insert(template(categoryId = restaurants))

            categories.delete(restaurants)

            assertNull(templates.byId(id)?.categoryId)
        }

    private fun template(
        uuid: String = "5d1b7e1c",
        amountMinor: Long? = 475,
        toAccountId: Long? = null,
        categoryId: Long? = null,
        lastUsedAtUtcMillis: Long? = null,
    ) = TransactionTemplateEntity(
        uuid = uuid,
        name = "Café matin",
        kind = TransactionKind.EXPENSE,
        amountMinor = amountMinor,
        currency = "CAD",
        merchantId = null,
        merchantRaw = "Zoo Cafe",
        accountId = null,
        toAccountId = toAccountId,
        categoryId = categoryId,
        note = null,
        lastUsedAtUtcMillis = lastUsedAtUtcMillis,
    )
}
