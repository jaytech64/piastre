// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.jaytech64.piastre.core.database.AccountEntity
import io.github.jaytech64.piastre.core.database.CategoryEntity
import io.github.jaytech64.piastre.core.database.DepensesDatabase
import io.github.jaytech64.piastre.core.database.ExpenseTransactionEntity
import io.github.jaytech64.piastre.core.database.MerchantEntity
import io.github.jaytech64.piastre.core.database.RawNotificationEventEntity
import io.github.jaytech64.piastre.core.database.RecurringTransactionEntity
import io.github.jaytech64.piastre.core.model.AccountOrigin
import io.github.jaytech64.piastre.core.model.AccountType
import io.github.jaytech64.piastre.core.model.ParseState
import io.github.jaytech64.piastre.core.model.Rhythm
import io.github.jaytech64.piastre.core.model.TransactionKind
import io.github.jaytech64.piastre.core.model.TransactionSource
import io.github.jaytech64.piastre.core.model.TransactionStatus
import io.github.jaytech64.piastre.core.testing.inMemoryDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class DataErasureTest {
    private val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private lateinit var database: DepensesDatabase
    private lateinit var storeFile: File
    private lateinit var settings: SettingsRepository
    private lateinit var erasure: DataErasure

    @Before
    fun setUp() {
        database = inMemoryDatabase()
        storeFile = File.createTempFile("settings", ".preferences_pb").apply { delete() }
        settings = SettingsRepository(PreferenceDataStoreFactory.create(scope = storeScope) { storeFile })
        erasure = DataErasure(database, database.rawNotificationEventDao(), settings)
    }

    @After
    fun tearDown() {
        storeScope.cancel()
        storeFile.delete()
        database.close()
    }

    @Test
    fun `clearing the raw log keeps the ledger`() =
        runTest {
            populate()

            erasure.clearRawLog()

            assertEquals(0, database.rawNotificationEventDao().count().first())
            assertEquals(1, database.transactionDao().count().first())
        }

    @Test
    fun `erasing everything empties every table`() =
        runTest {
            populate()

            erasure.eraseEverything()

            assertEquals(0, database.rawNotificationEventDao().count().first())
            assertEquals(0, database.transactionDao().count().first())
            assertTrue(database.merchantDao().allOnce().isEmpty())
            assertTrue("the names you gave your accounts are data about you", database.accountDao().allOnce().isEmpty())
            assertTrue("so are the categories you named", database.categoryDao().allOnce().isEmpty())
            assertTrue("and what you pay every month", database.recurringTransactionDao().allOnce().isEmpty())
        }

    @Test
    fun `erasing everything drops the heartbeat, which is device activity too`() =
        runTest {
            settings.noteListenerEvent(NOW)
            assertNotNull(settings.lastListenerEventAt.first())

            erasure.eraseEverything()

            assertNull(settings.lastListenerEventAt.first())
        }

    @Test
    fun `erasing everything leaves the switches alone, because they are not data about you`() =
        runTest {
            settings.setCaptureEnabled(false)
            settings.setOnboardingCompleted()

            erasure.eraseEverything()

            assertEquals(false, settings.captureEnabled.first())
            assertEquals(true, settings.onboardingCompleted.first())
        }

    private suspend fun populate() {
        database.rawNotificationEventDao().insert(
            RawNotificationEventEntity(
                sourcePackage = "com.google.android.apps.walletnfcrel",
                notificationKey = "0|wallet|1|tag-1|10348",
                postedAtUtcMillis = NOW.toEpochMilli(),
                deviceLocale = "fr-CA",
                title = "MARCHE ST DAVID",
                text = "16,09 $",
                bigText = null,
                subText = null,
                isGroupSummary = false,
                extrasJson = "{}",
                parseState = ParseState.PARSED,
                parsedByVersion = 1,
            ),
        )
        database.categoryDao().insert(CategoryEntity(name = "Épicerie", normalisedName = "EPICERIE"))
        insertRent()
        database.accountDao().insert(
            AccountEntity(
                name = "Débit Desrives",
                type = AccountType.CARD,
                issuerLabel = null,
                last4 = "6262",
                origin = AccountOrigin.USER,
            ),
        )
        val merchantId =
            database.merchantDao().insert(
                MerchantEntity(displayName = "MARCHE ST DAVID", normalisedName = "MARCHE ST DAVID"),
            )
        database.transactionDao().insert(
            ExpenseTransactionEntity(
                rawEventId = null,
                amountMinor = 1_609,
                currency = "CAD",
                occurredAtUtcMillis = NOW.toEpochMilli(),
                zoneId = "America/Toronto",
                localDateEpochDay = 20_705,
                merchantId = merchantId,
                merchantRaw = "MARCHE ST DAVID",
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
                cardLabel = null,
                cardLast4 = "6262",
                note = null,
            ),
        )
    }

    private suspend fun insertRent() =
        database.recurringTransactionDao().insert(
            RecurringTransactionEntity(
                uuid = "5d1b7e1c",
                amountMinor = 145_000,
                currency = "CAD",
                kind = TransactionKind.EXPENSE,
                merchantId = null,
                merchantRaw = "Loyer",
                accountId = null,
                toAccountId = null,
                categoryId = null,
                note = null,
                rhythm = Rhythm.MONTHLY,
                firstEpochDay = 20_705,
                lastEpochDay = null,
                nextDueEpochDay = 20_735,
            ),
        )

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-09T14:00:00Z")
    }
}
