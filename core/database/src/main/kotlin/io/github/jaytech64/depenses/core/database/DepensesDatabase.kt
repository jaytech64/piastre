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
        AccountEntity::class,
        CategoryEntity::class,
        CategoryGroupEntity::class,
        RecurringTransactionEntity::class,
    ],
    version = DepensesDatabase.VERSION,
    exportSchema = true,
)
abstract class DepensesDatabase : RoomDatabase() {
    abstract fun rawNotificationEventDao(): RawNotificationEventDao

    abstract fun transactionDao(): TransactionDao

    abstract fun merchantDao(): MerchantDao

    abstract fun reportDao(): ReportDao

    abstract fun accountDao(): AccountDao

    abstract fun categoryDao(): CategoryDao

    abstract fun recurringTransactionDao(): RecurringTransactionDao

    companion object {
        const val VERSION: Int = 14

        /**
         * Migrations are listed here rather than passed at the call site so that
         * adding one cannot be forgotten in a second place.
         */
        val MIGRATIONS: Array<Migration> =
            arrayOf(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7,
                MIGRATION_7_8,
                MIGRATION_8_9,
                MIGRATION_9_10,
                MIGRATION_10_11,
                MIGRATION_11_12,
                MIGRATION_12_13,
                MIGRATION_13_14,
            )

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
 *
 * Schema 8 renamed this table to `account`; the SQL here stays as it was, because
 * a migration must build the schema of its own version and no other.
 */
private val MIGRATION_6_7 =
    object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `card` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`name` TEXT, `issuerLabel` TEXT, `last4` TEXT, `origin` TEXT NOT NULL)",
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_card_issuerLabel_last4` " +
                    "ON `card` (`issuerLabel`, `last4`)",
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
                "CREATE INDEX IF NOT EXISTS `index_expense_transaction_cardId` " +
                    "ON `expense_transaction` (`cardId`)",
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

/**
 * Schema 8 turns cards into accounts, and lets a transaction move between two.
 *
 * Cash stops being a column on the transaction and becomes an account like any
 * other, which is what allows a transfer between a wallet and a card. Entries
 * that said cash are re-pointed at the new cash account, so nothing loses how it
 * was paid.
 *
 * Both tables are rebuilt rather than altered: this app supports Android 12,
 * whose SQLite cannot drop a column, and `paymentMethod` has to go.
 */
private val MIGRATION_7_8 =
    object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.createAccountTable()
            db.copyCardsIntoAccounts()
            db.rebuildTransactionsOntoAccounts()
            db.execSQL("DROP TABLE card")
        }
    }

private fun SupportSQLiteDatabase.createAccountTable() {
    execSQL(
        "CREATE TABLE IF NOT EXISTS `account` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`name` TEXT, `type` TEXT NOT NULL, `issuerLabel` TEXT, `last4` TEXT, `origin` TEXT NOT NULL)",
    )
    execSQL(
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_account_issuerLabel_last4` " +
            "ON `account` (`issuerLabel`, `last4`)",
    )
}

/**
 * Every card becomes an account of its own kind, keeping its id so the ledger's
 * rows still point at it, and a cash account is added whether or not anything
 * said cash — the list and the form offer it from the start rather than later.
 */
private fun SupportSQLiteDatabase.copyCardsIntoAccounts() {
    execSQL(
        """
        INSERT INTO account (id, name, type, issuerLabel, last4, origin)
        SELECT id, name, 'CARD', issuerLabel, last4, origin FROM card
        """.trimIndent(),
    )
    execSQL(
        """
        INSERT INTO account (name, type, issuerLabel, last4, origin)
        VALUES (NULL, 'CASH', NULL, NULL, 'USER')
        """.trimIndent(),
    )
}

