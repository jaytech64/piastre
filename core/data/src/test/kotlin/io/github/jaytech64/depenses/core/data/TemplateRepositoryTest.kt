// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.data

import io.github.jaytech64.depenses.core.database.DepensesDatabase
import io.github.jaytech64.depenses.core.model.AccountType
import io.github.jaytech64.depenses.core.model.Money
import io.github.jaytech64.depenses.core.model.TemplateContent
import io.github.jaytech64.depenses.core.model.TransactionKind
import io.github.jaytech64.depenses.core.testing.inMemoryDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import javax.inject.Provider

/** Templates against a real database. */
@RunWith(RobolectricTestRunner::class)
class TemplateRepositoryTest {
    private lateinit var database: DepensesDatabase
    private lateinit var accounts: AccountRepository
    private lateinit var merchants: MerchantRepository
    private lateinit var transactions: TransactionRepository
    private lateinit var templates: TemplateRepository
    private var now = Instant.parse("2026-10-03T14:00:00Z")

    @Before
    fun open() {
        database = inMemoryDatabase()
        val clock =
            object : Clock() {
                override fun instant(): Instant = now

                override fun getZone(): ZoneId = ZoneOffset.UTC

                override fun withZone(zone: ZoneId): Clock = this
            }
        merchants = MerchantRepository(database.merchantDao())
        accounts = AccountRepository(database.accountDao())
        val categories = CategoryRepository(database.categoryDao(), database.merchantDao(), database.accountDao())
        transactions =
            TransactionRepository(database, database.transactionDao(), merchants, categories, Provider { ZONE })
        templates = TemplateRepository(database.transactionTemplateDao(), merchants, clock)
    }

    @After
    fun close() = database.close()

    @Test
    fun `a template comes back as it was saved, with the names of what it points at`() =
        runTest {
            val cash = accounts.add("Comptant", AccountType.CASH, last4 = null, openingBalance = null)

            val id = templates.save(coffee.copy(accountId = cash))

            val saved = templates.byId(id)
            assertEquals("Café matin", saved?.name)
            assertEquals(Money(475, Money.CAD), saved?.amount)
            assertEquals("Zoo Cafe", saved?.merchantName)
            assertEquals("Comptant", saved?.accountName)
        }

    @Test
    fun `a template with no amount has none, not zero`() =
        runTest {
            val id = templates.save(coffee.copy(amount = null))

            assertNull(templates.byId(id)?.amount)
        }

    @Test
    fun `a transfer template names no merchant, and nothing else keeps a destination`() =
        runTest {
            val bank = accounts.add("Chèques", AccountType.BANK, last4 = null, openingBalance = null)
            val cash = accounts.add("Comptant", AccountType.CASH, last4 = null, openingBalance = null)
            val withdrawal =
                coffee.copy(name = "Retrait", kind = TransactionKind.TRANSFER, accountId = bank, toAccountId = cash)

            val transfer = templates.byId(templates.save(withdrawal))
            val expense = templates.byId(templates.save(withdrawal.copy(kind = TransactionKind.EXPENSE)))

            assertNull(transfer?.merchantName)
            assertEquals(cash, transfer?.toAccountId)
            assertNull(expense?.toAccountId)
        }

    @Test
    fun `the template last used is offered first`() =
        runTest {
            val coffeeId = templates.save(coffee)
            templates.save(coffee.copy(name = "Passe d'autobus"))

            templates.markUsed(coffeeId)

            assertEquals(listOf("Café matin", "Passe d'autobus"), templates.all().first().map { it.name })
        }

    @Test
    fun `changing a template keeps its place in the list`() =
        runTest {
            val coffeeId = templates.save(coffee)
            templates.markUsed(coffeeId)
            templates.save(coffee.copy(name = "Passe d'autobus"))

            templates.change(coffeeId, coffee.copy(name = "Café", amount = Money(525, Money.CAD)))

            val first = templates.all().first().first()
            assertEquals("Café", first.name)
            assertEquals(Money(525, Money.CAD), first.amount)
        }

    @Test
    fun `changing or deleting a template leaves the transactions it filled as they are`() =
        runTest {
            val id = templates.save(coffee)
            transactions.addManual(
                amount = Money(475, Money.CAD),
                merchantName = "Zoo Cafe",
                occurredAt = now,
                note = null,
                kind = TransactionKind.EXPENSE,
                accountId = null,
                toAccountId = null,
                categoryId = null,
                reach = null,
            )
            val before = transactions.confirmed().first()

            templates.change(id, coffee.copy(merchantName = "Ailleurs", amount = Money(999, Money.CAD)))
            templates.delete(id)

            assertEquals(before, transactions.confirmed().first())
            assertEquals(0, templates.count().first())
        }

    @Test
    fun `an account a template uses cannot be removed`() =
        runTest {
            val cash = accounts.add("Comptant", AccountType.CASH, last4 = null, openingBalance = null)
            templates.save(coffee.copy(accountId = cash))

            assertFalse(accounts.remove(cash))
        }

    private val coffee =
        TemplateContent(
            name = " Café matin ",
            kind = TransactionKind.EXPENSE,
            amount = Money(475, Money.CAD),
            merchantName = "Zoo Cafe",
            accountId = null,
            toAccountId = null,
            categoryId = null,
            note = null,
        )

    private companion object {
        val ZONE: ZoneId = ZoneId.of("America/Toronto")
    }
}
