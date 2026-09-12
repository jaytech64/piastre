// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        RawNotificationEventEntity::class,
        ExpenseTransactionEntity::class,
        MerchantEntity::class,
        MerchantAliasEntity::class,
        CardEntity::class,
    ],
    version = DepensesDatabase.VERSION,
    exportSchema = true,
)
abstract class DepensesDatabase : RoomDatabase() {
    abstract fun rawNotificationEventDao(): RawNotificationEventDao

    abstract fun transactionDao(): TransactionDao

    abstract fun merchantDao(): MerchantDao

    abstract fun reportDao(): ReportDao

    abstract fun cardDao(): CardDao

    companion object {
        const val VERSION: Int = 7

        /**
         * Migrations are listed here rather than passed at the call site so that
         * adding one cannot be forgotten in a second place.
         */
        val MIGRATIONS: Array<Migration> =
            arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)

        const val NAME: String = "depenses.db"
    }
}

/**
 * Schema 2 makes the raw log reject a replayed post.
 *
 * The duplicate sweep has to run first: a database captured under schema 1 can
 * already hold rows the new index forbids, and creating a unique index over
 * existing duplicates fails outright, which would leave the app unable to open
 * its own data. Keeping the lowest id preserves the row the parser has already
 * been pointed at.
 */
private val MIGRATION_1_2 =
    object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                DELETE FROM raw_notification_event
                WHERE id NOT IN (
                    SELECT MIN(id) FROM raw_notification_event
                    GROUP BY sourcePackage, notificationKey, postedAtUtcMillis
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS " +
                    "index_raw_notification_event_sourcePackage_notificationKey_postedAtUtcMillis " +
                    "ON raw_notification_event (sourcePackage, notificationKey, postedAtUtcMillis)",
            )
        }
    }

/**
 * Schema 3 makes a merchant's normalised name its identity.
 *
 * Nothing has written to the merchant table before this version, so there is
 * nothing to collapse first — but the sweep is kept anyway, because a migration
 * that assumes an empty table is a migration that fails the one time it is wrong.
 */
private val MIGRATION_2_3 =
    object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                DELETE FROM merchant
                WHERE id NOT IN (SELECT MIN(id) FROM merchant GROUP BY normalisedName)
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS index_merchant_normalisedName " +
                    "ON merchant (normalisedName)",
            )
        }
    }

/**
 * Schema 4 deletes raw events this app should never have kept.
 *
 * Until this version the listener recorded every notification on the device,
 * content included, and only decided afterwards that a mail subject was not a
 * payment. The listener now filters by package before it reads the bundle, which
 * stops the collection — this stops the retention.
 *
 * The package list is written out here rather than read from the parser registry
 * on purpose. A migration is a historical record of what one upgrade did; wiring
 * it to a list that grows would silently change what an old upgrade means, and a
 * parser added next year must not be able to reach back and alter this delete.
 */
private val MIGRATION_3_4 =
    object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "DELETE FROM raw_notification_event WHERE sourcePackage NOT IN (?)",
                arrayOf(WALLET_PACKAGE),
            )
        }
    }

/** The only package any parser claimed when schema 4 was written. */
private const val WALLET_PACKAGE = "com.google.android.apps.walletnfcrel"

/**
 * Schema 5 gives the review inbox the two columns it needs: why the parser
 * declined, and whether a human has since ruled.
 *
 * Existing rows get nulls rather than guesses. A row already waiting for review
 * was parsed before the column existed, and inventing MISSING_MERCHANT for it
 * would put a sentence on screen the parser never said. A null review time is
 * equally honest: nothing has been ruled on yet, so the parser still owns
 * every one of these rows.
 */
private val MIGRATION_4_5 =
    object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE expense_transaction ADD COLUMN reviewReason TEXT DEFAULT NULL")
            db.execSQL("ALTER TABLE expense_transaction ADD COLUMN reviewedAtUtcMillis INTEGER DEFAULT NULL")
        }
    }

/**
 * Schema 6 records when a raw row's content was stripped by the retention sweep.
 *
 * Existing rows get null, which is the truth: nothing has been stripped yet, and
 * the first sweep after this upgrade will redact whatever is already over 90 days
 * old. Backfilling a redaction time for rows that still hold their payload would
 * make the re-parse sweep skip history it can still repair.
 */
private val MIGRATION_5_6 =
    object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE raw_notification_event ADD COLUMN redactedAtUtcMillis INTEGER DEFAULT NULL")
        }
    }

/**
 * Schema 7 adds cards, income and how a transaction was paid.
 *
 * Every card already seen in a notification becomes a card row and its purchases
 * point at it, so the Cards screen opens on the cards you actually use rather
 * than on an empty list. Every existing row stays an expense, which keeps every
 * total the app has ever shown exactly as it was.
 */
private val MIGRATION_6_7 =
    object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `card` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`name` TEXT, `issuerLabel` TEXT, `last4` TEXT, `origin` TEXT NOT NULL)",
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_card_issuerLabel_last4` ON `card` (`issuerLabel`, `last4`)",
            )
            db.execSQL(
                """
                INSERT INTO card (issuerLabel, last4, origin)
                SELECT DISTINCT cardLabel, cardLast4, 'WALLET' FROM expense_transaction
                WHERE source = 'NOTIFICATION' AND cardLast4 IS NOT NULL
                """.trimIndent(),
            )
            db.execSQL("ALTER TABLE expense_transaction ADD COLUMN kind TEXT NOT NULL DEFAULT 'EXPENSE'")
            db.execSQL("ALTER TABLE expense_transaction ADD COLUMN paymentMethod TEXT DEFAULT NULL")
            db.execSQL("ALTER TABLE expense_transaction ADD COLUMN cardId INTEGER DEFAULT NULL")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_expense_transaction_cardId` ON `expense_transaction` (`cardId`)",
            )
            db.execSQL(
                """
                UPDATE expense_transaction SET
                    cardId = (
                        SELECT c.id FROM card c
                        WHERE c.origin = 'WALLET'
                          AND c.issuerLabel IS expense_transaction.cardLabel
                          AND c.last4 = expense_transaction.cardLast4
                    ),
                    paymentMethod = 'CARD'
                WHERE source = 'NOTIFICATION' AND cardLast4 IS NOT NULL
                """.trimIndent(),
            )
        }
    }
