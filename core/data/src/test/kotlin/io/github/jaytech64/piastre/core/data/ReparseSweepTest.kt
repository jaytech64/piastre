// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.core.data

import io.github.jaytech64.piastre.core.database.PiastreDatabase
import io.github.jaytech64.piastre.core.model.Money
import io.github.jaytech64.piastre.core.model.ParseState
import io.github.jaytech64.piastre.core.testing.inMemoryDatabase
import io.github.jaytech64.piastre.parsing.IgnoreReason
import io.github.jaytech64.piastre.parsing.NotificationParser
import io.github.jaytech64.piastre.parsing.ParseResult
import io.github.jaytech64.piastre.parsing.ParserRegistry
import io.github.jaytech64.piastre.parsing.RawNotification
import io.github.jaytech64.piastre.parsing.TransactionDraft
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import javax.inject.Provider

/**
 * The raw log's whole justification, tested: a parser that was wrong is fixed,
 * and the purchases it already misread come out right.
 *
 * A stub parser rather than the real one, because the interesting version is the
 * *broken* one and the real parser is not allowed to have a broken version
 * checked in. What is under test is the sweep, not any particular payment format.
 */
@RunWith(RobolectricTestRunner::class)
class ReparseSweepTest {
    private lateinit var database: PiastreDatabase

    @Before
    fun open() {
        database = inMemoryDatabase()
    }

    @After
    fun close() = database.close()

    @Test
    fun `a parser fix repairs the amount it misread, and leaves your note alone`() =
        runTest {
            captureWith(misreading(version = 1))
            val misread =
                database
                    .transactionDao()
                    .all()
                    .first()
                    .single()
            database.transactionDao().update(misread.copy(note = NOTE))

            val reread = pipelineWith(reading(version = 2)).resweep()

            val repaired =
                database
                    .transactionDao()
                    .all()
                    .first()
                    .single()
            assertEquals(1, reread)
            assertEquals(MISREAD_MINOR, misread.amountMinor)
            assertEquals(AMOUNT_MINOR, repaired.amountMinor)
            assertEquals(NOTE, repaired.note)
            assertEquals(2, repaired.parserVersion)
        }

    /** Otherwise every launch would drag the whole raw log back through the parser. */
    @Test
    fun `a second sweep at the same version finds nothing left to re-read`() =
        runTest {
            captureWith(misreading(version = 1))
            val pipeline = pipelineWith(reading(version = 2))

            assertEquals(1, pipeline.resweep())
            assertEquals(0, pipeline.resweep())
        }

    /**
     * A ruling is evidence about the purchase, not a guess for a later parser to
     * improve on — the same rule the review inbox relies on against Wallet's
     * re-posts, held here against a version bump.
     */
    @Test
    fun `a row a human has ruled on is not reopened by a parser fix`() =
        runTest {
            captureWith(misreading(version = 1))
            val ruled =
                database
                    .transactionDao()
                    .all()
                    .first()
                    .single()
            database.transactionDao().update(ruled.copy(reviewedAtUtcMillis = RULED_AT))

            pipelineWith(reading(version = 2)).resweep()

            assertEquals(
                MISREAD_MINOR,
                database
                    .transactionDao()
                    .all()
                    .first()
                    .single()
                    .amountMinor,
            )
        }

    /**
     * The case an in-place overwrite cannot reach: the fix is that this was never
     * a purchase. Without the sweep clearing up after itself the phantom stays in
     * the ledger for good, and no screen would ever explain where it came from.
     */
    @Test
    fun `a payload the fixed parser no longer claims loses the row it wrongly created`() =
        runTest {
            captureWith(misreading(version = 1))
            assertEquals(1, database.transactionDao().count().first())

            pipelineWith(ignoring(version = 2)).resweep()

            assertEquals(0, database.transactionDao().count().first())
            assertEquals(1, database.rawNotificationEventDao().byState(ParseState.IGNORED).size)
        }

    /** A fix to how identity is derived must move the row, not fork it. */
    @Test
    fun `a fix that changes the source key leaves one purchase, not two`() =
        runTest {
            captureWith(misreading(version = 1))

            pipelineWith(reading(version = 2, sourceKey = "wallet-tag-corrected")).resweep()

            val transactions = database.transactionDao().all().first()
            assertEquals(1, transactions.size)
            assertEquals("wallet-tag-corrected", transactions.single().sourceKey)
        }

    /** A parser that never moved must not have its rows re-read by one that did. */
    @Test
    fun `bumping one parser leaves another parser's history alone`() =
        runTest {
            captureWith(misreading(version = 1))
            val untouched =
                ParsePipeline(
                    events = database.rawNotificationEventDao(),
                    transactions = database.transactionDao(),
                    registry =
                        ParserRegistry(
                            listOf(StubParser(OTHER_PACKAGE, version = 9) { ParseResult.NotApplicable }),
                        ),
                    merchants = MerchantRepository(database.merchantDao()),
                    accounts = AccountRepository(database.accountDao()),
                    zoneId = Provider { ZONE },
                )

            assertEquals(0, untouched.resweep())
            assertEquals(
                MISREAD_MINOR,
                database
                    .transactionDao()
                    .all()
                    .first()
                    .single()
                    .amountMinor,
            )
        }

