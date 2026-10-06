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
import io.github.jaytech64.depenses.core.model.AccountType
import io.github.jaytech64.depenses.core.model.ParseState
import io.github.jaytech64.depenses.core.model.TransactionKind
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

/**
 * Keeps a write of a category off a split. A split's row carries none because
 * its parts do, so `categoryId IS NULL` is true of it without meaning "nobody
 * has said": every write that gives rows a category in bulk must add this, or
 * the split would be counted under that category on top of its parts (ADR-0018).
 */
private const val NOT_SPLIT =
    "NOT EXISTS (SELECT 1 FROM transaction_part p WHERE p.transactionId = expense_transaction.id)"

/** A transaction's row and its parts, which leave together. */
interface RowWithParts {
    @Query("SELECT EXISTS (SELECT 1 FROM transaction_part WHERE transactionId = :id)")
    suspend fun isSplit(id: Long): Boolean

    @Query("DELETE FROM expense_transaction WHERE id = :id")
    suspend fun deleteRow(id: Long)

    @Query("DELETE FROM transaction_part WHERE transactionId = :id")
    suspend fun deletePartsOf(id: Long)
}

@Dao
interface TransactionDao : RowWithParts {
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
     * survive a re-parse. A row with no category yet takes the one the new reading
     * carries, which is its merchant's, so a category given to a merchant since
     * the first reading reaches a purchase Wallet re-posts. A split never does: its
     * parts already say what it was for.
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
                categoryId = existing.categoryId ?: transaction.categoryId.takeUnless { isSplit(existing.id) },
                kind = existing.kind,
            ),
        )
        return existing.id
    }

    @Insert
    suspend fun insert(transaction: ExpenseTransactionEntity): Long

    /**
     * Returns [RawNotificationEventDao.INSERT_IGNORED] when a row with the same
     * source key is already there, which for a recurring row means that day has
     * already been written.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(transaction: ExpenseTransactionEntity): Long

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
        SELECT t.*, m.displayName AS merchantName,
               a.name AS accountName, a.last4 AS accountLast4, a.type AS accountType,
               d.name AS toAccountName, d.last4 AS toAccountLast4, d.type AS toAccountType,
               c.name AS categoryName
        FROM expense_transaction t
        LEFT JOIN merchant m ON m.id = t.merchantId
        LEFT JOIN account a ON a.id = t.accountId
        LEFT JOIN account d ON d.id = t.toAccountId
        LEFT JOIN category c ON c.id = t.categoryId
        WHERE t.status = :status
        ORDER BY t.occurredAtUtcMillis DESC
        """,
    )
    fun withMerchantByStatus(status: TransactionStatus): Flow<List<TransactionWithMerchant>>

    /**
     * The account of each merchant's latest confirmed [kind] row up to
     * [untilEpochDay] that names one. Rows with no account are passed over rather
     * than read as "none", and the id breaks a tie on the same instant so the
     * answer never depends on how SQLite happens to scan.
     */
    @Query(
        """
        SELECT normalisedName, accountId FROM (
            SELECT m.normalisedName, t.accountId,
                ROW_NUMBER() OVER (
                    PARTITION BY t.merchantId ORDER BY t.occurredAtUtcMillis DESC, t.id DESC
                ) AS recency
            FROM expense_transaction t
            JOIN merchant m ON m.id = t.merchantId
            WHERE t.kind = :kind AND t.status = 'CONFIRMED' AND t.accountId IS NOT NULL
                AND t.localDateEpochDay <= :untilEpochDay
        )
        WHERE recency = 1
        """,
    )
    fun lastAccountAtEachMerchant(
        kind: TransactionKind,
        untilEpochDay: Int,
    ): Flow<List<MerchantLastAccount>>

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
        SELECT t.*, m.displayName AS merchantName,
               a.name AS accountName, a.last4 AS accountLast4, a.type AS accountType,
               d.name AS toAccountName, d.last4 AS toAccountLast4, d.type AS toAccountType,
               c.name AS categoryName
        FROM expense_transaction t
        LEFT JOIN merchant m ON m.id = t.merchantId
        LEFT JOIN account a ON a.id = t.accountId
        LEFT JOIN account d ON d.id = t.toAccountId
        LEFT JOIN category c ON c.id = t.categoryId
        WHERE t.id = :id
        """,
    )
    suspend fun withMerchantById(id: Long): TransactionWithMerchant?

    /** Deletes a transaction with its parts, so no part is ever left dividing an amount that is gone. */
    @Transaction
    suspend fun deleteById(id: Long) {
        deletePartsOf(id)
        deleteRow(id)
    }

    /**
     * Gives [categoryId], or none, to exactly the rows [ids] names, and returns how
     * many that was.
     *
     * Unlike [CategoryDao.categoriseAtMerchant], a row that already has a
     * category takes the new one: these rows were picked one by one, often
     * precisely because the category they carry is wrong. A dismissed row is left
     * out, as there, and so is a split, which was divided by hand part by part.
     *
     * The review time is not stamped, for the reason
     * [CategoryDao.categoriseAtMerchant] gives. [ids] is a selection made by hand,
     * far below the 32 766 variables Android 12's SQLite binds, so it is never
     * chunked.
     */
    @Query(
        """
        UPDATE expense_transaction SET categoryId = :categoryId
        WHERE id IN (:ids) AND status != 'DISMISSED' AND """ + NOT_SPLIT + """
        """,
    )
    suspend fun categorise(
        ids: Collection<Long>,
        categoryId: Long?,
    ): Int

    @Query("SELECT COUNT(*) FROM expense_transaction")
    fun count(): Flow<Int>

    /**
     * Every transaction with its canonical merchant, oldest first — the shape an
     * export wants. Ascending because a spreadsheet is read downward through
     * time, where a screen is read newest first.
     */
    @Query(
        """
        SELECT t.*, m.displayName AS merchantName,
               a.name AS accountName, a.last4 AS accountLast4, a.type AS accountType,
               d.name AS toAccountName, d.last4 AS toAccountLast4, d.type AS toAccountType,
               c.name AS categoryName
        FROM expense_transaction t
        LEFT JOIN merchant m ON m.id = t.merchantId
        LEFT JOIN account a ON a.id = t.accountId
        LEFT JOIN account d ON d.id = t.toAccountId
        LEFT JOIN category c ON c.id = t.categoryId
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

