// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The first migration this app has ever had, run against a populated database.
 *
 * Getting it wrong on a phone holding a month of captures is unrecoverable, and
 * the schema-2 index is the interesting case: a database written under schema 1
 * can already contain the rows the new index forbids.
 */
@RunWith(RobolectricTestRunner::class)
class DepensesDatabaseMigrationTest {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            DepensesDatabase::class.java,
        )

    @Test
    fun `migrating to schema 2 collapses a replayed post and keeps a genuine re-post`() {
        helper.createDatabase(NAME, 1).use { database ->
            database.insertRawEvent(postedAtUtcMillis = FIRST_POST)
            database.insertRawEvent(postedAtUtcMillis = FIRST_POST)
            database.insertRawEvent(postedAtUtcMillis = RE_POST)
        }

        val migrated = helper.runMigrationsAndValidate(NAME, 2, true, *DepensesDatabase.MIGRATIONS)

        assertEquals(2, migrated.rawEventCount())
    }

    @Test
    fun `after migrating, replaying the same post again changes nothing`() {
        helper.createDatabase(NAME, 1).use { database ->
            database.insertRawEvent(postedAtUtcMillis = FIRST_POST)
        }

        val migrated = helper.runMigrationsAndValidate(NAME, 2, true, *DepensesDatabase.MIGRATIONS)
        migrated.insertRawEvent(postedAtUtcMillis = FIRST_POST, orIgnore = true)

        assertEquals(1, migrated.rawEventCount())
    }

    /**
     * What a phone that installed the first release and then updated actually does.
     * Each migration is correct in isolation above; this is the only test that
     * proves the chain lands on the schema Room expects.
     */
    @Test
    fun `a database from the first release migrates all the way to the current schema`() {
        helper.createDatabase(NAME, 1).use { database ->
            database.insertRawEvent(postedAtUtcMillis = FIRST_POST)
            database.insertRawEvent(postedAtUtcMillis = RE_POST)
            database.execSQL(
                "INSERT INTO merchant (displayName, normalisedName) VALUES ('Marché St-Joseph', 'MARCHE ST JOSEPH')",
            )
        }

        val migrated =
            helper.runMigrationsAndValidate(
                NAME,
                DepensesDatabase.VERSION,
                true,
                *DepensesDatabase.MIGRATIONS,
            )

        assertEquals(2, migrated.rawEventCount())
        assertEquals(1, migrated.count("merchant"))
    }

    private fun SupportSQLiteDatabase.insertRawEvent(
        postedAtUtcMillis: Long,
        orIgnore: Boolean = false,
    ) = execSQL(
        "INSERT ${if (orIgnore) "OR IGNORE " else ""}INTO raw_notification_event " +
            "(sourcePackage, notificationKey, postedAtUtcMillis, deviceLocale, title, text, " +
            "bigText, subText, isGroupSummary, extrasJson, parseState, parsedByVersion) " +
            "VALUES (?, ?, ?, ?, ?, ?, NULL, NULL, 0, '{}', 'PENDING', NULL)",
        arrayOf<Any>(WALLET, KEY, postedAtUtcMillis, "fr-CA", "MARCHE ST JOSEPH  1234", "23,42 $ avec"),
    )

    private fun SupportSQLiteDatabase.rawEventCount(): Int = count("raw_notification_event")

    private fun SupportSQLiteDatabase.count(table: String): Int =
        query("SELECT COUNT(*) FROM $table").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    private companion object {
        const val NAME = "migration-test.db"
        const val WALLET = "com.google.android.apps.walletnfcrel"
        const val KEY = "0|com.google.android.apps.walletnfcrel|1001|tag-1|10123"
        const val FIRST_POST = 1_788_740_166_564L
        const val RE_POST = 1_788_740_169_732L
    }
}
