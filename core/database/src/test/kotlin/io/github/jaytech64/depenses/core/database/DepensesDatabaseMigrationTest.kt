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

    /**
     * The purge is the one migration that deletes data the user can never get
     * back, so it is tested for what it spares as much as for what it removes.
     */
    @Test
    fun `migrating to schema 4 deletes every notification no parser claimed`() {
        helper.createDatabase(NAME, 3).use { database ->
            database.insertRawEvent(postedAtUtcMillis = FIRST_POST)
            database.insertRawEvent(postedAtUtcMillis = FIRST_POST, sourcePackage = "ch.protonmail.android")
            database.insertRawEvent(postedAtUtcMillis = RE_POST, sourcePackage = "com.android.systemui")
        }

        val migrated = helper.runMigrationsAndValidate(NAME, 4, true, *DepensesDatabase.MIGRATIONS)

        assertEquals(1, migrated.rawEventCount())
        assertEquals(WALLET, migrated.singleRawEventPackage())
    }

    @Test
    fun `the purge leaves the ledger alone`() {
        helper.createDatabase(NAME, 3).use { database ->
            database.insertRawEvent(postedAtUtcMillis = FIRST_POST, sourcePackage = "ch.protonmail.android")
            database.insertTransaction()
        }

        val migrated = helper.runMigrationsAndValidate(NAME, 4, true, *DepensesDatabase.MIGRATIONS)

        assertEquals(0, migrated.rawEventCount())
        assertEquals(1, migrated.count("expense_transaction"))
    }

    /**
     * An `ALTER TABLE` is the migration most likely to be waved through unread,
     * and the one that silently empties a ledger when it is wrong.
     */
    @Test
    fun `migrating to schema 5 adds the review columns without touching the ledger`() {
        helper.createDatabase(NAME, 4).use { database ->
            database.insertTransaction()
        }

        val migrated = helper.runMigrationsAndValidate(NAME, 5, true, *DepensesDatabase.MIGRATIONS)

        assertEquals(1, migrated.count("expense_transaction"))
        assertEquals(6554, migrated.singleInt("SELECT amountMinor FROM expense_transaction"))
        assertEquals(0, migrated.singleInt("SELECT COUNT(*) FROM expense_transaction WHERE reviewReason IS NOT NULL"))
        assertEquals(
            0,
            migrated.singleInt("SELECT COUNT(*) FROM expense_transaction WHERE reviewedAtUtcMillis IS NOT NULL"),
        )
    }

    /**
     * The column the retention sweep writes, and the one the re-parse sweep reads
     * to know a payload is gone. A row that still holds its text must arrive on
     * schema 6 with a null here, or a parser fix would skip history it can repair.
     */
    @Test
    fun `migrating to schema 6 marks nothing as already redacted`() {
        helper.createDatabase(NAME, 5).use { database ->
            database.insertRawEvent(postedAtUtcMillis = FIRST_POST)
        }

        val migrated = helper.runMigrationsAndValidate(NAME, 6, true, *DepensesDatabase.MIGRATIONS)

        assertEquals(1, migrated.rawEventCount())
        assertEquals(
            0,
            migrated.singleInt("SELECT COUNT(*) FROM raw_notification_event WHERE redactedAtUtcMillis IS NOT NULL"),
        )
        assertEquals(
            1,
            migrated.singleInt("SELECT COUNT(*) FROM raw_notification_event WHERE title IS NOT NULL"),
        )
    }

    /**
     * The one migration that runs on a phone full of real purchases with a new
     * table to fill: every account already seen must arrive as one account, with its
     * purchases pointing at it, and nothing may stop being an expense. It runs
     * against schema 7's own names, which schema 8 later renamed.
     */
    @Test
    fun `migrating to schema 7 turns each card already seen into one card`() {
        helper.createDatabase(NAME, 6).use { database ->
            database.insertTransaction(sourceKey = "tag-1", cardLabel = "Carte d'accès Desrives", cardLast4 = "0000")
            database.insertTransaction(sourceKey = "tag-2", cardLabel = "Carte d'accès Desrives", cardLast4 = "0000")
            database.insertTransaction(sourceKey = "tag-3", cardLabel = "Récompenses Visa", cardLast4 = "7373")
            database.insertTransaction(sourceKey = "manual-1", source = "MANUAL", cardLast4 = null)
        }

        val migrated = helper.runMigrationsAndValidate(NAME, 7, true, *DepensesDatabase.MIGRATIONS)

        assertEquals(2, migrated.count("card"))
        assertEquals(3, migrated.singleInt("SELECT COUNT(*) FROM expense_transaction WHERE cardId IS NOT NULL"))
        assertEquals(3, migrated.singleInt("SELECT COUNT(*) FROM expense_transaction WHERE paymentMethod = 'CARD'"))
        assertEquals(
            2,
            migrated.singleInt(
                "SELECT COUNT(*) FROM expense_transaction t JOIN card c ON c.id = t.cardId " +
                    "WHERE c.last4 = '0000'",
            ),
        )
        assertEquals(4, migrated.singleInt("SELECT COUNT(*) FROM expense_transaction WHERE kind = 'EXPENSE'"))
        assertEquals(
            0,
            migrated.singleInt(
                "SELECT COUNT(*) FROM expense_transaction WHERE source = 'MANUAL' AND cardId IS NOT NULL",
            ),
        )
    }

    /**
     * The rename with real data in it: every card becomes an account, entries that
     * said cash point at the cash account, and nothing loses the account it was on.
     */
    @Test
    fun `migrating to schema 8 turns cards into accounts, cash included`() {
        helper.createDatabase(NAME, 7).use { database ->
            database.execSQL(
                "INSERT INTO card (id, name, issuerLabel, last4, origin) " +
                    "VALUES (1, 'Visa perso', 'Récompenses Visa', '4821', 'WALLET')",
            )
            database.insertSchema7Transaction(sourceKey = "card-1", paymentMethod = "CARD", cardId = 1)
            database.insertSchema7Transaction(sourceKey = "cash-1", paymentMethod = "CASH", cardId = null)
            database.insertSchema7Transaction(sourceKey = "bare-1", paymentMethod = null, cardId = null)
        }

        val migrated = helper.runMigrationsAndValidate(NAME, 8, true, *DepensesDatabase.MIGRATIONS)

        assertEquals(3, migrated.count("expense_transaction"))
        assertEquals(2, migrated.count("account"))
        assertEquals(
            1,
            migrated.singleInt("SELECT COUNT(*) FROM account WHERE type = 'CARD' AND name = 'Visa perso'"),
        )
        assertEquals(1, migrated.singleInt("SELECT COUNT(*) FROM account WHERE type = 'CASH'"))
        assertEquals(
            1,
            migrated.singleInt(
                "SELECT COUNT(*) FROM expense_transaction t JOIN account a ON a.id = t.accountId " +
                    "WHERE a.type = 'CASH'",
            ),
        )
        assertEquals(
            1,
            migrated.singleInt(
                "SELECT COUNT(*) FROM expense_transaction t JOIN account a ON a.id = t.accountId " +
                    "WHERE a.last4 = '4821'",
            ),
        )
        assertEquals(1, migrated.singleInt("SELECT COUNT(*) FROM expense_transaction WHERE accountId IS NULL"))
        assertEquals(0, migrated.singleInt("SELECT COUNT(*) FROM expense_transaction WHERE toAccountId IS NOT NULL"))
        assertEquals(3, migrated.singleInt("SELECT COUNT(*) FROM expense_transaction WHERE kind = 'EXPENSE'"))
    }

    @Test
    fun `migrating to schema 9 leaves every account without an opening balance`() {
        helper.createDatabase(NAME, 8).use { database ->
            database.execSQL(
                "INSERT INTO account (name, type, issuerLabel, last4, origin) " +
                    "VALUES ('Visa perso', 'CARD', 'Récompenses Visa', '4821', 'WALLET')",
            )
            database.execSQL(
                "INSERT INTO account (name, type, issuerLabel, last4, origin) " +
                    "VALUES (NULL, 'CASH', NULL, NULL, 'USER')",
            )
        }

        val migrated = helper.runMigrationsAndValidate(NAME, 9, true, *DepensesDatabase.MIGRATIONS)

        assertEquals(2, migrated.count("account"))
        assertEquals(2, migrated.singleInt("SELECT COUNT(*) FROM account WHERE openingBalanceMinor IS NULL"))
    }

    @Test
    fun `migrating to schema 10 keeps every account where it was shown`() {
        helper.createDatabase(NAME, 9).use { database ->
            listOf(
                "(NULL, 'CARD', 'Mastercard', '0977', 'WALLET')",
                "('visa Desrives', 'CARD', 'Récompenses Visa', '4821', 'WALLET')",
                "(NULL, 'CASH', NULL, NULL, 'USER')",
                "('Chèques', 'BANK', NULL, NULL, 'USER')",
            ).forEach { values ->
                database.execSQL("INSERT INTO account (name, type, issuerLabel, last4, origin) VALUES $values")
            }
        }

        val migrated = helper.runMigrationsAndValidate(NAME, 10, true, *DepensesDatabase.MIGRATIONS)

        val shown =
            migrated.query("SELECT COALESCE(name, issuerLabel, type) FROM account ORDER BY sortOrder").use { cursor ->
                generateSequence { if (cursor.moveToNext()) cursor.getString(0) else null }.toList()
            }
        assertEquals(listOf("Chèques", "visa Desrives", "CASH", "Mastercard"), shown)
        assertEquals(4, migrated.singleInt("SELECT COUNT(DISTINCT sortOrder) FROM account"))
    }

    private fun SupportSQLiteDatabase.insertSchema7Transaction(
        sourceKey: String,
        paymentMethod: String?,
        cardId: Long?,
    ) = execSQL(
        "INSERT INTO expense_transaction " +
            "(rawEventId, amountMinor, currency, occurredAtUtcMillis, zoneId, localDateEpochDay, merchantId, " +
            "merchantRaw, categoryId, source, status, confidence, parserId, parserVersion, sourcePackage, " +
            "sourceKey, cardLabel, cardLast4, note, kind, paymentMethod, cardId) " +
            "VALUES (NULL, 6554, 'CAD', ?, 'America/Toronto', 20707, NULL, 'MARCHE ST DAVID', NULL, " +
            "'NOTIFICATION', 'CONFIRMED', 1.0, 'google-wallet', 1, ?, ?, NULL, '4821', NULL, 'EXPENSE', ?, ?)",
        arrayOf<Any?>(FIRST_POST, WALLET, sourceKey, paymentMethod, cardId),
    )

    private fun SupportSQLiteDatabase.insertRawEvent(
        postedAtUtcMillis: Long,
        sourcePackage: String = WALLET,
        orIgnore: Boolean = false,
    ) = execSQL(
        "INSERT ${if (orIgnore) "OR IGNORE " else ""}INTO raw_notification_event " +
            "(sourcePackage, notificationKey, postedAtUtcMillis, deviceLocale, title, text, " +
            "bigText, subText, isGroupSummary, extrasJson, parseState, parsedByVersion) " +
            "VALUES (?, ?, ?, ?, ?, ?, NULL, NULL, 0, '{}', 'PENDING', NULL)",
        arrayOf<Any>(sourcePackage, KEY, postedAtUtcMillis, "fr-CA", "MARCHE ST JOSEPH  1234", "23,42 $ avec"),
    )

    private fun SupportSQLiteDatabase.insertTransaction(
        sourceKey: String = "tag-1",
        source: String = "NOTIFICATION",
        cardLabel: String? = null,
        cardLast4: String? = "6262",
    ) = execSQL(
        "INSERT INTO expense_transaction " +
            "(rawEventId, amountMinor, currency, occurredAtUtcMillis, zoneId, localDateEpochDay, " +
            "merchantId, merchantRaw, categoryId, source, status, confidence, parserId, parserVersion, " +
            "sourcePackage, sourceKey, cardLabel, cardLast4, note) " +
            "VALUES (NULL, 6554, 'CAD', ?, 'America/Toronto', 20707, NULL, 'MARCHE ST DAVID', NULL, " +
            "?, 'CONFIRMED', 1.0, 'google-wallet', 1, ?, ?, ?, ?, NULL)",
        arrayOf<Any?>(FIRST_POST, source, WALLET, sourceKey, cardLabel, cardLast4),
    )

    private fun SupportSQLiteDatabase.singleRawEventPackage(): String =
        query("SELECT sourcePackage FROM raw_notification_event").use { cursor ->
            cursor.moveToFirst()
            cursor.getString(0)
        }

    private fun SupportSQLiteDatabase.rawEventCount(): Int = count("raw_notification_event")

    private fun SupportSQLiteDatabase.singleInt(sql: String): Int =
        query(sql).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

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