/** The parts a split transaction's amount is divided into: see [TransactionPartEntity]. */
@Dao
interface TransactionPartDao {
    @Insert
    suspend fun insertAll(parts: List<TransactionPartEntity>)

    @Query("DELETE FROM transaction_part WHERE transactionId = :transactionId")
    suspend fun deleteOf(transactionId: Long)

    /**
     * Makes [parts] the whole of what [transactionId] is divided into, in the
     * order given. An empty list leaves the transaction with no parts, which is
     * a transaction that is not split.
     */
    @Transaction
    suspend fun replace(
        transactionId: Long,
        parts: List<TransactionPartEntity>,
    ) {
        deleteOf(transactionId)
        insertAll(
            parts.mapIndexed { position, part ->
                part.copy(id = 0, transactionId = transactionId, position = position)
            },
        )
    }

    /** Every part with its category's name, each transaction's together and in the order typed. */
    @Query(
        """
        SELECT p.*, c.name AS categoryName
        FROM transaction_part p
        LEFT JOIN category c ON c.id = p.categoryId
        ORDER BY p.transactionId, p.position
        """,
    )
    fun allWithNames(): Flow<List<PartWithCategory>>

    @Query(
        """
        SELECT p.*, c.name AS categoryName
        FROM transaction_part p
        LEFT JOIN category c ON c.id = p.categoryId
        WHERE p.transactionId = :transactionId
        ORDER BY p.position
        """,
    )
    suspend fun ofTransaction(transactionId: Long): List<PartWithCategory>

    @Query("SELECT * FROM transaction_part ORDER BY transactionId, position")
    suspend fun allOnce(): List<TransactionPartEntity>
}

/** A part with the name of its category, or none. */
data class PartWithCategory(
    @Embedded val part: TransactionPartEntity,
    val categoryName: String?,
)

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

    /**
     * Renames a merchant in place, which renames it on every transaction at once:
     * the ledger reads this name through a join rather than copying it per row.
     */
    @Query("UPDATE merchant SET displayName = :displayName WHERE id = :id")
    suspend fun renameMerchant(
        id: Long,
        displayName: String,
    )

    @Query("SELECT * FROM merchant WHERE normalisedName = :normalisedName")
    suspend fun byNormalisedName(normalisedName: String): MerchantEntity?

    @Query("SELECT m.* FROM merchant m JOIN merchant_alias a ON a.merchantId = m.id WHERE a.pattern = :raw")
    suspend fun byExactAlias(raw: String): MerchantEntity?

    /**
     * Every merchant, the ones you use most for [kind] first: by how many confirmed
     * rows named it on or after [sinceEpochDay], then by when it was last used,
     * then by name. A merchant never used for [kind] still comes back, last.
     *
     * A row waiting for review does not count: its merchant may be the parser's
     * wrong guess, and it is approving the row that makes it yours.
     */
    @Query(
        """
        SELECT m.* FROM merchant m
        LEFT JOIN expense_transaction t
            ON t.merchantId = m.id AND t.kind = :kind AND t.status = 'CONFIRMED'
        GROUP BY m.id
        ORDER BY SUM(t.localDateEpochDay >= :sinceEpochDay) DESC,
            MAX(t.occurredAtUtcMillis) DESC,
            m.displayName
        """,
    )
    fun byUse(
        kind: TransactionKind,
        sinceEpochDay: Int,
    ): Flow<List<MerchantEntity>>

    @Query("SELECT * FROM merchant ORDER BY id")
    suspend fun allOnce(): List<MerchantEntity>

    @Query("SELECT * FROM merchant ORDER BY id")
    fun all(): Flow<List<MerchantEntity>>

    @Query("SELECT * FROM merchant WHERE id = :id")
    suspend fun byId(id: Long): MerchantEntity?

    /** Null stops it: purchases there arrive with no category again. */
    @Query("UPDATE merchant SET categoryId = :categoryId WHERE id = :id")
    suspend fun setCategory(
        id: Long,
        categoryId: Long?,
    )

    @Query("SELECT * FROM merchant_alias ORDER BY id")
    suspend fun allAliases(): List<MerchantAliasEntity>
}

