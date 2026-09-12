// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.jaytech64.depenses.core.database.DepensesDatabase
import io.github.jaytech64.depenses.core.testing.inMemoryDatabase
import io.github.jaytech64.depenses.parsing.ParseResult
import io.github.jaytech64.depenses.parsing.ParserRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Clock
import java.time.ZoneId
import javax.inject.Provider

/**
 * The guard, not the sweep: what stops a re-parse from running on every launch.
 *
 * [ReparseSweepTest] proves the re-reading is correct. This proves it happens
 * exactly once per parser change, which is the difference between a repair and a
 * full scan of the raw log every time the app opens.
 */
@RunWith(RobolectricTestRunner::class)
class CaptureCatchUpTest {
    /** A real scope: DataStore never runs its writes in a [kotlinx.coroutines.test.TestScope]. */
    private val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private lateinit var database: DepensesDatabase
    private lateinit var storeFile: File
    private lateinit var settings: SettingsRepository

    @Before
    fun open() {
        database = inMemoryDatabase()
        storeFile = File.createTempFile("settings", ".preferences_pb").apply { delete() }
        settings = SettingsRepository(PreferenceDataStoreFactory.create(scope = storeScope) { storeFile })
    }

    @After
    fun close() {
        storeScope.cancel()
        storeFile.delete()
        database.close()
    }

    @Test
    fun `the first run records the parser line-up it read history with`() =
        runTest {
            catchUp().resweepIfParsersChanged()

            assertNotNull(settings.lastParserFingerprint.first())
        }

    @Test
    fun `a launch that changes no parser does no work`() =
        runTest {
            val catchUp = catchUp()
            catchUp.resweepIfParsersChanged()

            assertEquals(0, catchUp.resweepIfParsersChanged())
        }

    /**
     * The fingerprint carries the version, so a bump alone is a change — the
     * parser list, its packages and its behaviour can all be identical otherwise.
     */
    @Test
    fun `bumping a parser is a change worth a sweep`() =
        runTest {
            catchUp().resweepIfParsersChanged()
            val before = settings.lastParserFingerprint.first()

            catchUp(version = 2).resweepIfParsersChanged()

            assertEquals("wallet:1", before)
            assertEquals("wallet:2", settings.lastParserFingerprint.first())
        }

    private fun catchUp(version: Int = 1): CaptureCatchUp {
        val registry =
            ParserRegistry(listOf(StubParser(PAY_PACKAGE, version, id = PARSER_ID) { ParseResult.NotApplicable }))
        return CaptureCatchUp(
            pipeline =
                ParsePipeline(
                    events = database.rawNotificationEventDao(),
                    transactions = database.transactionDao(),
                    registry = registry,
                    merchants = MerchantRepository(database.merchantDao()),
                    accounts = AccountRepository(database.accountDao()),
                    zoneId = Provider { ZONE },
                ),
            registry = registry,
            retention = RawLogRetention(database.rawNotificationEventDao(), Clock.systemUTC()),
            settings = settings,
        )
    }

    private companion object {
        const val PAY_PACKAGE = "com.example.pay"
        const val PARSER_ID = "wallet"
        val ZONE: ZoneId = ZoneId.of("America/Toronto")
    }
}
