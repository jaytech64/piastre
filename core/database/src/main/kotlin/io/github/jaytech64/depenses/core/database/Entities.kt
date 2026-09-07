package io.github.jaytech64.depenses.core.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.jaytech64.depenses.core.model.AliasOrigin
import io.github.jaytech64.depenses.core.model.MatchType
import io.github.jaytech64.depenses.core.model.ParseState
import io.github.jaytech64.depenses.core.model.TransactionSource
import io.github.jaytech64.depenses.core.model.TransactionStatus

/**
 * The notification exactly as it arrived, stored before anything tries to
 * understand it.
 *
 * This is what makes the pipeline re-runnable: a parser fix re-reads history
 * instead of only improving the future, and a payload no parser handles today is
 * still on disk when one is written for it.
 */
@Entity(
    tableName = "raw_notification_event",
    indices = [Index("notificationKey"), Index("postedAtUtcMillis"), Index("parseState")],
)
data class RawNotificationEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourcePackage: String,
    val notificationKey: String,
    val postedAtUtcMillis: Long,
    val deviceLocale: String,
    val title: String?,
    val text: String?,
    val bigText: String?,
    val subText: String?,
    val isGroupSummary: Boolean,
    val extrasJson: String,
    val parseState: ParseState,
    val parsedByVersion: Int?,
)

/**
 * Named `expense_transaction` rather than `transaction`, which is a SQLite
 * reserved word you would otherwise be backticking forever.
 */
@Entity(
    tableName = "expense_transaction",
    indices = [
        Index(value = ["sourcePackage", "sourceKey"], unique = true),
        Index("localDateEpochDay"),
        Index("merchantId"),
        Index("status"),
    ],
)
data class ExpenseTransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val rawEventId: Long?,
    val amountMinor: Long,
    val currency: String,
    val occurredAtUtcMillis: Long,
    val zoneId: String,
    val localDateEpochDay: Int,
    val merchantId: Long?,
    val merchantRaw: String?,
    val categoryId: Long?,
    val source: TransactionSource,
    val status: TransactionStatus,
    val confidence: Float,
    val parserId: String?,
    val parserVersion: Int?,
    val sourcePackage: String,
    val sourceKey: String,
    val cardLabel: String?,
    val cardLast4: String?,
    val note: String?,
)

@Entity(tableName = "merchant")
data class MerchantEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val displayName: String,
    val normalisedName: String,
)

/**
 * Maps a raw merchant string to a canonical merchant. Renaming a merchant
 * rewrites the alias, not history, so every past and future transaction follows
 * along with no data migration.
 */
@Entity(
    tableName = "merchant_alias",
    indices = [Index(value = ["pattern"], unique = true), Index("merchantId")],
)
data class MerchantAliasEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val merchantId: Long,
    val pattern: String,
    val matchType: MatchType,
    val createdBy: AliasOrigin,
)