@Dao
interface AccountDao {
    /** Prefer [insertLast], which gives the account its place; this one leaves [AccountEntity.sortOrder] as given. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(account: AccountEntity): Long

    /**
     * Adds an account after every other, so your order never changes without you:
     * a card Wallet shows for the first time arrives last, as does one you add.
     */
    @Transaction
    suspend fun insertLast(account: AccountEntity): Long = insert(account.copy(sortOrder = nextSortOrder()))

    @Query("SELECT COALESCE(MAX(sortOrder) + 1, 0) FROM account")
    suspend fun nextSortOrder(): Int

    /**
     * Moves an account to [toIndex] in the order you see, shifting the ones between.
     *
     * Every account is renumbered from 0, so positions stay dense whatever a removal
     * or an import left behind, and two accounts never share a place.
     */
    @Transaction
    suspend fun move(
        id: Long,
        toIndex: Int,
    ) {
        val ids = idsInOrder().toMutableList()
        if (!ids.remove(id)) return
        ids.add(toIndex.coerceIn(0, ids.size), id)
        ids.forEachIndexed { position, each -> setSortOrder(each, position) }
    }

    @Query("SELECT id FROM account ORDER BY sortOrder, id")
    suspend fun idsInOrder(): List<Long>

    @Query("UPDATE account SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun setSortOrder(
        id: Long,
        sortOrder: Int,
    )

    /**
     * The Wallet card a notification names. `IS` rather than `=` for the label, so
     * a notification whose card label could not be read still finds its account.
     * Scoped to Wallet accounts, so one you added by hand is never claimed by capture.
     */
    @Query("SELECT * FROM account WHERE origin = 'WALLET' AND issuerLabel IS :issuerLabel AND last4 = :last4")
    suspend fun walletAccount(
        issuerLabel: String?,
        last4: String,
    ): AccountEntity?

    /**
     * In the order you set in Settings, which every list of accounts follows.
     *
     * Both sides of a transfer count: an account money only ever leaves is still in
     * use, and the count is what decides whether it can be removed.
     *
     * The balance starts from the opening balance and follows every confirmed row on
     * the account: income adds, an expense takes away, and a transfer takes from the
     * account it left and adds to the one it reached. Only [currency] is summed, the
     * way reports keep to one currency, because dollars plus euros is not a balance.
     * It stops at [throughDay]: what the account holds that day, never a row dated
     * after it. The count does not stop, since a row still to come uses the account.
     */
    @Query(
        """
        SELECT a.*,
            (SELECT COUNT(*) FROM expense_transaction t
             WHERE (t.accountId = a.id OR t.toAccountId = a.id) AND t.status = 'CONFIRMED') AS transactionCount,
            COALESCE(a.openingBalanceMinor, 0)
                + COALESCE((SELECT SUM(CASE WHEN t.kind = 'INCOME' THEN t.amountMinor ELSE -t.amountMinor END)
                            FROM expense_transaction t
                            WHERE t.accountId = a.id AND t.status = 'CONFIRMED' AND t.currency = :currency
                              AND t.localDateEpochDay <= :throughDay), 0)
                + COALESCE((SELECT SUM(t.amountMinor)
                            FROM expense_transaction t
                            WHERE t.toAccountId = a.id AND t.kind = 'TRANSFER'
                              AND t.status = 'CONFIRMED' AND t.currency = :currency
                              AND t.localDateEpochDay <= :throughDay), 0)
                AS balanceMinor
        FROM account a
        ORDER BY a.sortOrder, a.id
        """,
    )
    fun allWithCounts(
        currency: String,
        throughDay: Int,
    ): Flow<List<AccountWithCount>>

    @Query("SELECT * FROM account ORDER BY id")
    suspend fun allOnce(): List<AccountEntity>

    /** The cash account, which the 7→8 migration created if any entry ever said cash. */
    @Query("SELECT * FROM account WHERE type = 'CASH' ORDER BY id LIMIT 1")
    suspend fun cashAccount(): AccountEntity?

    @Query("UPDATE account SET name = :name WHERE id = :id")
    suspend fun rename(
        id: Long,
        name: String?,
    )

    @Query("SELECT categoryId FROM account WHERE id = :id")
    suspend fun categoryOf(id: Long): Long?

    /** Null stops it: transfers into the account arrive with no category again. */
    @Query("UPDATE account SET categoryId = :categoryId WHERE id = :id")
    suspend fun setCategory(
        id: Long,
        categoryId: Long?,
    )

    /** Null clears it, and the account goes back to having no stated starting point. */
    @Query("UPDATE account SET openingBalanceMinor = :openingBalanceMinor WHERE id = :id")
    suspend fun setOpeningBalance(
        id: Long,
        openingBalanceMinor: Long?,
    )

