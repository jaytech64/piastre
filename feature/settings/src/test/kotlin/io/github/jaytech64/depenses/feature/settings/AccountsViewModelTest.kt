// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.feature.settings

import io.github.jaytech64.depenses.core.data.AccountRepository
import io.github.jaytech64.depenses.core.database.DepensesDatabase
import io.github.jaytech64.depenses.core.database.ExpenseTransactionEntity
import io.github.jaytech64.depenses.core.model.AccountOrigin
import io.github.jaytech64.depenses.core.model.AccountType
import io.github.jaytech64.depenses.core.model.Money
import io.github.jaytech64.depenses.core.model.TransactionSource
import io.github.jaytech64.depenses.core.model.TransactionStatus
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

/** The Cards section's rules, against a real database. */
@RunWith(RobolectricTestRunner::class)
class AccountsViewModelTest {
    private lateinit var database: DepensesDatabase
    private lateinit var repository: AccountRepository
    private lateinit var viewModel: AccountsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        database = inMemoryDatabase()
        repository = AccountRepository(database.accountDao())
        viewModel = AccountsViewModel(repository)
    }

    @After
    fun tearDown() {
        database.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `an account added here is listed with its name and digits`() =
        runTest {
            viewModel.save(
                account = null,
                name = "Amex papier",
                type = AccountType.CARD,
                last4 = "8484",
                openingBalance = null,
            )

            val account = viewModel.accounts.first { it.isNotEmpty() }.single()

            assertEquals("Amex papier", account.name)
            assertEquals("8484", account.last4)
            assertEquals(AccountOrigin.USER, account.origin)
        }

    /** The digits of a Wallet account are the bank's; only the name is yours. */
    @Test
    fun `a Wallet card takes a name but keeps the bank's digits`() =
        runTest {
            repository.resolve("Carte d'accès Desrives", "0000")
            val wallet = viewModel.accounts.first { it.isNotEmpty() }.single()

            viewModel.save(
                wallet,
                name = "Débit Desrives",
                type = AccountType.CARD,
                last4 = "9999",
                openingBalance = null,
            )

            val saved = viewModel.accounts.first { it.single().name != null }.single()
            assertEquals("Débit Desrives", saved.name)
            assertEquals("0000", saved.last4)
        }

    /** The bank supplies a card's digits, never what it held, so a Wallet card takes a balance too. */
    @Test
    fun `a Wallet card takes an opening balance, and it becomes its balance`() =
        runTest {
            repository.resolve("Carte d'accès Desrives", "0000")
            val wallet = viewModel.accounts.first { it.isNotEmpty() }.single()

            viewModel.save(
                wallet,
                name = "Débit Desrives",
                type = AccountType.CARD,
                last4 = "0000",
                openingBalance = Money(1_850_00, Money.CAD),
            )

            val saved = viewModel.accounts.first { it.single().openingBalance != null }.single()
            assertEquals(1_850_00L, saved.openingBalance?.minor)
            assertEquals(1_850_00L, saved.balance.minor)
        }

    @Test
    fun `clearing the opening balance leaves none, rather than zero`() =
        runTest {
            viewModel.save(
                account = null,
                name = "CELI",
                type = AccountType.BANK,
                last4 = "",
                openingBalance = Money(5_000_00, Money.CAD),
            )
            val added = viewModel.accounts.first { it.isNotEmpty() }.single()

            viewModel.save(added, name = "CELI", type = AccountType.BANK, last4 = "", openingBalance = null)

            val cleared = viewModel.accounts.first { it.single().openingBalance == null }.single()
            assertEquals(0L, cleared.balance.minor)
        }

    @Test
    fun `an account you added can have its digits corrected`() =
        runTest {
            viewModel.save(
                account = null,
                name = "Amex papier",
                type = AccountType.CARD,
                last4 = "1090",
                openingBalance = null,
            )
            val added = viewModel.accounts.first { it.isNotEmpty() }.single()

            viewModel.save(added, name = "Amex papier", type = AccountType.CARD, last4 = "8484", openingBalance = null)

            assertEquals(
                "8484",
                viewModel.accounts
                    .first { it.single().last4 == "8484" }
                    .single()
                    .last4,
            )
        }

    /** An account money only ever leaves through is still in use. */
    @Test
    fun `an account a transfer only leaves stays too`() =
        runTest {
            val cash = repository.add(name = null, type = AccountType.CASH, last4 = null)
            val card = repository.resolve("Carte d'accès Desrives", "0000")!!
            payWith(accountId = cash, toAccountId = card)
            val destination = viewModel.accounts.first { it.size == 2 }.single { it.id == card }

            viewModel.remove(destination)

            assertTrue(viewModel.removalRefused.first { it })
        }

    @Test
    fun `an account still in use stays, and the screen is told why`() =
        runTest {
            val id = repository.resolve("Carte d'accès Desrives", "0000")!!
            payWith(accountId = id)
            val account = viewModel.accounts.first { it.isNotEmpty() }.single()

            viewModel.remove(account)

            assertTrue(viewModel.removalRefused.first { it })
            assertEquals(1, viewModel.accounts.value.size)
        }

    @Test
    fun `an account moved here moves in every list of accounts`() =
        runTest {
            repository.add(name = "Visa Desrives", type = AccountType.CARD, last4 = "7373")
            repository.add(name = "Chèques", type = AccountType.BANK, last4 = null)
            val chequing = viewModel.accounts.first { it.size == 2 }.last()

            viewModel.move(chequing, toIndex = 0)

            assertEquals(
                listOf("Chèques", "Visa Desrives"),
                viewModel.accounts.first { it.first().name == "Chèques" }.map { it.name },
            )
            assertEquals(listOf("Chèques", "Visa Desrives"), repository.all().first().map { it.name })
        }

    @Test
    fun `an unused account goes`() =
        runTest {
            viewModel.save(
                account = null,
                name = "Amex papier",
                type = AccountType.CARD,
                last4 = "8484",
                openingBalance = null,
            )
            val account = viewModel.accounts.first { it.isNotEmpty() }.single()

            viewModel.remove(account)

            assertTrue(viewModel.accounts.first { it.isEmpty() }.isEmpty())
        }

    private suspend fun payWith(
        accountId: Long? = null,
        toAccountId: Long? = null,
    ) = database.transactionDao().insert(
        ExpenseTransactionEntity(
            rawEventId = null,
            amountMinor = 625,
            currency = "CAD",
            occurredAtUtcMillis = 1_788_740_166_564,
            zoneId = "America/Toronto",
            localDateEpochDay = 20_705,
            merchantId = null,
            merchantRaw = "SQ *CAFE OLIMPICO 12",
            categoryId = null,
            source = TransactionSource.NOTIFICATION,
            status = TransactionStatus.CONFIRMED,
            reviewReason = null,
            reviewedAtUtcMillis = null,
            confidence = 1f,
            parserId = "google-wallet",
            parserVersion = 1,
            sourcePackage = "com.google.android.apps.walletnfcrel",
            sourceKey = "tag-1",
            cardLabel = "Carte d'accès Desrives",
            cardLast4 = "0000",
            note = null,
            accountId = accountId,
            toAccountId = toAccountId,
        ),
    )
}
