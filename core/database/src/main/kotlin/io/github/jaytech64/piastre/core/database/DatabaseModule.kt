// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.core.database

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
    ): PiastreDatabase =
        Room
            .databaseBuilder(context, PiastreDatabase::class.java, PiastreDatabase.NAME)
            .addMigrations(*PiastreDatabase.MIGRATIONS)
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
    fun rawNotificationEventDao(database: PiastreDatabase): RawNotificationEventDao = database.rawNotificationEventDao()

    @Provides
    fun transactionDao(database: PiastreDatabase): TransactionDao = database.transactionDao()

    @Provides
    fun merchantDao(database: PiastreDatabase): MerchantDao = database.merchantDao()

    @Provides
    fun reportDao(database: PiastreDatabase): ReportDao = database.reportDao()

    @Provides
    fun accountDao(database: PiastreDatabase): AccountDao = database.accountDao()

    @Provides
    fun categoryDao(database: PiastreDatabase): CategoryDao = database.categoryDao()

    @Provides
    fun categoryGroupDao(database: PiastreDatabase): CategoryGroupDao = database.categoryGroupDao()

    @Provides
    fun recurringTransactionDao(database: PiastreDatabase): RecurringTransactionDao = database.recurringTransactionDao()

    @Provides
    fun transactionTemplateDao(database: PiastreDatabase): TransactionTemplateDao = database.transactionTemplateDao()

    @Provides
    fun transactionPartDao(database: PiastreDatabase): TransactionPartDao = database.transactionPartDao()
}