/** The table without `paymentMethod`, with its cash rows pointed at the cash account. */
private fun SupportSQLiteDatabase.rebuildTransactionsOntoAccounts() {
    execSQL(
        "CREATE TABLE IF NOT EXISTS `expense_transaction_new` " +
            "(`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `rawEventId` INTEGER, " +
            "`amountMinor` INTEGER NOT NULL, `currency` TEXT NOT NULL, " +
            "`occurredAtUtcMillis` INTEGER NOT NULL, `zoneId` TEXT NOT NULL, " +
            "`localDateEpochDay` INTEGER NOT NULL, `merchantId` INTEGER, `merchantRaw` TEXT, " +
            "`categoryId` INTEGER, `source` TEXT NOT NULL, `status` TEXT NOT NULL, " +
            "`reviewReason` TEXT, `reviewedAtUtcMillis` INTEGER, `confidence` REAL NOT NULL, " +
            "`parserId` TEXT, `parserVersion` INTEGER, `sourcePackage` TEXT NOT NULL, " +
            "`sourceKey` TEXT NOT NULL, `cardLabel` TEXT, `cardLast4` TEXT, `note` TEXT, " +
            "`kind` TEXT NOT NULL DEFAULT 'EXPENSE', `accountId` INTEGER, `toAccountId` INTEGER)",
    )
    execSQL(
        """
        INSERT INTO expense_transaction_new (
            id, rawEventId, amountMinor, currency, occurredAtUtcMillis, zoneId, localDateEpochDay,
            merchantId, merchantRaw, categoryId, source, status, reviewReason, reviewedAtUtcMillis,
            confidence, parserId, parserVersion, sourcePackage, sourceKey, cardLabel, cardLast4,
            note, kind, accountId, toAccountId
        )
        SELECT
            id, rawEventId, amountMinor, currency, occurredAtUtcMillis, zoneId, localDateEpochDay,
            merchantId, merchantRaw, categoryId, source, status, reviewReason, reviewedAtUtcMillis,
            confidence, parserId, parserVersion, sourcePackage, sourceKey, cardLabel, cardLast4,
            note, kind,
            CASE WHEN paymentMethod = 'CASH'
                 THEN (SELECT id FROM account WHERE type = 'CASH' ORDER BY id LIMIT 1)
                 ELSE cardId END,
            NULL
        FROM expense_transaction
        """.trimIndent(),
    )
    execSQL("DROP TABLE expense_transaction")
    execSQL("ALTER TABLE expense_transaction_new RENAME TO expense_transaction")
    recreateTransactionIndices()
}

/**
 * Schema 9 lets an account start from a balance.
 *
 * Every existing account gets null rather than zero. Nobody has said what any of
 * them held, and zero would be a claim — one an import would then have to treat
 * as a balance you chose and refuse to fill in.
 */
private val MIGRATION_8_9 =
    object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE account ADD COLUMN openingBalanceMinor INTEGER DEFAULT NULL")
        }
    }

/**
 * Schema 10 lets you put your accounts in an order of your own.
 *
 * Every account starts where schema 9 showed it — named ones A to Z, then the
 * ones still waiting for a name — so an upgrade moves nothing until you do. That
 * is the order you have already been looking at, not a guess, which is why it is
 * written in rather than left null. The ordering is a literal copy of the one
 * `AccountDao.allWithCounts` used at schema 9, so a later change to that query
 * cannot reach back and change what this upgrade meant.
 */
private val MIGRATION_9_10 =
    object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE account ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0")
            val ids =
                db
                    .query("SELECT id FROM account ORDER BY name IS NULL, name COLLATE NOCASE, issuerLabel, last4, id")
                    .use { cursor ->
                        generateSequence { if (cursor.moveToNext()) cursor.getLong(0) else null }.toList()
                    }
            ids.forEachIndexed { position, id ->
                db.execSQL("UPDATE account SET sortOrder = ? WHERE id = ?", arrayOf<Any>(position, id))
            }
        }
    }

/**
 * Schema 11 adds categories, and lets a merchant or an account give one to what
 * arrives there.
 *
 * `expense_transaction.categoryId` has existed, nullable, since schema 1, and
 * nothing has ever written anything but null to it. It is cleared anyway: a
 * migration assumes nothing about the data, and any stray value would point at a
 * category that does not exist.
 */
private val MIGRATION_10_11 =
    object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `category` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`name` TEXT NOT NULL, `normalisedName` TEXT NOT NULL)",
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_category_normalisedName` ON `category` (`normalisedName`)",
            )
            db.execSQL("ALTER TABLE merchant ADD COLUMN categoryId INTEGER DEFAULT NULL")
            db.execSQL("ALTER TABLE account ADD COLUMN categoryId INTEGER DEFAULT NULL")
            db.execSQL("UPDATE expense_transaction SET categoryId = NULL WHERE categoryId IS NOT NULL")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_expense_transaction_categoryId` " +
                    "ON `expense_transaction` (`categoryId`)",
            )
        }
    }