    /** Only an account you added has digits and a type you can change; Wallet's are the bank's. */
    @Query("UPDATE account SET name = :name, last4 = :last4, type = :type WHERE id = :id AND origin = 'USER'")
    suspend fun updateAddedAccount(
        id: Long,
        name: String?,
        last4: String?,
        type: AccountType,
    )

    /**
     * Returns 1 when the account was removed and 0 when a transaction, a
     * recurring transaction or a template still uses it, so nothing is ever left
     * pointing at an account that is gone.
     */
    @Query(
        """
        DELETE FROM account WHERE id = :id AND NOT EXISTS (
            SELECT 1 FROM expense_transaction WHERE accountId = :id OR toAccountId = :id
        ) AND NOT EXISTS (
            SELECT 1 FROM recurring_transaction WHERE accountId = :id OR toAccountId = :id
        ) AND NOT EXISTS (
            SELECT 1 FROM transaction_template WHERE accountId = :id OR toAccountId = :id
        )
        """,
    )
    suspend fun deleteIfUnused(id: Long): Int
}

/** Everything that can carry a category, each cleared of one that is being removed. */
interface CategoryClearing {
    @Query("UPDATE expense_transaction SET categoryId = NULL WHERE categoryId = :id")
    suspend fun clearFromTransactions(id: Long)

    @Query("UPDATE merchant SET categoryId = NULL WHERE categoryId = :id")
    suspend fun clearFromMerchants(id: Long)

    @Query("UPDATE account SET categoryId = NULL WHERE categoryId = :id")
    suspend fun clearFromAccounts(id: Long)

    @Query("UPDATE recurring_transaction SET categoryId = NULL WHERE categoryId = :id")
    suspend fun clearFromRecurring(id: Long)

    @Query("UPDATE transaction_template SET categoryId = NULL WHERE categoryId = :id")
    suspend fun clearFromTemplates(id: Long)

    @Query("UPDATE transaction_part SET categoryId = NULL WHERE categoryId = :id")
    suspend fun clearFromParts(id: Long)
}

/**
 * Every use of a category, once per transaction: the one a transaction carries,
 * and each one its parts carry. A split is a use of every category it touches,
 * and two parts under one category are still one transaction using it.
 */
private const val CATEGORISED =
    "(SELECT t.id AS transactionId, t.categoryId, t.kind, t.status, t.localDateEpochDay, t.occurredAtUtcMillis " +
        "FROM expense_transaction t WHERE t.categoryId IS NOT NULL " +
        "UNION " +
        "SELECT t.id, p.categoryId, t.kind, t.status, t.localDateEpochDay, t.occurredAtUtcMillis " +
        "FROM transaction_part p JOIN expense_transaction t ON t.id = p.transactionId " +
        "WHERE p.categoryId IS NOT NULL)"

@Dao
interface CategoryDao : CategoryClearing {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(category: CategoryEntity): Long

    @Query("SELECT * FROM category WHERE normalisedName = :normalisedName")
    suspend fun byNormalisedName(normalisedName: String): CategoryEntity?

    @Query("SELECT * FROM category ORDER BY id")
    suspend fun allOnce(): List<CategoryEntity>

    /**
     * Returns 0 and changes nothing when another category already answers to the
     * new name, so a rename can never fold two categories into one behind your back.
     */
    @Query("UPDATE OR IGNORE category SET name = :name, normalisedName = :normalisedName WHERE id = :id")
    suspend fun rename(
        id: Long,
        name: String,
        normalisedName: String,
    ): Int

    /**
     * Every category, the ones you use most for [kind] first: by how many confirmed
     * rows carried it on or after [sinceEpochDay], then by when it was last used,
     * then by name — the order [MerchantDao.byUse] gives merchants. A split counts
     * for every category its parts carry.
     *
     * The name breaks ties on its folded form, so an unused Épicerie sits among
     * the E's rather than after Z where SQLite's byte order would put it.
     */
    @Query(
        """
        SELECT c.* FROM category c
        LEFT JOIN """ + CATEGORISED + """ t
            ON t.categoryId = c.id AND t.kind = :kind AND t.status = 'CONFIRMED'
        GROUP BY c.id
        ORDER BY SUM(t.localDateEpochDay >= :sinceEpochDay) DESC,
            MAX(t.occurredAtUtcMillis) DESC,
            c.normalisedName
        """,
    )
    fun byUse(
        kind: TransactionKind,
        sinceEpochDay: Int,
    ): Flow<List<CategoryEntity>>

    /**
     * At most [limit] categories confirmed rows of [kind] carried on or after
     * [sinceEpochDay], in [byUse]'s order. A category not used since is not here,
     * so the list is only ever what you have actually been picking.
     */
    @Query(
        """
        SELECT c.* FROM category c
        JOIN """ + CATEGORISED + """ t
            ON t.categoryId = c.id AND t.kind = :kind AND t.status = 'CONFIRMED'
            AND t.localDateEpochDay >= :sinceEpochDay
        GROUP BY c.id
        ORDER BY COUNT(*) DESC, MAX(t.occurredAtUtcMillis) DESC, c.normalisedName
        LIMIT :limit
        """,
    )
    fun recentlyUsed(
        kind: TransactionKind,
        sinceEpochDay: Int,
        limit: Int,
    ): Flow<List<CategoryEntity>>

