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
    ],
    version = DepensesDatabase.VERSION,
    exportSchema = true,
)
abstract class DepensesDatabase : RoomDatabase() {
    abstract fun rawNotificationEventDao(): RawNotificationEventDao

    abstract fun transactionDao(): TransactionDao

    abstract fun merchantDao(): MerchantDao

    abstract fun reportDao(): ReportDao

    companion object {
        const val VERSION: Int = 3

        /**
         * Migrations are listed here rather than passed at the call site so that
         * adding one cannot be forgotten in a second place.
         */
        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)

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
