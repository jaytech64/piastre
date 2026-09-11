// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.database

import android.content.Context
import androidx.room.Room
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
            .build()

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
    fun cardDao(database: DepensesDatabase): CardDao = database.cardDao()
}