    /**
     * Confirmed transactions only, as an account counts them, a split among them
     * for each category it touches, and the merchants and accounts that give it
     * automatically.
     */
    @Query(
        """
        SELECT c.*,
            (SELECT COUNT(*) FROM """ + CATEGORISED + """ t
             WHERE t.categoryId = c.id AND t.status = 'CONFIRMED') AS transactionCount,
            (SELECT COUNT(*) FROM merchant m WHERE m.categoryId = c.id) AS merchantCount,
            (SELECT COUNT(*) FROM account a WHERE a.categoryId = c.id) AS accountCount
        FROM category c
        ORDER BY c.normalisedName
        """,
    )
    fun allWithCounts(): Flow<List<CategoryWithCounts>>

    /**
     * Gives [categoryId] to a merchant's other [kind] rows that have no category
     * yet, and returns how many that was. [exceptId] is the row the category was
     * chosen on, or null when it is not saved yet.
     *
     * A row that already has a category keeps it: it may be the one purchase
     * there you filed elsewhere on purpose. A dismissed row is left out, since it
     * is in no list and no total, and so is a split, whose parts carry its categories.
     *
     * The review time is deliberately not stamped. It is what locks the parser out
     * of a row, and labelling forty purchases must not stop a parser fix from
     * repairing them; [TransactionDao.insertOrUpdate] already keeps the category through a
     * re-read.
     */
    @Query(
        """
        UPDATE expense_transaction SET categoryId = :categoryId
        WHERE merchantId = :merchantId AND kind = :kind AND categoryId IS NULL
          AND status != 'DISMISSED' AND id IS NOT :exceptId AND """ + NOT_SPLIT + """
        """,
    )
    suspend fun categoriseAtMerchant(
        merchantId: Long,
        kind: TransactionKind,
        categoryId: Long,
        exceptId: Long?,
    ): Int

    /** How many rows [categoriseAtMerchant] would change, kept current as the ledger changes. */
    @Query(
        """
        SELECT COUNT(*) FROM expense_transaction
        WHERE merchantId = :merchantId AND kind = :kind AND categoryId IS NULL
          AND status != 'DISMISSED' AND id IS NOT :exceptId AND """ + NOT_SPLIT + """
        """,
    )
    fun uncategorisedAtMerchant(
        merchantId: Long,
        kind: TransactionKind,
        exceptId: Long?,
    ): Flow<Int>

    /**
     * [categoriseAtMerchant] for transfers, which have no merchant: the account
     * they went into says what they were for.
     */
    @Query(
        """
        UPDATE expense_transaction SET categoryId = :categoryId
        WHERE toAccountId = :accountId AND kind = 'TRANSFER' AND categoryId IS NULL
          AND status != 'DISMISSED' AND id IS NOT :exceptId AND """ + NOT_SPLIT + """
        """,
    )
    suspend fun categoriseTransfersInto(
        accountId: Long,
        categoryId: Long,
        exceptId: Long?,
    ): Int

    @Query(
        """
        SELECT COUNT(*) FROM expense_transaction
        WHERE toAccountId = :accountId AND kind = 'TRANSFER' AND categoryId IS NULL
          AND status != 'DISMISSED' AND id IS NOT :exceptId AND """ + NOT_SPLIT + """
        """,
    )
    fun uncategorisedTransfersInto(
        accountId: Long,
        exceptId: Long?,
    ): Flow<Int>

    /**
     * Removes a category and every trace of it: the transactions and the parts that
     * carried it are left with none, and merchants, accounts, recurring transactions and
     * templates stop giving it. A category moves no balance, so this loses a label and never an
     * amount.
     */
    @Transaction
    suspend fun delete(id: Long) {
        clearFromTransactions(id)
        clearFromMerchants(id)
        clearFromAccounts(id)
        clearFromRecurring(id)
        clearFromTemplates(id)
        clearFromParts(id)
        deleteRow(id)
    }

    @Query("DELETE FROM category WHERE id = :id")
    suspend fun deleteRow(id: Long)

    /** Null takes the category out of any group. */
    @Query("UPDATE category SET groupId = :groupId WHERE id = :id")
    suspend fun moveToGroup(
        id: Long,
        groupId: Long?,
    )

    /** [moveToGroup] for a category in none; one already in a group stays where it is. */
    @Query("UPDATE category SET groupId = :groupId WHERE id = :id AND groupId IS NULL")
    suspend fun groupIfNone(
        id: Long,
        groupId: Long,
    )
}

