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

    /**
     * Backs the re-parse pass after a parser fix: rows an older version of this
     * parser read, and rows nothing has ever read.
     *
     * The null case is not an edge: a parser gains a payload type about as often
     * as it loses a bug, and the rows it used to walk past carry no version at
     * all. Scoped to the packages the parser actually claims, so bumping one
     * parser does not drag another app's notifications back through it.
     *
     * Redacted rows are excluded, and that exclusion is load-bearing rather than
     * an optimisation. Re-reading a row whose payload the retention sweep has
     * stripped would find no payment in it, and the tidy-up that follows a
     * re-parse would then delete the transaction it produced back when the text
     * was still there — a parser fix would quietly erase everything older than
     * 90 days.
     */
    @Query(
        """
        SELECT * FROM raw_notification_event
        WHERE sourcePackage IN (:packages)
          AND redactedAtUtcMillis IS NULL
          AND (parsedByVersion IS NULL OR parsedByVersion < :version)
        ORDER BY postedAtUtcMillis
        """,
    )
    suspend fun parsedBelowVersion(
        packages: Set<String>,
        version: Int,
    ): List<RawNotificationEventEntity>

    /** [version] is null when no parser claimed the notification, so none read it. */
    @Query("UPDATE raw_notification_event SET parseState = :state, parsedByVersion = :version WHERE id = :id")
    suspend fun markParsed(
        id: Long,
        state: ParseState,
        version: Int?,
    )

    /**
     * Strips the content of rows older than the cutoff, and returns how many rows
     * that changed.
     *
     * The skeleton stays: id, key, post time and parse state. Deleting the row
     * outright would break the uniqueness rule that collapses replays, and a
     * notification the shade hands back two years later would reappear as a new
     * purchase.
     *
     * [exempt] is the states whose rows are kept whole — a payload no parser
     * could read is the raw material for the parser that will one day read it,
     * and stripping it would destroy the only copy.
     *
     * The `redactedAtUtcMillis IS NULL` clause makes the count honest: a second
     * sweep over the same rows reports nothing rather than counting them again.
     */
    @Query(
        """
        UPDATE raw_notification_event
        SET title = NULL, text = NULL, bigText = NULL, subText = NULL,
            extrasJson = '{}', redactedAtUtcMillis = :redactedAtUtcMillis
        WHERE postedAtUtcMillis < :cutoffUtcMillis
          AND redactedAtUtcMillis IS NULL
          AND parseState NOT IN (:exempt)
        """,
    )
    suspend fun redactOlderThan(
        cutoffUtcMillis: Long,
        redactedAtUtcMillis: Long,
        exempt: List<ParseState>,
    ): Int

    @Query("SELECT COUNT(*) FROM raw_notification_event")
    fun count(): Flow<Int>

    /**
     * Drops the whole raw log, keeping the ledger.
     *
     * This is the sensitive half of the database and the half you can delete
     * without losing history: the transactions read out of these rows stay, they
     * just stop being re-derivable, and the review inbox shows a ruled row
     * without the notification behind it.
     */
    @Query("DELETE FROM raw_notification_event")
    suspend fun deleteAll()

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
     * Fields the user owns — their note, their category, whether it was income —
     * survive a re-parse.
     * Everything the parser owns is overwritten, which is what makes fixing a
     * parser repair history.
     *
     * A row a human has already ruled on is left entirely alone: without that,
     * the next re-post would silently undo the correction made in the review
     * inbox, using the very notification the correction was about. A later parser
     * fix does not reopen it either, which is the point — your ruling is evidence
     * about the purchase, not a guess to be improved on.
     */
    @Transaction
    suspend fun insertOrUpdate(transaction: ExpenseTransactionEntity): Long {
        val existing =
            bySourceKey(transaction.sourcePackage, transaction.sourceKey)
                ?: return insert(transaction)
        if (existing.reviewedAtUtcMillis != null) return existing.id
        update(
            transaction.copy(
                id = existing.id,
                note = existing.note,
                categoryId = existing.categoryId,
                kind = existing.kind,
            ),
        )
        return existing.id
    }

    @Insert
    suspend fun insert(transaction: ExpenseTransactionEntity): Long

    @Update
    suspend fun update(transaction: ExpenseTransactionEntity)

    /**
     * Drops what an earlier reading of [rawEventId] left in the ledger and the
     * current one no longer produces, keeping the row identified by [keepId].
     *
     * Overwriting in place covers a parser that reads the same purchase better;
     * it cannot cover a parser that now reads a different purchase, or none at
     * all. A fix to how a source key is derived would otherwise leave the old row
     * beside the new one, and a payload reclassified as "not a payment" would
     * leave its phantom in the ledger for good — both of them silently, which is
     * the worst way for a ledger to be wrong.
     *
     * Pass [NOTHING_RECORDED] when the re-parse produced no row. A row a human
     * has ruled on is left alone, for the same reason [insertOrUpdate] leaves it
     * alone.
     */
    @Query(
        """
        DELETE FROM expense_transaction
        WHERE rawEventId = :rawEventId AND id != :keepId AND reviewedAtUtcMillis IS NULL
        """,
    )
    suspend fun deleteSupersededBy(
        rawEventId: Long,
        keepId: Long,
    )

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
        SELECT t.*, m.displayName AS merchantName, c.name AS cardName, c.last4 AS resolvedCardLast4
        FROM expense_transaction t
        LEFT JOIN merchant m ON m.id = t.merchantId
        LEFT JOIN card c ON c.id = t.cardId
        WHERE t.status = :status
        ORDER BY t.occurredAtUtcMillis DESC
        """,
    )
    fun withMerchantByStatus(status: TransactionStatus): Flow<List<TransactionWithMerchant>>

    /**
     * The review queue, carrying the notification the row was read from.
     *
     * The raw title and text ride along because the inbox shows them verbatim:
     * the question it asks is "did the parser read this correctly", which cannot
     * be answered from the parser's own output. The join is a left join so that a
     * row whose raw event has since been purged is still reviewable — without the
     * evidence, but not lost.
     */
    @Query(
        """
        SELECT t.*, m.displayName AS merchantName,
               r.title AS rawTitle, r.text AS rawText, r.postedAtUtcMillis AS rawPostedAtUtcMillis
        FROM expense_transaction t
        LEFT JOIN merchant m ON m.id = t.merchantId
        LEFT JOIN raw_notification_event r ON r.id = t.rawEventId
        WHERE t.status = :status
        ORDER BY t.occurredAtUtcMillis DESC
        """,
    )
    fun reviewQueue(status: TransactionStatus): Flow<List<TransactionForReview>>

    @Query("SELECT * FROM expense_transaction WHERE id = :id")
    suspend fun byId(id: Long): ExpenseTransactionEntity?

    @Query(
        """
        SELECT t.*, m.displayName AS merchantName, c.name AS cardName, c.last4 AS resolvedCardLast4
        FROM expense_transaction t
        LEFT JOIN merchant m ON m.id = t.merchantId
        LEFT JOIN card c ON c.id = t.cardId
        WHERE t.id = :id
        """,
    )
    suspend fun withMerchantById(id: Long): TransactionWithMerchant?

    @Query("DELETE FROM expense_transaction WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COUNT(*) FROM expense_transaction")
    fun count(): Flow<Int>

    /**
     * Every transaction with its canonical merchant, oldest first — the shape an
     * export wants. Ascending because a spreadsheet is read downward through
     * time, where a screen is read newest first.
     */
    @Query(
        """
        SELECT t.*, m.displayName AS merchantName, c.name AS cardName, c.last4 AS resolvedCardLast4
        FROM expense_transaction t
        LEFT JOIN merchant m ON m.id = t.merchantId
        LEFT JOIN card c ON c.id = t.cardId
        ORDER BY t.occurredAtUtcMillis
        """,
    )
    suspend fun allWithMerchant(): List<TransactionWithMerchant>

    @Query("DELETE FROM expense_transaction")
    suspend fun deleteAll()

    companion object {
        /** No row survived the re-parse, so every row from that event is superseded. */
        const val NOTHING_RECORDED: Long = 0
    }
}

@Dao
interface MerchantDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(merchant: MerchantEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAlias(alias: MerchantAliasEntity): Long

    /**
     * Writes an alias that must win, replacing whatever mapping was there.
     *
     * [insertAlias] is right for the parser, which should never overwrite a
     * decision; this is right for the user, whose correction is the decision.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAlias(alias: MerchantAliasEntity): Long

    @Query("SELECT * FROM merchant WHERE normalisedName = :normalisedName")
    suspend fun byNormalisedName(normalisedName: String): MerchantEntity?

    @Query("SELECT m.* FROM merchant m JOIN merchant_alias a ON a.merchantId = m.id WHERE a.pattern = :raw")
    suspend fun byExactAlias(raw: String): MerchantEntity?

    @Query("SELECT * FROM merchant ORDER BY displayName")
    fun all(): Flow<List<MerchantEntity>>

    @Query("SELECT * FROM merchant ORDER BY id")
    suspend fun allOnce(): List<MerchantEntity>

    @Query("SELECT * FROM merchant_alias ORDER BY id")
    suspend fun allAliases(): List<MerchantAliasEntity>
}

@Dao
interface CardDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(card: CardEntity): Long

    /**
     * The Wallet card a notification names. `IS` rather than `=` for the label, so
     * a notification whose card label could not be read still finds its card.
     * Scoped to Wallet cards, so one you added by hand is never claimed by capture.
     */
    @Query("SELECT * FROM card WHERE origin = 'WALLET' AND issuerLabel IS :issuerLabel AND last4 = :last4")
    suspend fun walletCard(
        issuerLabel: String?,
        last4: String,
    ): CardEntity?

    /** Named cards first, alphabetically; then the ones still waiting for a name. */
    @Query(
        """
        SELECT c.*, COUNT(t.id) AS purchaseCount
        FROM card c
        LEFT JOIN expense_transaction t ON t.cardId = c.id AND t.status = 'CONFIRMED'
        GROUP BY c.id
        ORDER BY c.name IS NULL, c.name COLLATE NOCASE, c.issuerLabel, c.last4
        """,
    )
    fun allWithCounts(): Flow<List<CardWithCount>>

    @Query("SELECT * FROM card ORDER BY id")
    suspend fun allOnce(): List<CardEntity>

    @Query("UPDATE card SET name = :name WHERE id = :id")
    suspend fun rename(
        id: Long,
        name: String?,
    )

    /** Only a card you added has digits you can change; Wallet's are the bank's. */
    @Query("UPDATE card SET name = :name, last4 = :last4 WHERE id = :id AND origin = 'USER'")
    suspend fun updateUserCard(
        id: Long,
        name: String?,
        last4: String?,
    )

    /**
     * Returns 1 when the card was removed and 0 when a transaction still uses it,
     * so nothing is ever left pointing at a card that is gone.
     */
    @Query("DELETE FROM card WHERE id = :id AND NOT EXISTS (SELECT 1 FROM expense_transaction WHERE cardId = :id)")
    suspend fun deleteIfUnused(id: Long): Int
}

/**
 * A transaction with the display name of the merchant it resolved to, and the
 * card it was paid with.
 *
 * [resolvedCardLast4] is the card's digits rather than the notification's: a
 * manual entry has no notification, and a card you added carries its digits only
 * on the card row.
 */
data class TransactionWithMerchant(
    @Embedded val transaction: ExpenseTransactionEntity,
    val merchantName: String?,
    val cardName: String? = null,
    val resolvedCardLast4: String? = null,
)

/** A card with how many confirmed transactions were paid with it. */
data class CardWithCount(
    @Embedded val card: CardEntity,
    val purchaseCount: Int,
)

/** A transaction awaiting review, with the notification it was read from. */
data class TransactionForReview(
    @Embedded val transaction: ExpenseTransactionEntity,
    val merchantName: String?,
    val rawTitle: String?,
    val rawText: String?,
    val rawPostedAtUtcMillis: Long?,
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
 *
 * They are about spending, so income is left out of every one of them: a
 * paycheque ranked beside the grocer, or cancelling out a week of coffee on a
 * heat map, would make both answers wrong.
 */
@Dao
interface ReportDao {
    @Query(
        """
        SELECT localDateEpochDay AS day, SUM(amountMinor) AS totalMinor, COUNT(*) AS count
        FROM expense_transaction
        WHERE status = 'CONFIRMED'
          AND kind = 'EXPENSE'
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
          AND kind = 'EXPENSE'
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
          AND t.kind = 'EXPENSE'
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
