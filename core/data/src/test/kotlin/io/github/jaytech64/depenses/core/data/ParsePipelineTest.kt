// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.data

import io.github.jaytech64.depenses.core.database.DepensesDatabase
import io.github.jaytech64.depenses.core.model.Money
import io.github.jaytech64.depenses.core.model.ParseState
import io.github.jaytech64.depenses.core.model.TransactionStatus
import io.github.jaytech64.depenses.core.testing.inMemoryDatabase
import io.github.jaytech64.depenses.parsing.FixtureCorpus
import io.github.jaytech64.depenses.parsing.GoogleWalletParser
import io.github.jaytech64.depenses.parsing.ParserRegistry
import io.github.jaytech64.depenses.parsing.RawNotification
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.ZoneId
import javax.inject.Provider

/**
 * Replays two weeks of real captures through the real pipeline into a real
 * SQLite, because the interesting failures live between the layers.
 *
 * The parser's own suite already proves it reads a payment correctly. What it
 * cannot prove is that the ledger collapses Wallet's re-posts the same way the
 * parser does — that answer depends on a unique index, an upsert and a source
 * key agreeing with one another.
 */
@RunWith(RobolectricTestRunner::class)
class ParsePipelineTest {
    private lateinit var database: DepensesDatabase
    private lateinit var pipeline: ParsePipeline
    private lateinit var capture: NotificationCapture
    private lateinit var rawEvents: RawEventRepository
    private lateinit var merchants: MerchantRepository

    @Before
    fun open() {
        database = inMemoryDatabase()
        rawEvents = RawEventRepository(database.rawNotificationEventDao())
        merchants = MerchantRepository(database.merchantDao())
        pipeline =
            ParsePipeline(
                events = database.rawNotificationEventDao(),
                transactions = database.transactionDao(),
                registry = ParserRegistry(listOf(GoogleWalletParser())),
                merchants = merchants,
                zoneId = Provider { ZONE },
            )
        capture = NotificationCapture(rawEvents, pipeline)
    }

    @After
    fun close() = database.close()

    @Test
    fun `two weeks of captures become the purchases actually made`() =
        runTest {
            captureAll(corpus())

            assertEquals(DISTINCT_POSTS, database.rawNotificationEventDao().count().first())
            assertEquals(PURCHASES, database.transactionDao().count().first())
            assertEquals(TOTAL_MINOR, confirmedTotal())
        }

    /**
     * The two exports overlap: one purchase was still on the shade when the second
     * week began, so it appears in both files with the same tag and the same post
     * time. Eight purchases plus ten is therefore seventeen, not eighteen — which
     * is the answer only because identity comes from Wallet's tag. A content hash
     * or an insert per notification would both have produced a phantom purchase
     * here.
     */
    @Test
    fun `a purchase exported in both weeks is one purchase`() =
        runTest {
            captureAll(FixtureCorpus.load(WEEK_1))
            val afterWeekOne = database.transactionDao().count().first()

            captureAll(FixtureCorpus.load(WEEK_2))

            assertEquals(WEEK_1_PURCHASES, afterWeekOne)
            assertEquals(WEEK_1_PURCHASES + WEEK_2_PURCHASES - OVERLAP, database.transactionDao().count().first())
        }

    /** The listener replays the shade on every rebind, so this is the ordinary case. */
    @Test
    fun `replaying the entire corpus a second time changes nothing`() =
        runTest {
            captureAll(corpus())
            captureAll(corpus())

            assertEquals(DISTINCT_POSTS, database.rawNotificationEventDao().count().first())
            assertEquals(PURCHASES, database.transactionDao().count().first())
            assertEquals(TOTAL_MINOR, confirmedTotal())
        }

    /**
     * The corpus is a raw capture, so it contains the listener's own replays: 45
     * exported lines are 29 distinct posts. Collapsing those is the raw log's job,
     * before any parser sees them.
     */
    @Test
    fun `the corpus own replays never reach the raw log twice`() =
        runTest {
            captureAll(corpus())

            assertEquals(DISTINCT_POSTS, database.rawNotificationEventDao().count().first())
        }

    @Test
    fun `notifications the parser recognises and rejects leave no ledger row`() =
        runTest {
            captureAll(corpus())

            val dao = database.rawNotificationEventDao()
            val ignored = dao.byState(ParseState.IGNORED)

            assertEquals(IGNORED_POSTS, ignored.size)
            assertEquals(PARSED_POSTS, dao.byState(ParseState.PARSED).size)
            assertTrue("nothing should be left pending", dao.byState(ParseState.PENDING).isEmpty())
        }