/** The groups categories are arranged under. */
@Dao
interface CategoryGroupDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(group: CategoryGroupEntity): Long

    @Query("SELECT * FROM category_group WHERE normalisedName = :normalisedName")
    suspend fun byNormalisedName(normalisedName: String): CategoryGroupEntity?

    @Query("SELECT * FROM category_group ORDER BY id")
    suspend fun allOnce(): List<CategoryGroupEntity>

    @Query("SELECT * FROM category_group ORDER BY normalisedName")
    fun all(): Flow<List<CategoryGroupEntity>>

    /**
     * Returns 0 and changes nothing when another group already answers to the new
     * name, as [CategoryDao.rename] does for a category.
     */
    @Query("UPDATE OR IGNORE category_group SET name = :name, normalisedName = :normalisedName WHERE id = :id")
    suspend fun rename(
        id: Long,
        name: String,
        normalisedName: String,
    ): Int

    /**
     * Removes a group and leaves its categories in none. Every transaction keeps
     * its category, since a group only arranges them.
     */
    @Transaction
    suspend fun delete(id: Long) {
        ungroup(id)
        deleteRow(id)
    }

    @Query("UPDATE category SET groupId = NULL WHERE groupId = :id")
    suspend fun ungroup(id: Long)

    @Query("DELETE FROM category_group WHERE id = :id")
    suspend fun deleteRow(id: Long)
}

@Dao
interface RecurringTransactionDao {
    @Insert
    suspend fun insert(recurring: RecurringTransactionEntity): Long

    /** For an import: a schedule whose [RecurringTransactionEntity.uuid] is already here is left alone. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(recurring: RecurringTransactionEntity): Long

    @Update
    suspend fun update(recurring: RecurringTransactionEntity)

    @Query("SELECT * FROM recurring_transaction WHERE id = :id")
    suspend fun byId(id: Long): RecurringTransactionEntity?

    @Query("SELECT * FROM recurring_transaction WHERE uuid = :uuid")
    suspend fun byUuid(uuid: String): RecurringTransactionEntity?

    /** Every schedule, the soonest next day first, and any that has ended last. */
    @Query(
        """
        SELECT r.*, m.displayName AS merchantName, a.name AS accountName, a.type AS accountType,
               d.name AS toAccountName, d.type AS toAccountType, c.name AS categoryName
        FROM recurring_transaction r
        LEFT JOIN merchant m ON m.id = r.merchantId
        LEFT JOIN account a ON a.id = r.accountId
        LEFT JOIN account d ON d.id = r.toAccountId
        LEFT JOIN category c ON c.id = r.categoryId
        ORDER BY r.nextDueEpochDay IS NULL, r.nextDueEpochDay, r.id
        """,
    )
    fun allWithNames(): Flow<List<RecurringWithNames>>

    @Query(
        """
        SELECT r.*, m.displayName AS merchantName, a.name AS accountName, a.type AS accountType,
               d.name AS toAccountName, d.type AS toAccountType, c.name AS categoryName
        FROM recurring_transaction r
        LEFT JOIN merchant m ON m.id = r.merchantId
        LEFT JOIN account a ON a.id = r.accountId
        LEFT JOIN account d ON d.id = r.toAccountId
        LEFT JOIN category c ON c.id = r.categoryId
        WHERE r.id = :id
        """,
    )
    suspend fun withNamesById(id: Long): RecurringWithNames?

    @Query("SELECT * FROM recurring_transaction ORDER BY id")
    suspend fun allOnce(): List<RecurringTransactionEntity>

    /** The schedules with a day that has come by [epochDay]. */
    @Query("SELECT * FROM recurring_transaction WHERE nextDueEpochDay <= :epochDay ORDER BY id")
    suspend fun dueBy(epochDay: Int): List<RecurringTransactionEntity>

    @Query("UPDATE recurring_transaction SET nextDueEpochDay = :nextDueEpochDay WHERE id = :id")
    suspend fun moveNextDue(
        id: Long,
        nextDueEpochDay: Int?,
    )

    @Query("SELECT COUNT(*) FROM recurring_transaction WHERE nextDueEpochDay IS NOT NULL")
    fun activeCount(): Flow<Int>

    /**
     * Ends a schedule. The rows it wrote stay in the ledger, as the history of
     * what was paid, and stop naming it.
     */
    @Transaction
    suspend fun stop(id: Long) {
        releaseTransactions(id)
        deleteRow(id)
    }

    @Query("UPDATE expense_transaction SET recurringId = NULL WHERE recurringId = :id")
    suspend fun releaseTransactions(id: Long)

    /** [stop] for every schedule with no day left to come. */
    @Transaction
    suspend fun removeEnded() {
        releaseEndedTransactions()
        deleteEnded()
    }

    @Query(
        """
        UPDATE expense_transaction SET recurringId = NULL
        WHERE recurringId IN (SELECT id FROM recurring_transaction WHERE nextDueEpochDay IS NULL)
        """,
    )
    suspend fun releaseEndedTransactions()

    @Query("DELETE FROM recurring_transaction WHERE nextDueEpochDay IS NULL")
    suspend fun deleteEnded()

    @Query("DELETE FROM recurring_transaction WHERE id = :id")
    suspend fun deleteRow(id: Long)
}

/** A schedule with the names of what it points at, for the list of them. */
data class RecurringWithNames(
    @Embedded val recurring: RecurringTransactionEntity,
    val merchantName: String?,
    val accountName: String?,
    val accountType: AccountType?,
    val toAccountName: String?,
    val toAccountType: AccountType?,
    val categoryName: String?,
)

@Dao
interface TransactionTemplateDao {
    @Insert
    suspend fun insert(template: TransactionTemplateEntity): Long

