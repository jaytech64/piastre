// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.feature.ledger

import androidx.lifecycle.SavedStateHandle
import io.github.jaytech64.depenses.core.data.AccountRepository
import io.github.jaytech64.depenses.core.data.CategoryRepository
import io.github.jaytech64.depenses.core.data.LocalDateTicker
import io.github.jaytech64.depenses.core.data.MerchantRepository
import io.github.jaytech64.depenses.core.data.TransactionRepository
import io.github.jaytech64.depenses.core.database.AccountEntity
import io.github.jaytech64.depenses.core.database.CategoryEntity
import io.github.jaytech64.depenses.core.database.DepensesDatabase
import io.github.jaytech64.depenses.core.database.ExpenseTransactionEntity
import io.github.jaytech64.depenses.core.database.MerchantEntity
import io.github.jaytech64.depenses.core.model.AccountOrigin
import io.github.jaytech64.depenses.core.model.AccountType
import io.github.jaytech64.depenses.core.model.Category
import io.github.jaytech64.depenses.core.model.Money
import io.github.jaytech64.depenses.core.model.TransactionKind
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Provider

/** The in-memory database and helpers the ledger view model tests share. */
abstract class LedgerTest {
    protected lateinit var database: DepensesDatabase
    protected lateinit var savedState: SavedStateHandle
    protected lateinit var viewModel: LedgerViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        database = inMemoryDatabase()
        savedState = SavedStateHandle()
        viewModel = ledgerViewModel(savedState)
    }

    protected fun ledgerViewModel(state: SavedStateHandle) =
        LedgerViewModel(
            repository = transactionRepository(),
            accounts = AccountRepository(database.accountDao()),
            categories = categoryRepository(),
            merchants = MerchantRepository(database.merchantDao()),
            ticker = LocalDateTicker(Provider { ZONE }),
            savedState = state,
        )

    protected fun transactionRepository() =
        TransactionRepository(
            database = database,
            transactions = database.transactionDao(),
            merchants = MerchantRepository(database.merchantDao()),
            categories = categoryRepository(),
            zoneId = Provider { ZONE },
        )

    protected fun categoryRepository() =
        CategoryRepository(
            database.categoryDao(),
            database.merchantDao(),
            database.accountDao(),
        )

    @After
    fun tearDown() {
        database.close()
        Dispatchers.resetMain()
    }

    /**
     * The state once the database has answered, awaited rather than polled.
     *
     * Turbine's timeouts run on the test scheduler's virtual clock, which jumps the
     * moment nothing is queued — so a wait that starts before the flow has been
     * subscribed times out instantly rather than waiting at all.
     */
    protected suspend fun settled(): LedgerUiState = viewModel.uiState.first { !it.loading }

    protected suspend fun insert(
        amountMinor: Long,
        day: LocalDate,
        status: TransactionStatus = TransactionStatus.CONFIRMED,
        merchantId: Long? = null,
        merchantRaw: String = "MARCHE ST JOSEPH  1234",
        kind: TransactionKind = TransactionKind.EXPENSE,
        accountId: Long? = null,
        toAccountId: Long? = null,
        categoryId: Long? = null,
    ) = database.transactionDao().insertOrUpdate(
        ExpenseTransactionEntity(
            rawEventId = null,
            amountMinor = amountMinor,
            currency = Money.CAD,
            occurredAtUtcMillis =
                day
                    .atTime(NOON, 0)
                    .atZone(ZONE)
                    .toInstant()
                    .toEpochMilli(),
            zoneId = ZONE.id,
            localDateEpochDay = day.toEpochDay().toInt(),
            merchantId = merchantId,
            merchantRaw = merchantRaw,
            categoryId = categoryId,
            source = TransactionSource.NOTIFICATION,
            status = status,
            reviewReason = null,
            reviewedAtUtcMillis = null,
            confidence = 1f,
            parserId = "google-wallet",
            parserVersion = 1,
            sourcePackage = "com.google.android.apps.walletnfcrel",
            sourceKey =
                listOf(
                    amountMinor,
                    day.toEpochDay(),
                    status,
                    accountId,
                    toAccountId,
                    categoryId,
                    merchantId,
                    merchantRaw,
                ).joinToString("-", prefix = "tag-"),
            cardLabel = "Récompenses Visa",
            cardLast4 = "0000",
            note = null,
            kind = kind,
            accountId = accountId,
            toAccountId = toAccountId,
        ),
    )

    protected suspend fun account(name: String) =
        database.accountDao().insert(
            AccountEntity(
                name = name,
                type = AccountType.BANK,
                issuerLabel = null,
                last4 = null,
                origin = AccountOrigin.USER,
            ),
        )

    protected suspend fun merchant(name: String) =
        database.merchantDao().insert(MerchantEntity(displayName = name, normalisedName = name.uppercase()))

    protected suspend fun category(name: String) =
        database.categoryDao().insert(CategoryEntity(name = name, normalisedName = name.uppercase()))

    protected companion object {
        val ZONE: ZoneId = ZoneId.of("America/Toronto")
        val TODAY: LocalDate = LocalDate.now(ZONE)
        val YESTERDAY: LocalDate = TODAY.minusDays(1)
        val LAST_MONTH: LocalDate = TODAY.minusMonths(1)

        /**
         * A second day inside the current month whichever day the suite runs on:
         * yesterday belongs to the month before it on the first of every month.
         */
        val ANOTHER_DAY_THIS_MONTH: LocalDate = if (TODAY.dayOfMonth == 1) TODAY.plusDays(1) else YESTERDAY
        const val NOON = 12
    }
}
