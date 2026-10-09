// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.core.data

import io.github.jaytech64.piastre.core.database.PiastreDatabase
import io.github.jaytech64.piastre.core.database.RawNotificationEventEntity
import io.github.jaytech64.piastre.core.model.AliasOrigin
import io.github.jaytech64.piastre.core.model.Money
import io.github.jaytech64.piastre.core.model.ParseState
import io.github.jaytech64.piastre.core.model.ReviewReason
import io.github.jaytech64.piastre.core.model.TransactionStatus
import io.github.jaytech64.piastre.core.testing.inMemoryDatabase
import io.github.jaytech64.piastre.parsing.GoogleWalletParser
import io.github.jaytech64.piastre.parsing.ParserRegistry
import io.github.jaytech64.piastre.parsing.RawNotification
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
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import javax.inject.Provider

/**
 * The review inbox against a real database, driven by a real notification the
 * parser genuinely refuses to accept.
 *
 * Hand-built NEEDS_REVIEW rows would prove nothing about the case that matters:
 * that a ruling survives Wallet re-posting the very notification it was about.
 */
@RunWith(RobolectricTestRunner::class)
class ReviewRepositoryTest {
    private lateinit var database: PiastreDatabase
    private lateinit var capture: NotificationCapture
    private lateinit var review: ReviewRepository

    @Before
    fun open() {
        database = inMemoryDatabase()
        val merchants = MerchantRepository(database.merchantDao())
        val pipeline =
            ParsePipeline(
                events = database.rawNotificationEventDao(),
                transactions = database.transactionDao(),
                registry = ParserRegistry(listOf(GoogleWalletParser())),
                merchants = merchants,
                accounts = AccountRepository(database.accountDao()),
                zoneId = Provider { ZONE },
            )
        capture = NotificationCapture(RawEventRepository(database.rawNotificationEventDao()), pipeline)
        review =
            ReviewRepository(
                transactions = database.transactionDao(),
                merchants = merchants,
                registry = ParserRegistry(listOf(GoogleWalletParser())),
                clock = Clock.fixed(Instant.ofEpochMilli(RULED_AT), ZONE),
            )
    }

    @After
    fun close() = database.close()

    @Test
    fun `a notification with no merchant queues for review with the parser's reason`() =
        runTest {
            captureUnnamedMerchant()

            val queued = review.queue().first().single()

            assertEquals(ReviewReason.MISSING_MERCHANT, queued.reason)
            assertEquals(10_200L, queued.transaction.amount.minor)
            assertNull(queued.transaction.displayMerchant)
        }

    @Test
    fun `the queue carries the notification text verbatim`() =
        runTest {
            captureUnnamedMerchant()

            val queued = review.queue().first().single()

            assertEquals(UNNAMED_TEXT, queued.rawText)
            assertEquals(Instant.ofEpochMilli(POSTED_AT), queued.postedAt)
        }

    @Test
    fun `confirming moves the purchase into the ledger and empties the queue`() =
        runTest {
            captureUnnamedMerchant()
            review.confirm(reviewedId())

            assertEquals(emptyList<Any>(), review.queue().first())
            assertEquals(1, database.transactionDao().countByStatus(TransactionStatus.CONFIRMED).first())
        }

    @Test
    fun `dismissing keeps the row and its evidence but takes it out of the ledger`() =
        runTest {
            captureUnnamedMerchant()
            review.dismiss(reviewedId())

            assertEquals(0, database.transactionDao().countByStatus(TransactionStatus.CONFIRMED).first())
            assertEquals(1, database.transactionDao().countByStatus(TransactionStatus.DISMISSED).first())
            assertEquals(1, database.rawNotificationEventDao().count().first())
        }

    @Test
    fun `correcting the merchant teaches the app the name for next time`() =
        runTest {
            captureNamed(merchant = "SQ *CAFE OLIMPICO 12")
            val id = reviewedId()

            review.correct(id, Money(625, Money.CAD), "Café Olimpico")

            val corrected = database.transactionDao().byId(id)
            assertNotNull(corrected?.merchantId)
            assertEquals(625L, corrected?.amountMinor)
            assertEquals(TransactionStatus.CONFIRMED, corrected?.status)
            assertEquals(
                "Café Olimpico",
                database.merchantDao().byExactAlias("SQ *CAFE OLIMPICO 12")?.displayName,
            )
        }

    /**
     * The failure this whole column exists to prevent: Wallet re-posts the same
     * purchase minutes later, the pipeline re-parses it, and a decision already
     * made is silently replaced by the parser's original reading.
     */
    @Test
    fun `a re-post cannot undo a ruling`() =
        runTest {
            captureUnnamedMerchant()
            val id = reviewedId()
            review.correct(id, Money(9_999, Money.CAD), "Dépanneur du coin")

            captureUnnamedMerchant(postedAt = POSTED_AT + RE_POST_DELAY_MILLIS)

            val afterRepost = database.transactionDao().byId(id)
            assertEquals(9_999L, afterRepost?.amountMinor)
            assertEquals(TransactionStatus.CONFIRMED, afterRepost?.status)
            assertEquals(RULED_AT, afterRepost?.reviewedAtUtcMillis)
            assertEquals(emptyList<Any>(), review.queue().first())
        }

    @Test
    fun `a ruling clears the reason it was queued for`() =
        runTest {
            captureUnnamedMerchant()
            val id = reviewedId()

            review.confirm(id)

            assertNull(database.transactionDao().byId(id)?.reviewReason)
            assertEquals(RULED_AT, database.transactionDao().byId(id)?.reviewedAtUtcMillis)
        }

    private suspend fun reviewedId(): Long =
        review
            .queue()
            .first()
            .single()
            .transaction.id

    private suspend fun captureUnnamedMerchant(postedAt: Long = POSTED_AT) =
        capture(title = null, text = UNNAMED_TEXT, postedAt = postedAt)

    private suspend fun captureNamed(merchant: String) =
        capture(title = merchant, text = "6,25 $ avec Carte", postedAt = POSTED_AT)

    private suspend fun capture(
        title: String?,
        text: String,
        postedAt: Long,
    ) = capture.capture(
        RawNotification(
            sourcePackage = WALLET,
            notificationKey = KEY,
            postedAtUtcMillis = postedAt,
            deviceLocale = "fr-CA",
            title = title,
            text = text,
        ),
        extrasJson = "{}",
    )

    private companion object {
        val ZONE: ZoneId = ZoneId.of("America/Toronto")
        const val WALLET = "com.google.android.apps.walletnfcrel"
        const val KEY = "0|com.google.android.apps.walletnfcrel|1001|tag-77|10348"

        /** Amount and account read, merchant absent — the parser's MISSING_MERCHANT case. */
        const val UNNAMED_TEXT = "102,00 $ avec Carte d'accès Desrives ••0000"
        const val POSTED_AT = 1_788_740_166_564L
        const val RE_POST_DELAY_MILLIS = 1_800_000L
        const val RULED_AT = 1_788_750_000_000L
    }
}
