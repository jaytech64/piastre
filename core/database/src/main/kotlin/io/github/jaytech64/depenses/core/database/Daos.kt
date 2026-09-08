// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.database

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import io.github.jaytech64.depenses.core.model.ParseState
import io.github.jaytech64.depenses.core.model.TransactionStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface RawNotificationEventDao {
    /**
     * Returns the new row id, or [INSERT_IGNORED] when this exact post is already
     * recorded — which is the normal outcome every time the listener rebinds and
     * replays the shade.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(event: RawNotificationEventEntity): Long

    @Query("SELECT * FROM raw_notification_event WHERE id = :id")
    suspend fun byId(id: Long): RawNotificationEventEntity?

    @Query("SELECT * FROM raw_notification_event WHERE parseState = :state ORDER BY postedAtUtcMillis")
    suspend fun byState(state: ParseState): List<RawNotificationEventEntity>

    /** Backs the re-parse pass after a parser fix: rows an older version produced. */
    @Query("SELECT * FROM raw_notification_event WHERE parsedByVersion IS NULL OR parsedByVersion < :version")
    suspend fun parsedBelowVersion(version: Int): List<RawNotificationEventEntity>

    /** [version] is null when no parser claimed the notification, so none read it. */
    @Query("UPDATE raw_notification_event SET parseState = :state, parsedByVersion = :version WHERE id = :id")
    suspend fun markParsed(
        id: Long,
        state: ParseState,
        version: Int?,
    )

    @Query("SELECT COUNT(*) FROM raw_notification_event")
    fun count(): Flow<Int>

    companion object {
        /** What SQLite's `INSERT OR IGNORE` returns when the row was already there. */
        const val INSERT_IGNORED: Long = -1L
    }
}

@Dao
interface TransactionDao {
    /**
     * Records a parsed transaction, replacing the parser's earlier reading of the
     * same purchase.
     *
     * Wallet re-posts a purchase seconds later, and sometimes over half an hour
     * later with a fresh timestamp, so a plain insert would grow the ledger every
     * time. Room's `@Upsert` does not solve this: it resolves the conflict by
     * primary key, and a fresh row carries id 0, so the update matches nothing and
     * the re-post is silently dropped instead of applied.
     *
     * Fields the user owns — their note, their category — survive a re-parse.
     * Everything the parser owns is overwritten, which is what makes fixing a
     * parser repair history.
     */
    @Transaction
    suspend fun insertOrUpdate(transaction: ExpenseTransactionEntity): Long {
        val existing =
            bySourceKey(transaction.sourcePackage, transaction.sourceKey)
                ?: return insert(transaction)
        update(
            transaction.copy(
                id = existing.id,
                note = existing.note,
                categoryId = existing.categoryId,
            ),
        )
        return existing.id
    }

    @Insert
    suspend fun insert(transaction: ExpenseTransactionEntity): Long

    @Update
    suspend fun update(transaction: ExpenseTransactionEntity)

    @Query("SELECT * FROM expense_transaction WHERE sourcePackage = :sourcePackage AND sourceKey = :sourceKey")
    suspend fun bySourceKey(
        sourcePackage: String,
        sourceKey: String,
    ): ExpenseTransactionEntity?

    @Query("SELECT * FROM expense_transaction WHERE status = :status ORDER BY occurredAtUtcMillis DESC")
    fun byStatus(status: TransactionStatus): Flow<List<ExpenseTransactionEntity>>

    @Query("SELECT COUNT(*) FROM expense_transaction WHERE status = :status")
    fun countByStatus(status: TransactionStatus): Flow<Int>

    @Query("SELECT * FROM expense_transaction ORDER BY occurredAtUtcMillis DESC")
    fun all(): Flow<List<ExpenseTransactionEntity>>

    /**
     * The ledger reads this rather than [all]: it needs the canonical merchant, and
     * a screen that joined the merchant table itself would be reimplementing the
     * chain-collapsing rules in Kotlin.
     *
     * The join is a left join because a transaction whose merchant could not be
     * resolved still has to appear — losing a purchase is worse than showing it
     * under its raw string.
     */
    @Query(
        """
        SELECT t.*, m.displayName AS merchantName
        FROM expense_transaction t
        LEFT JOIN merchant m ON m.id = t.merchantId
        WHERE t.status = :status
        ORDER BY t.occurredAtUtcMillis DESC
        """,
    )
    fun withMerchantByStatus(status: TransactionStatus): Flow<List<TransactionWithMerchant>>

    @Query("SELECT COUNT(*) FROM expense_transaction")
    fun count(): Flow<Int>

    @Query("DELETE FROM expense_transaction")
    suspend fun deleteAll()
}

@Dao
interface MerchantDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(merchant: MerchantEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAlias(alias: MerchantAliasEntity): Long

    @Query("SELECT * FROM merchant WHERE normalisedName = :normalisedName")
    suspend fun byNormalisedName(normalisedName: String): MerchantEntity?

    @Query("SELECT m.* FROM merchant m JOIN merchant_alias a ON a.merchantId = m.id WHERE a.pattern = :raw")
    suspend fun byExactAlias(raw: String): MerchantEntity?

    @Query("SELECT * FROM merchant ORDER BY displayName")
    fun all(): Flow<List<MerchantEntity>>
}

/** A transaction with the display name of the merchant it resolved to. */
data class TransactionWithMerchant(
    @Embedded val transaction: ExpenseTransactionEntity,
    val merchantName: String?,
)

/** A daily total, one row per local day. */
data class DailyTotal(
    val day: Int,
    val totalMinor: Long,
    val count: Int,
)

/** A merchant's share of a period. */
data class MerchantTotal(
    val merchantId: Long,
    val name: String,
    val totalMinor: Long,
    val count: Int,
)

/**
 * Reports are SQL, not Kotlin loops over the whole table. Every one of these
 * groups on an indexed integer column, so a year of history costs the same as a
 * week.
 */
@Dao
interface ReportDao {
    @Query(
        """
        SELECT localDateEpochDay AS day, SUM(amountMinor) AS totalMinor, COUNT(*) AS count
        FROM expense_transaction
        WHERE status = 'CONFIRMED'
          AND currency = :currency
          AND localDateEpochDay BETWEEN :fromDay AND :toDay
        GROUP BY localDateEpochDay
        ORDER BY localDateEpochDay
        """,
    )
    fun dailyTotals(
        fromDay: Int,
        toDay: Int,
        currency: String,
    ): Flow<List<DailyTotal>>

    @Query(
        """
        SELECT SUM(amountMinor)
        FROM expense_transaction
        WHERE status = 'CONFIRMED'
          AND currency = :currency
          AND localDateEpochDay BETWEEN :fromDay AND :toDay
        """,
    )
    fun totalForRange(
        fromDay: Int,
        toDay: Int,
        currency: String,
    ): Flow<Long?>

    @Query(
        """
        SELECT m.id AS merchantId, m.displayName AS name,
               SUM(t.amountMinor) AS totalMinor, COUNT(*) AS count
        FROM expense_transaction t
        JOIN merchant m ON m.id = t.merchantId
        WHERE t.status = 'CONFIRMED'
          AND t.currency = :currency
          AND t.localDateEpochDay BETWEEN :fromDay AND :toDay
        GROUP BY m.id
        ORDER BY totalMinor DESC
        LIMIT :limit
        """,
    )
    fun topMerchants(
        fromDay: Int,
        toDay: Int,
        currency: String,
        limit: Int,
    ): Flow<List<MerchantTotal>>
}
