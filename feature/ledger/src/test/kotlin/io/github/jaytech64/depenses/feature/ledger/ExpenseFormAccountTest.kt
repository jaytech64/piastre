// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.feature.ledger

import androidx.lifecycle.SavedStateHandle
import io.github.jaytech64.depenses.core.data.AccountRepository
import io.github.jaytech64.depenses.core.data.CategoryRepository
import io.github.jaytech64.depenses.core.data.MerchantRepository
import io.github.jaytech64.depenses.core.data.TransactionEditor
import io.github.jaytech64.depenses.core.data.TransactionRepository
import io.github.jaytech64.depenses.core.database.DepensesDatabase
import io.github.jaytech64.depenses.core.database.ExpenseTransactionEntity
import io.github.jaytech64.depenses.core.model.AccountType
import io.github.jaytech64.depenses.core.model.Money
import io.github.jaytech64.depenses.core.model.TransactionKind
import io.github.jaytech64.depenses.core.model.TransactionSource
import io.github.jaytech64.depenses.core.model.TransactionStatus
import io.github.jaytech64.depenses.core.testing.inMemoryDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Provider

/** The account the form fills in from the merchant you name. */
@RunWith(RobolectricTestRunner::class)
class ExpenseFormAccountTest {
    private lateinit var database: DepensesDatabase
    private lateinit var merchants: MerchantRepository
    private lateinit var categories: CategoryRepository
    private lateinit var transactions: TransactionRepository
    private lateinit var accounts: AccountRepository
    private lateinit var viewModel: ExpenseFormViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        database = inMemoryDatabase()
        merchants = MerchantRepository(database.merchantDao())
        categories = CategoryRepository(database.categoryDao(), database.merchantDao(), database.accountDao())
        accounts = AccountRepository(database.accountDao())
        transactions =
            TransactionRepository(database, database.transactionDao(), merchants, categories, Provider { ZONE })
    }

    @After
    fun tearDown() {
        database.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `naming a merchant fills in the account last paid from there`() =
        runTest {
            val visa = accounts.add("Visa", AccountType.CARD, last4 = "")
            val debit = accounts.add("Débit", AccountType.BANK, last4 = "")
            add("Métro Plus", visa, daysAgo = 9)
            add("Métro Plus", debit, daysAgo = 2)
            open()

            viewModel.onMerchantChange("metro plus")

            assertEquals(debit, viewModel.uiState.value.accountId)
        }

    @Test
    fun `a purchase that named no account is passed over`() =
        runTest {
            val visa = accounts.add("Visa", AccountType.CARD, last4 = "")
            add("Métro Plus", visa, daysAgo = 9)
            add("Métro Plus", accountId = null, daysAgo = 2)
            open()

            viewModel.onMerchantChange("Métro Plus")

            assertEquals(visa, viewModel.uiState.value.accountId)
        }

    @Test
    fun `a payment Wallet captured counts as the last time`() =
        runTest {
            val visa = accounts.add("Visa", AccountType.CARD, last4 = "")
            val debit = accounts.add("Débit", AccountType.BANK, last4 = "")
            add("Métro Plus", debit, daysAgo = 9)
            captured("Métro Plus", visa, daysAgo = 2)
            open()

            viewModel.onMerchantChange("Métro Plus")

            assertEquals(visa, viewModel.uiState.value.accountId)
        }

    @Test
    fun `a purchase dated after today is not the last time yet`() =
        runTest {
            val visa = accounts.add("Visa", AccountType.CARD, last4 = "")
            val debit = accounts.add("Débit", AccountType.BANK, last4 = "")
            add("Hydro-Québec", visa, daysAgo = 30)
            add("Hydro-Québec", debit, daysAgo = -3)
            open()

            viewModel.onMerchantChange("Hydro-Québec")

            assertEquals(visa, viewModel.uiState.value.accountId)
        }

    @Test
    fun `an income goes by the account last paid into, not the one last paid from`() =
        runTest {
            val visa = accounts.add("Visa", AccountType.CARD, last4 = "")
            val debit = accounts.add("Débit", AccountType.BANK, last4 = "")
            add("Costco", visa, daysAgo = 2)
            add("Costco", debit, daysAgo = 9, kind = TransactionKind.INCOME)
            open()
            viewModel.onMerchantChange("Costco")
            assertEquals(visa, viewModel.uiState.value.accountId)

            viewModel.onKindChange(TransactionKind.INCOME)
            advanceUntilIdle()

            assertEquals(debit, viewModel.uiState.value.accountId)
        }

    @Test
    fun `a merchant with nothing to go by takes back the account it filled in`() =
        runTest {
            val visa = accounts.add("Visa", AccountType.CARD, last4 = "")
            add("IGA", visa, daysAgo = 2)
            open()
            viewModel.onMerchantChange("IGA")

            viewModel.onMerchantChange("IGA Extra")

            assertNull(viewModel.uiState.value.accountId)
        }

    @Test
    fun `an account you picked is not replaced by the merchant's`() =
        runTest {
            val visa = accounts.add("Visa", AccountType.CARD, last4 = "")
            val debit = accounts.add("Débit", AccountType.BANK, last4 = "")
            add("Métro Plus", visa, daysAgo = 2)
            open()

            viewModel.onAccountChange(AccountSlot.SOURCE, debit)
            viewModel.onMerchantChange("Métro Plus")

            assertEquals(debit, viewModel.uiState.value.accountId)
        }

    @Test
    fun `correcting the merchant of a saved purchase keeps its account`() =
        runTest {
            val visa = accounts.add("Visa", AccountType.CARD, last4 = "")
            val debit = accounts.add("Débit", AccountType.BANK, last4 = "")
            add("Métro Plus", visa, daysAgo = 2)
            val typo = add("Metor", debit, daysAgo = 1)
            open(editing = typo)
            viewModel.uiState.first { !it.loading }

            viewModel.onMerchantChange("Métro Plus")

            assertEquals(debit, viewModel.uiState.value.accountId)
        }

    /** Opens the form the way the screen does, and waits until it knows every merchant's last account. */
    private suspend fun TestScope.open(editing: Long? = null) {
        viewModel =
            ExpenseFormViewModel(
                savedStateHandle = SavedStateHandle(editing?.let { mapOf(EXPENSE_ID_ARG to it) } ?: emptyMap()),
                transactions = transactions,
                editor =
                    TransactionEditor(
                        database,
                        database.transactionDao(),
                        merchants,
                        categories,
                        Clock.systemUTC(),
                    ),
                merchants = merchants,
                accounts = accounts,
                categories = categories,
                categoryGroups = database.categoryGroupRepository(),
                recurring = database.recurringRepository(Clock.systemUTC(), ZONE),
                templates = database.templateRepository(),
                clock = Clock.systemUTC(),
                zoneId = Provider { ZONE },
            )
        backgroundScope.launch { viewModel.knownMerchants.collect {} }
        backgroundScope.launch { viewModel.category.offer.collect {} }
        advanceUntilIdle()
    }

    private suspend fun add(
        merchant: String,
        accountId: Long?,
        daysAgo: Long,
        kind: TransactionKind = TransactionKind.EXPENSE,
    ): Long =
        transactions.addManual(
            amount = Money(4_000, Money.CAD),
            merchantName = merchant,
            occurredAt = noonOf(daysAgo).atZone(ZONE).toInstant(),
            note = null,
            kind = kind,
            accountId = accountId,
            toAccountId = null,
            categoryId = null,
            reach = null,
        )

    private suspend fun captured(
        merchant: String,
        accountId: Long,
        daysAgo: Long,
    ) {
        val noon = noonOf(daysAgo)
        database.transactionDao().insert(
            ExpenseTransactionEntity(
                rawEventId = null,
                amountMinor = 4_000,
                currency = Money.CAD,
                occurredAtUtcMillis = noon.atZone(ZONE).toInstant().toEpochMilli(),
                zoneId = ZONE.id,
                localDateEpochDay = noon.toLocalDate().toEpochDay().toInt(),
                merchantId = merchants.named(merchant),
                merchantRaw = merchant.uppercase(),
                categoryId = null,
                source = TransactionSource.NOTIFICATION,
                status = TransactionStatus.CONFIRMED,
                reviewReason = null,
                reviewedAtUtcMillis = null,
                confidence = 1f,
                parserId = "google-wallet",
                parserVersion = 1,
                sourcePackage = "com.google.android.apps.walletnfcrel",
                sourceKey = "$merchant-$daysAgo",
                cardLabel = "Récompenses Visa",
                cardLast4 = "0000",
                note = null,
                accountId = accountId,
            ),
        )
    }

    private fun noonOf(daysAgo: Long) = LocalDate.now(ZONE).minusDays(daysAgo).atTime(12, 0)

    private companion object {
        val ZONE: ZoneId = ZoneId.of("America/Toronto")
    }
}
