// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.core.data

import io.github.jaytech64.piastre.core.database.PiastreDatabase
import io.github.jaytech64.piastre.core.model.Money
import io.github.jaytech64.piastre.core.model.TransactionKind
import io.github.jaytech64.piastre.core.testing.inMemoryDatabase
import io.github.jaytech64.piastre.parsing.GoogleWalletParser
import io.github.jaytech64.piastre.parsing.ParserRegistry
import io.github.jaytech64.piastre.parsing.RawNotification
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import javax.inject.Provider

/** What a captured purchase can say about the notifications behind it, fed by real Wallet payloads. */
@RunWith(RobolectricTestRunner::class)
class CaptureHistoryTest {
    private lateinit var database: PiastreDatabase
    private lateinit var capture: NotificationCapture
    private lateinit var repository: TransactionRepository
    private lateinit var editor: TransactionEditor

    @Before
    fun open() {
        database = inMemoryDatabase()
        val merchants = MerchantRepository(database.merchantDao())
        val categories = CategoryRepository(database.categoryDao(), database.merchantDao(), database.accountDao())
        capture =
            NotificationCapture(
                RawEventRepository(database.rawNotificationEventDao(), Provider { ZONE }),
                ParsePipeline(
                    events = database.rawNotificationEventDao(),
                    transactions = database.transactionDao(),
                    registry = ParserRegistry(listOf(GoogleWalletParser())),
                    merchants = merchants,
                    accounts = AccountRepository(database.accountDao()),
                    zoneId = Provider { ZONE },
                ),
            )
        repository =
            TransactionRepository(
                database = database,
                transactions = database.transactionDao(),
                merchants = merchants,
                categories = categories,
                zoneId = Provider { ZONE },
            )
        editor =
            TransactionEditor(
                database,
                database.transactionDao(),
                merchants,
                categories,
                Clock.fixed(Instant.ofEpochMilli(EDITED_AT), ZONE),
            )
    }

    @After
    fun close() = database.close()

    @Test
    fun `the ledger shows a re-posted purchase at the time of its first capture`() =
        runTest {
            captureCoffee()
            captureCoffee(postedAt = POSTED_AT + RE_POST_DELAY_MILLIS)

            assertEquals(Instant.ofEpochMilli(POSTED_AT), repository.byId(capturedId())?.occurredAt)
        }

    @Test
    fun `a captured purchase's history lists every post, oldest first, even after an edit`() =
        runTest {
            captureCoffee(postedAt = POSTED_AT + RE_POST_DELAY_MILLIS)
            captureCoffee()
            val id = capturedId()
            editor.edit(
                id,
                Money(625, Money.CAD),
                TransactionKind.EXPENSE,
                MERCHANT_RAW,
                Instant.ofEpochMilli(POSTED_AT),
                note = "Avec un croissant",
                accountId = null,
                toAccountId = null,
                categoryId = null,
                reach = null,
            )
            captureCoffee(postedAt = POSTED_AT + 2 * RE_POST_DELAY_MILLIS)

            assertEquals(
                listOf(POSTED_AT, POSTED_AT + RE_POST_DELAY_MILLIS, POSTED_AT + 2 * RE_POST_DELAY_MILLIS)
                    .map(Instant::ofEpochMilli),
                repository.captureHistory(id)?.posts,
            )
        }

    @Test
    fun `a purchase whose raw log was cleared has a history with nothing in it`() =
        runTest {
            captureCoffee()
            val id = capturedId()

            database.rawNotificationEventDao().deleteAll()

            assertEquals(emptyList<Instant>(), repository.captureHistory(id)?.posts)
        }

    @Test
    fun `a typed purchase has no capture history`() =
        runTest {
            val typed =
                repository.addManual(
                    Money(1_250, Money.CAD),
                    "Dépanneur",
                    Instant.ofEpochMilli(POSTED_AT),
                    note = null,
                    kind = TransactionKind.EXPENSE,
                    accountId = null,
                    toAccountId = null,
                    categoryId = null,
                    reach = null,
                )

            assertNull(repository.captureHistory(typed))
        }

    private suspend fun capturedId(): Long =
        repository
            .confirmed()
            .first()
            .single()
            .id

    private suspend fun captureCoffee(postedAt: Long = POSTED_AT) =
        capture.capture(
            RawNotification(
                sourcePackage = WALLET,
                notificationKey = KEY,
                postedAtUtcMillis = postedAt,
                deviceLocale = "fr-CA",
                title = MERCHANT_RAW,
                text = "6,25 $ avec Carte d'accès Desrives ••0000",
            ),
            extrasJson = "{}",
        )

    private companion object {
        val ZONE: ZoneId = ZoneId.of("America/Toronto")
        const val WALLET = "com.google.android.apps.walletnfcrel"
        const val KEY = "0|com.google.android.apps.walletnfcrel|1001|tag-77|10348"
        const val MERCHANT_RAW = "SQ *CAFE OLIMPICO 12"
        const val POSTED_AT = 1_788_740_166_564L
        const val RE_POST_DELAY_MILLIS = 1_800_000L
        const val EDITED_AT = 1_788_750_000_000L
    }
}