    /** A payload no parser could read yet is swept too, or a new parser never sees it. */
    @Test
    fun `a payload nothing could read is offered to the parser that can`() =
        runTest {
            captureWith(StubParser(PAY_PACKAGE, version = 1) { ParseResult.NotApplicable })
            assertEquals(1, database.rawNotificationEventDao().byState(ParseState.UNPARSED).size)

            val reread = pipelineWith(reading(version = 2)).resweep()

            assertEquals(1, reread)
            assertEquals(
                AMOUNT_MINOR,
                database
                    .transactionDao()
                    .all()
                    .first()
                    .single()
                    .amountMinor,
            )
            assertTrue(database.rawNotificationEventDao().byState(ParseState.UNPARSED).isEmpty())
        }

    /**
     * The interaction that would have been catastrophic and silent.
     *
     * After 90 days the retention sweep strips a row's payload. A parser handed
     * that empty row concludes it was never a payment, and the tidy-up after a
     * re-parse would then delete the purchase it produced back when the text was
     * still there — one parser bump erasing every purchase older than 90 days.
     */
    @Test
    fun `a purchase whose notification has been redacted survives a parser fix`() =
        runTest {
            captureWith(misreading(version = 1))
            RawLogRetention(
                database.rawNotificationEventDao(),
                Clock.fixed(Instant.ofEpochMilli(POSTED_AT).plus(Duration.ofDays(91)), ZoneOffset.UTC),
            ).purgeExpired()

            val reread = pipelineWith(readingRealText(version = 2)).resweep()

            assertEquals(0, reread)
            assertEquals(1, database.transactionDao().count().first())
            assertEquals(
                MISREAD_MINOR,
                database
                    .transactionDao()
                    .all()
                    .first()
                    .single()
                    .amountMinor,
            )
        }

    private suspend fun captureWith(parser: NotificationParser) =
        NotificationCapture(RawEventRepository(database.rawNotificationEventDao()), pipelineWith(parser))
            .capture(PAYMENT, NO_EXTRAS)

    private fun pipelineWith(parser: NotificationParser) =
        ParsePipeline(
            events = database.rawNotificationEventDao(),
            transactions = database.transactionDao(),
            registry = ParserRegistry(listOf(parser)),
            merchants = MerchantRepository(database.merchantDao()),
            accounts = AccountRepository(database.accountDao()),
            zoneId = Provider { ZONE },
        )

    /** The bug: the cents are read as the whole amount. */
    private fun misreading(version: Int) = StubParser(PAY_PACKAGE, version) { parsed(MISREAD_MINOR, SOURCE_KEY) }

    private fun reading(
        version: Int,
        sourceKey: String = SOURCE_KEY,
    ) = StubParser(PAY_PACKAGE, version) { parsed(AMOUNT_MINOR, sourceKey) }

    /** Like a real parser: no text, no payment. */
    private fun readingRealText(version: Int) =
        StubParser(PAY_PACKAGE, version) { notification ->
            notification.text
                ?.let { parsed(AMOUNT_MINOR, SOURCE_KEY) }
                ?: ParseResult.Ignored(IgnoreReason.NOT_A_PAYMENT)
        }

    private fun ignoring(version: Int) =
        StubParser(PAY_PACKAGE, version) { ParseResult.Ignored(IgnoreReason.NOT_A_PAYMENT) }

    private fun parsed(
        minor: Long,
        sourceKey: String,
    ) = ParseResult.Parsed(
        draft =
            TransactionDraft(
                sourcePackage = PAY_PACKAGE,
                sourceKey = sourceKey,
                merchantRaw = MERCHANT,
                amount = Money(minor, Money.CAD),
                cardLast4 = "6262",
                occurredAtUtcMillis = POSTED_AT,
            ),
        confidence = 1f,
    )

    private companion object {
        const val PAY_PACKAGE = "com.example.pay"
        const val OTHER_PACKAGE = "com.example.other"
        const val SOURCE_KEY = "wallet-tag"
        const val MERCHANT = "MARCHE ST DAVID"
        const val NOTE = "remboursé par Marie"
        const val NO_EXTRAS = "{}"
        const val POSTED_AT = 1_788_740_166_564L
        const val RULED_AT = 1_788_740_999_999L

        const val AMOUNT_MINOR = 4_500L
        const val MISREAD_MINOR = 45L

        val ZONE: ZoneId = ZoneId.of("America/Toronto")

        val PAYMENT =
            RawNotification(
                sourcePackage = PAY_PACKAGE,
                notificationKey = "0|com.example.pay|7|wallet-tag|10500",
                postedAtUtcMillis = POSTED_AT,
                deviceLocale = "fr-CA",
                title = MERCHANT,
                text = "45,00 $ avec Carte d'accès Desrives ••6262",
            )
    }
}