    /** For an import: a template whose [TransactionTemplateEntity.uuid] is already here is left alone. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(template: TransactionTemplateEntity): Long

    @Update
    suspend fun update(template: TransactionTemplateEntity)

    @Query("SELECT * FROM transaction_template WHERE id = :id")
    suspend fun byId(id: Long): TransactionTemplateEntity?

    /** Every template, the one last used first, and those never used after them, the newest first. */
    @Query(
        """
        SELECT t.*, m.displayName AS merchantName, a.name AS accountName, a.type AS accountType,
               d.name AS toAccountName, d.type AS toAccountType, c.name AS categoryName
        FROM transaction_template t
        LEFT JOIN merchant m ON m.id = t.merchantId
        LEFT JOIN account a ON a.id = t.accountId
        LEFT JOIN account d ON d.id = t.toAccountId
        LEFT JOIN category c ON c.id = t.categoryId
        ORDER BY t.lastUsedAtUtcMillis IS NULL, t.lastUsedAtUtcMillis DESC, t.id DESC
        """,
    )
    fun allWithNames(): Flow<List<TemplateWithNames>>

    @Query(
        """
        SELECT t.*, m.displayName AS merchantName, a.name AS accountName, a.type AS accountType,
               d.name AS toAccountName, d.type AS toAccountType, c.name AS categoryName
        FROM transaction_template t
        LEFT JOIN merchant m ON m.id = t.merchantId
        LEFT JOIN account a ON a.id = t.accountId
        LEFT JOIN account d ON d.id = t.toAccountId
        LEFT JOIN category c ON c.id = t.categoryId
        WHERE t.id = :id
        """,
    )
    suspend fun withNamesById(id: Long): TemplateWithNames?

    @Query("SELECT * FROM transaction_template ORDER BY id")
    suspend fun allOnce(): List<TransactionTemplateEntity>

    @Query("SELECT COUNT(*) FROM transaction_template")
    fun count(): Flow<Int>

    @Query("UPDATE transaction_template SET lastUsedAtUtcMillis = :atUtcMillis WHERE id = :id")
    suspend fun markUsed(
        id: Long,
        atUtcMillis: Long,
    )

    @Query("DELETE FROM transaction_template WHERE id = :id")
    suspend fun delete(id: Long)
}

/** A template with the names of what it points at, for the list of them. */
data class TemplateWithNames(
    @Embedded val template: TransactionTemplateEntity,
    val merchantName: String?,
    val accountName: String?,
    val accountType: AccountType?,
    val toAccountName: String?,
    val toAccountType: AccountType?,
    val categoryName: String?,
)

/** A category with what uses it. */
data class CategoryWithCounts(
    @Embedded val category: CategoryEntity,
    val transactionCount: Int,
    val merchantCount: Int,
    val accountCount: Int,
)

/**
 * A transaction with the display name of the merchant it resolved to, the account
 * it came from, and — on a transfer — the one it went to.
 *
 * [accountLast4] is the account's digits rather than the notification's: a manual
 * entry has no notification, and an account you added carries its digits only on
 * the account row. [accountType] rides along so a screen can say "Cash" without
 * looking the account up again.
 */
data class TransactionWithMerchant(
    @Embedded val transaction: ExpenseTransactionEntity,
    val merchantName: String?,
    val accountName: String? = null,
    val accountLast4: String? = null,
    val accountType: AccountType? = null,
    val toAccountName: String? = null,
    val toAccountLast4: String? = null,
    val toAccountType: AccountType? = null,
    val categoryName: String? = null,
)

/** The account last used at a merchant, keyed on the name the normaliser gives it. */
data class MerchantLastAccount(
    val normalisedName: String,
    val accountId: Long,
)

