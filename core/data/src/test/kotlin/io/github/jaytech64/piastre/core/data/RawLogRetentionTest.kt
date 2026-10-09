// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.core.data

import io.github.jaytech64.piastre.core.database.PiastreDatabase
import io.github.jaytech64.piastre.core.database.RawNotificationEventEntity
import io.github.jaytech64.piastre.core.model.ParseState
import io.github.jaytech64.piastre.core.testing.inMemoryDatabase
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
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * The sweep that keeps the most sensitive table in the app from holding
 * lock-screen text for ever.
 *
 * Tested for what it spares as much as for what it strips: this deletes data the
 * user can never get back, and the row it must not touch is the one no parser
 * could read.
 */
@RunWith(RobolectricTestRunner::class)
class RawLogRetentionTest {
    private lateinit var database: PiastreDatabase
    private lateinit var retention: RawLogRetention

    @Before
    fun open() {
        database = inMemoryDatabase()
        retention = RawLogRetention(database.rawNotificationEventDao(), Clock.fixed(NOW, ZoneOffset.UTC))
    }

    @After
    fun close() = database.close()

    @Test
    fun `a row past ninety days loses its content and keeps its skeleton`() =
        runTest {
            val id = insert(age = Duration.ofDays(91), state = ParseState.PARSED)

            assertEquals(1, retention.purgeExpired())

            val row = database.rawNotificationEventDao().byId(id)!!
            assertNull(row.title)
            assertNull(row.text)
            assertNull(row.bigText)
            assertNull(row.subText)
            assertEquals("{}", row.extrasJson)
            assertEquals(NOTIFICATION_KEY, row.notificationKey)
            assertEquals(ParseState.PARSED, row.parseState)
            assertNotNull(row.redactedAtUtcMillis)
        }

    @Test
    fun `a row inside ninety days is left alone`() =
        runTest {
            val id = insert(age = Duration.ofDays(89), state = ParseState.PARSED)

            assertEquals(0, retention.purgeExpired())

            val row = database.rawNotificationEventDao().byId(id)!!
            assertEquals(TITLE, row.title)
            assertNull(row.redactedAtUtcMillis)
        }

    /** The exemption: the only copy of a payload no parser has read yet. */
    @Test
    fun `an unread row is kept whole however old it is`() =
        runTest {
            val unparsed = insert(age = Duration.ofDays(400), state = ParseState.UNPARSED)
            val pending = insert(age = Duration.ofDays(400), state = ParseState.PENDING, key = "$NOTIFICATION_KEY-2")

            assertEquals(0, retention.purgeExpired())

            assertEquals(TITLE, database.rawNotificationEventDao().byId(unparsed)!!.title)
            assertEquals(TITLE, database.rawNotificationEventDao().byId(pending)!!.title)
        }

    @Test
    fun `an ignored row is stripped like any other read row`() =
        runTest {
            val id = insert(age = Duration.ofDays(91), state = ParseState.IGNORED)

            assertEquals(1, retention.purgeExpired())

            assertNull(database.rawNotificationEventDao().byId(id)!!.title)
        }

    /** Otherwise every launch would report stripping rows it stripped months ago. */
    @Test
    fun `a second sweep over the same rows reports nothing`() =
        runTest {
            insert(age = Duration.ofDays(91), state = ParseState.PARSED)
            retention.purgeExpired()

            assertEquals(0, retention.purgeExpired())
        }

    /**
     * The row has to survive its own redaction, or the shade handing back a very
     * old notification would turn it into a second purchase.
     */
    @Test
    fun `a redacted row still refuses its own replay`() =
        runTest {
            insert(age = Duration.ofDays(91), state = ParseState.PARSED)
            retention.purgeExpired()

            insert(age = Duration.ofDays(91), state = ParseState.PARSED)

            assertEquals(1, database.rawNotificationEventDao().count().first())
        }

    private suspend fun insert(
        age: Duration,
        state: ParseState,
        key: String = NOTIFICATION_KEY,
    ): Long =
        database.rawNotificationEventDao().insert(
            RawNotificationEventEntity(
                sourcePackage = PACKAGE,
                notificationKey = key,
                postedAtUtcMillis = NOW.minus(age).toEpochMilli(),
                deviceLocale = "fr-CA",
                title = TITLE,
                text = TEXT,
                bigText = TEXT,
                subText = TEXT,
                isGroupSummary = false,
                extrasJson = EXTRAS,
                parseState = state,
                parsedByVersion = 1,
            ),
        )

    private companion object {
        const val PACKAGE = "com.google.android.apps.walletnfcrel"
        const val NOTIFICATION_KEY = "0|com.google.android.apps.walletnfcrel|1001|tag-1|10123"
        const val TITLE = "MARCHE ST DAVID  2998"
        const val TEXT = "16,09 $ avec Carte d'accès Desrives ••6262"
        const val EXTRAS = """{"android.title":"MARCHE ST DAVID  2998"}"""
        val NOW: Instant = Instant.parse("2026-09-09T20:00:00Z")
    }
}