    @Test
    fun `a notification no parser handles is kept rather than dropped`() =
        runTest {
            capture.capture(FOREIGN_NOTIFICATION, NO_EXTRAS)

            assertEquals(1, database.rawNotificationEventDao().count().first())
            assertEquals(0, database.transactionDao().count().first())
            assertEquals(1, database.rawNotificationEventDao().byState(ParseState.UNPARSED).size)
        }

    /** The case the inline path cannot see: recorded, then the process died. */
    @Test
    fun `the catch-up pass parses what the inline path never reached`() =
        runTest {
            corpus().forEach { rawEvents.record(it, NO_EXTRAS) }
            assertEquals(0, database.transactionDao().count().first())

            val drained = pipeline.drainPending()

            assertEquals(DISTINCT_POSTS, drained)
            assertEquals(PURCHASES, database.transactionDao().count().first())
            assertEquals(TOTAL_MINOR, confirmedTotal())
        }

    @Test
    fun `every purchase is recorded against the day it was made, locally`() =
        runTest {
            captureAll(corpus())

            val days =
                database
                    .transactionDao()
                    .all()
                    .first()
                    .map { it.localDateEpochDay }

            assertEquals(PURCHASES, days.size)
            assertTrue("every transaction needs a resolved local day", days.all { it > 0 })
        }

    @Test
    fun `every purchase resolves to a merchant, and branches of one chain share it`() =
        runTest {
            captureAll(corpus())

            val transactions = database.transactionDao().all().first()
            val resolved = transactions.mapNotNull { it.merchantId }

            assertEquals("every purchase needs a merchant", PURCHASES, resolved.size)
            assertEquals(MERCHANTS, merchants.all().first().size)
        }

    /**
     * The question "where does my money go" is about chains, not storefronts, so a
     * ranking that listed each branch separately would answer the wrong one.
     */
    @Test
    fun `the ranking sums the chain rather than the storefront`() =
        runTest {
            captureAll(corpus())

            val ranking = database.reportDao().topMerchants(0, MAX_DAY, Money.CAD, limit = MERCHANTS).first()

            assertEquals(MERCHANTS, ranking.size)
            assertEquals(TOTAL_MINOR, ranking.sumOf { it.totalMinor })
            assertTrue(
                "a ranking must be ordered",
                ranking.map { it.totalMinor } == ranking.map { it.totalMinor }.sortedDescending(),
            )
        }

    @Test
    fun `a merchant seen again is the same merchant, not a second one`() =
        runTest {
            captureAll(corpus())
            val before = merchants.all().first().size

            captureAll(corpus())

            assertEquals(before, merchants.all().first().size)
        }

    private suspend fun captureAll(notifications: List<RawNotification>) =
        notifications.forEach { capture.capture(it, NO_EXTRAS) }

    private suspend fun confirmedTotal(): Long =
        database
            .transactionDao()
            .all()
            .first()
            .filter { it.status == TransactionStatus.CONFIRMED }
            .sumOf { it.amountMinor }

    private fun corpus(): List<RawNotification> = FixtureCorpus.load(WEEK_1) + FixtureCorpus.load(WEEK_2)

    private companion object {
        const val WEEK_1 = "wallet-week1.jsonl"
        const val WEEK_2 = "wallet-week2.jsonl"

        /** 45 exported lines, of which 16 are the listener replaying what it already had. */
        const val DISTINCT_POSTS = 29
        const val PARSED_POSTS = 21
        const val IGNORED_POSTS = 8

        const val PURCHASES = 17
        const val WEEK_1_PURCHASES = 8
        const val WEEK_2_PURCHASES = 10

        /** One purchase was captured in both export windows. */
        const val OVERLAP = 1

        const val TOTAL_MINOR = 43_543L

        /** Nine distinct chains across the two weeks, after branch numbers collapse. */
        const val MERCHANTS = 9
        const val MAX_DAY = 99_999
        const val NO_EXTRAS = "{}"
        val ZONE: ZoneId = ZoneId.of("America/Toronto")

        val FOREIGN_NOTIFICATION =
            RawNotification(
                sourcePackage = "com.example.chat",
                notificationKey = "0|com.example.chat|7|null|10500",
                postedAtUtcMillis = 1_788_740_166_564,
                deviceLocale = "fr-CA",
                title = "Someone",
                text = "sent you a message",
            )
    }
}