/**
 * Schema 12 adds recurring transactions, and lets a transaction say which one it
 * belongs to. No transaction belongs to one yet, so the new column stays null.
 */
private val MIGRATION_11_12 =
    object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `recurring_transaction` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `uuid` TEXT NOT NULL, " +
                    "`amountMinor` INTEGER NOT NULL, `currency` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
                    "`merchantId` INTEGER, `merchantRaw` TEXT, `accountId` INTEGER, `toAccountId` INTEGER, " +
                    "`categoryId` INTEGER, `note` TEXT, `rhythm` TEXT NOT NULL, `firstEpochDay` INTEGER NOT NULL, " +
                    "`lastEpochDay` INTEGER, `nextDueEpochDay` INTEGER)",
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_recurring_transaction_uuid` " +
                    "ON `recurring_transaction` (`uuid`)",
            )
            db.execSQL("ALTER TABLE expense_transaction ADD COLUMN recurringId INTEGER DEFAULT NULL")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_expense_transaction_recurringId` " +
                    "ON `expense_transaction` (`recurringId`)",
            )
        }
    }

/**
 * Schema 13 changes no table: it makes the typed transaction a schedule started
 * from into that schedule's first row, the way every schedule made from now on
 * starts. It becomes a recurring row keyed on the schedule and its day, as the
 * rows the schedule writes are.
 *
 * The key is spelled out here rather than taken from the code that writes it
 * today, so a later change to that code cannot reach back and change what this
 * upgrade meant. A row whose new key another row already holds is left as it
 * was rather than breaking the unique index, which would leave the app unable
 * to open its own data.
 */
private val MIGRATION_12_13 =
    object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                UPDATE expense_transaction
                SET source = 'RECURRING',
                    sourcePackage = 'recurring',
                    sourceKey = (
                        SELECT r.uuid FROM recurring_transaction r WHERE r.id = expense_transaction.recurringId
                    ) || ':' || date(localDateEpochDay * 86400, 'unixepoch')
                WHERE source = 'MANUAL'
                  AND recurringId IN (SELECT id FROM recurring_transaction)
                  AND NOT EXISTS (
                      SELECT 1 FROM expense_transaction other
                      WHERE other.sourcePackage = 'recurring'
                        AND other.sourceKey = (
                            SELECT r.uuid FROM recurring_transaction r WHERE r.id = expense_transaction.recurringId
                        ) || ':' || date(expense_transaction.localDateEpochDay * 86400, 'unixepoch')
                  )
                """.trimIndent(),
            )
        }
    }

/**
 * Schema 14 lets categories be arranged in groups. Every existing category is
 * left in none: nobody has said where any of them belongs.
 */
private val MIGRATION_13_14 =
    object : Migration(13, 14) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `category_group` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`name` TEXT NOT NULL, `normalisedName` TEXT NOT NULL)",
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_category_group_normalisedName` " +
                    "ON `category_group` (`normalisedName`)",
            )
            db.execSQL("ALTER TABLE category ADD COLUMN groupId INTEGER DEFAULT NULL")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_category_groupId` ON `category` (`groupId`)")
        }
    }

private fun SupportSQLiteDatabase.recreateTransactionIndices() {
    execSQL(
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_expense_transaction_sourcePackage_sourceKey` " +
            "ON `expense_transaction` (`sourcePackage`, `sourceKey`)",
    )
    execSQL(
        "CREATE INDEX IF NOT EXISTS `index_expense_transaction_localDateEpochDay` " +
            "ON `expense_transaction` (`localDateEpochDay`)",
    )
    execSQL(
        "CREATE INDEX IF NOT EXISTS `index_expense_transaction_merchantId` " +
            "ON `expense_transaction` (`merchantId`)",
    )
    execSQL(
        "CREATE INDEX IF NOT EXISTS `index_expense_transaction_status` " +
            "ON `expense_transaction` (`status`)",
    )
    execSQL(
        "CREATE INDEX IF NOT EXISTS `index_expense_transaction_accountId` " +
            "ON `expense_transaction` (`accountId`)",
    )
    execSQL(
        "CREATE INDEX IF NOT EXISTS `index_expense_transaction_toAccountId` " +
            "ON `expense_transaction` (`toAccountId`)",
    )
}
