// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.feature.ledger

import androidx.lifecycle.SavedStateHandle
import io.github.jaytech64.depenses.core.data.AccountRepository
import io.github.jaytech64.depenses.core.data.CategoryRepository
import io.github.jaytech64.depenses.core.data.LocalDateTicker
import io.github.jaytech64.depenses.core.data.MerchantRepository
import io.github.jaytech64.depenses.core.data.TransactionEditor
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
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Provider

/** The in-memory database and helpers the ledger view model tests share. */
abstract class LedgerTest {
    protected lateinit var database: DepensesDatabase
    protected lateinit var savedState: SavedStateHandle
    protected lateinit var viewModel: LedgerViewModel

    /** What time it is for the ledger: noon on [TODAY], unless a test moves it. */
    protected open val clock: Clock = Clock.fixed(TODAY.atTime(NOON, 0).atZone(ZONE).toInstant(), ZONE)

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
            categoryGroups = categoryGroupRepository(),
            merchants = MerchantRepository(database.merchantDao()),
            editor =
                TransactionEditor(
                    database,
                    database.transactionDao(),
                    MerchantRepository(database.merchantDao()),
                    categoryRepository(),
                    Clock.systemUTC(),
                ),
            recurring = database.recurringRepository(clock, ZONE),
            ticker = LocalDateTicker(clock, Provider { ZONE }),
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

    protected fun categoryGroupRepository() = database.categoryGroupRepository()

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
        currency: String = Money.CAD,
        note: String? = null,
        hour: Int = NOON,
    ) = database.transactionDao().insertOrUpdate(
        ExpenseTransactionEntity(
            rawEventId = null,
            amountMinor = amountMinor,
            currency = currency,
            occurredAtUtcMillis =
                day
                    .atTime(hour, 0)
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
                    note,
                ).joinToString("-", prefix = "tag-"),
            cardLabel = "Récompenses Visa",
            cardLast4 = "0000",
            note = note,
            kind = kind,
            accountId = accountId,
            toAccountId = toAccountId,
        ),
    )

    protected suspend fun account(
        name: String,
        type: AccountType = AccountType.CARD,
    ) = database.accountDao().insert(
        AccountEntity(
            name = name,
            type = type,
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

        /** Mid-month, so yesterday is in this month and tomorrow is too, whenever the suite runs. */
        val TODAY: LocalDate = LocalDate.of(2026, 9, 16)
        val YESTERDAY: LocalDate = TODAY.minusDays(1)
        val TOMORROW: LocalDate = TODAY.plusDays(1)
        val LAST_MONTH: LocalDate = TODAY.minusMonths(1)
        val TWO_MONTHS_AGO: LocalDate = TODAY.minusMonths(2)
        val ANOTHER_DAY_THIS_MONTH: LocalDate = YESTERDAY
        const val NOON = 12
    }
}
