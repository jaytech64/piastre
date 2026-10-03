// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.database

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun database(
        @ApplicationContext context: Context,
    ): DepensesDatabase =
        Room
            .databaseBuilder(context, DepensesDatabase::class.java, DepensesDatabase.NAME)
            .addMigrations(*DepensesDatabase.MIGRATIONS)
            // A fresh install gets the cash account the 7→8 migration gives an
            // upgrade, so "Cash" is offered from the first purchase rather than
            // after the first time you name it.
            .addCallback(
                object : RoomDatabase.Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            "INSERT INTO account (name, type, issuerLabel, last4, origin) " +
                                "VALUES (NULL, 'CASH', NULL, NULL, 'USER')",
                        )
                    }
                },
            ).build()

    @Provides
    fun rawNotificationEventDao(database: DepensesDatabase): RawNotificationEventDao =
        database.rawNotificationEventDao()

    @Provides
    fun transactionDao(database: DepensesDatabase): TransactionDao = database.transactionDao()

    @Provides
    fun merchantDao(database: DepensesDatabase): MerchantDao = database.merchantDao()

    @Provides
    fun reportDao(database: DepensesDatabase): ReportDao = database.reportDao()

    @Provides
    fun accountDao(database: DepensesDatabase): AccountDao = database.accountDao()

    @Provides
    fun categoryDao(database: DepensesDatabase): CategoryDao = database.categoryDao()

    @Provides
    fun categoryGroupDao(database: DepensesDatabase): CategoryGroupDao = database.categoryGroupDao()

    @Provides
    fun recurringTransactionDao(database: DepensesDatabase): RecurringTransactionDao =
        database.recurringTransactionDao()
}