/** An account with how many confirmed transactions it carries, transfers included, and what it holds now. */
data class AccountWithCount(
    @Embedded val account: AccountEntity,
    val transactionCount: Int,
    /** In minor units of the currency [AccountDao.allWithCounts] was asked for. */
    val balanceMinor: Long,
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

/**
 * What went to one category over a period, with the group the category is in
 * now. Every name is null on the row for what has no category, and the group's
 * are null for a category in none.
 */
data class CategoryTotal(
    val categoryId: Long?,
    val categoryName: String?,
    val groupId: Long?,
    val groupName: String?,
    val totalMinor: Long,
    val count: Int,
)

/**
 * A transfer counts by the two accounts it joins, and these are that rule in
 * SQL: `Transaction.signedAmount` is the same rule in Kotlin, and
 * `ReportRepositoryTest` holds the two to the same cent (ADR-0017).
 *
 * A bank account is outside what the ledger spends from, so money moved into
 * one has left and money moved out of one has come back. `NULL <> 'BANK'` is
 * not true, which is what leaves out a transfer with an account missing.
 */
private const val TRANSFER_ENDS =
    "LEFT JOIN account origin ON origin.id = t.accountId " +
        "LEFT JOIN account destination ON destination.id = t.toAccountId"
private const val MONEY_OUT =
    "(t.kind = 'EXPENSE' OR (t.kind = 'TRANSFER' AND destination.type = 'BANK' AND origin.type <> 'BANK'))"
private const val MONEY_IN =
    "(t.kind = 'INCOME' OR (t.kind = 'TRANSFER' AND origin.type = 'BANK' AND destination.type <> 'BANK'))"

/** The confirmed rows that went out in one currency over a range of days, which every spending figure counts. */
private const val SPENT_IN_RANGE =
    "t.status = 'CONFIRMED' AND " + MONEY_OUT +
        " AND t.currency = :currency AND t.localDateEpochDay BETWEEN :fromDay AND :toDay"

/**
 * Reports are SQL, not Kotlin loops over the whole table. Every one of these
 * groups on an indexed integer column, so a year of history costs the same as a
 * week.
 *
 * They are about what went out, so every one but [incomeForRange] keeps to
 * [MONEY_OUT]: a paycheque ranked beside the groceries would make the ranking
 * wrong, and a card payment counted as spending would charge you twice for the
 * purchases it settles.
 */
@Dao
interface ReportDao {
    @Query(
        """
        SELECT t.localDateEpochDay AS day, SUM(t.amountMinor) AS totalMinor, COUNT(*) AS count
        FROM expense_transaction t
        """ + TRANSFER_ENDS + """
        WHERE t.status = 'CONFIRMED'
          AND """ + MONEY_OUT + """
          AND t.currency = :currency
          AND t.localDateEpochDay BETWEEN :fromDay AND :toDay
        GROUP BY t.localDateEpochDay
        ORDER BY t.localDateEpochDay
        """,
    )
    fun dailyTotals(
        fromDay: Int,
        toDay: Int,
        currency: String,
    ): Flow<List<DailyTotal>>

    @Query(
        """
        SELECT SUM(t.amountMinor)
        FROM expense_transaction t
        """ + TRANSFER_ENDS + """
        WHERE t.status = 'CONFIRMED'
          AND """ + MONEY_OUT + """
          AND t.currency = :currency
          AND t.localDateEpochDay BETWEEN :fromDay AND :toDay
        """,
    )
    fun totalForRange(
        fromDay: Int,
        toDay: Int,
        currency: String,
    ): Flow<Long?>

    @Query(
        """
        SELECT SUM(t.amountMinor)
        FROM expense_transaction t
        """ + TRANSFER_ENDS + """
        WHERE t.status = 'CONFIRMED'
          AND """ + MONEY_IN + """
          AND t.currency = :currency
          AND t.localDateEpochDay BETWEEN :fromDay AND :toDay
        """,
    )
    fun incomeForRange(
        fromDay: Int,
        toDay: Int,
        currency: String,
    ): Flow<Long?>

    /**
     * What [dailyTotals] counts, by category instead of by day, so the rows add up
     * to the period's total. The group is joined here rather than stored on the
     * transaction: moving a category moves its history (ADR-0016).
     *
     * A split goes to each category by the amount of its parts, which add up to
     * its own, so the totals still add up to the period's (ADR-0018). It is
     * counted once under each category it touches, so the counts no longer do.
     */
    @Query(
        """
        SELECT c.id AS categoryId, c.name AS categoryName, g.id AS groupId, g.name AS groupName,
               SUM(s.amountMinor) AS totalMinor, COUNT(DISTINCT s.transactionId) AS count
        FROM (
            SELECT t.id AS transactionId, t.categoryId AS categoryId, t.amountMinor AS amountMinor
            FROM expense_transaction t
            """ + TRANSFER_ENDS + """
            WHERE """ + SPENT_IN_RANGE + """
              AND NOT EXISTS (SELECT 1 FROM transaction_part p WHERE p.transactionId = t.id)
            UNION ALL
            SELECT t.id, p.categoryId, p.amountMinor
            FROM expense_transaction t
            """ + TRANSFER_ENDS + """
            JOIN transaction_part p ON p.transactionId = t.id
            WHERE """ + SPENT_IN_RANGE + """
        ) s
        LEFT JOIN category c ON c.id = s.categoryId
        LEFT JOIN category_group g ON g.id = c.groupId
        GROUP BY c.id
        """,
    )
    fun categoryTotals(
        fromDay: Int,
        toDay: Int,
        currency: String,
    ): Flow<List<CategoryTotal>>

    /**
     * How many rows in the range the figures above would have counted, and left
     * out because they were in something other than [currency]. A transfer that
     * moves the ledger by nothing is not among them: it is in no total here, so
     * leaving one out understates nothing.
     *
     * Converting them is impossible here — a rate needs a network, and there is
     * none — so a report can only decline to count them. Declining silently is
     * the failure mode this exists to prevent: it is the same rule that puts the
     * unreviewed count beside the ledger's balance, applied to the other reason a
     * total can understate.
     */
    @Query(
        """
        SELECT COUNT(*)
        FROM expense_transaction t
        """ + TRANSFER_ENDS + """
        WHERE t.status = 'CONFIRMED'
          AND (""" + MONEY_OUT + " OR " + MONEY_IN + """)
          AND t.currency != :currency
          AND t.localDateEpochDay BETWEEN :fromDay AND :toDay
        """,
    )
    fun otherCurrencyCount(
        fromDay: Int,
        toDay: Int,
        currency: String,
    ): Flow<Int>
}
