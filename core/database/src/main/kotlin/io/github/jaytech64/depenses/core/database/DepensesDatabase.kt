package io.github.jaytech64.depenses.core.database

import androidx.room.Database
import androidx.room.RoomDatabase

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
        const val VERSION: Int = 1

        /**
         * Schema 1 is the initial schema, so there is nothing to migrate yet.
         * Migrations are listed here rather than passed at the call site so that
         * adding one cannot be forgotten in a second place.
         */
        val MIGRATIONS: Array<androidx.room.migration.Migration> = emptyArray()

        const val NAME: String = "depenses.db"
    }
}
