// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.jaytech64.depenses.core.model.AccountOrigin
import io.github.jaytech64.depenses.core.model.AccountType
import io.github.jaytech64.depenses.core.model.AliasOrigin
import io.github.jaytech64.depenses.core.model.MatchType
import io.github.jaytech64.depenses.core.model.ParseState
import io.github.jaytech64.depenses.core.model.ReviewReason
import io.github.jaytech64.depenses.core.model.TransactionKind
import io.github.jaytech64.depenses.core.model.TransactionSource
import io.github.jaytech64.depenses.core.model.TransactionStatus

/**
 * The notification exactly as it arrived, stored before anything tries to
 * understand it.
 *
 * This is what makes the pipeline re-runnable: a parser fix re-reads history
 * instead of only improving the future, and a payload no parser handles today is
 * still on disk when one is written for it.
 *
 * It is also the most sensitive table in the app, so the content does not stay
 * forever: see [RawNotificationEventDao.redactOlderThan] and
 * `docs/adr/0001-raw-log-90-day-purge.md`.
 *
 * The unique index separates two things that look identical and are not. A
 * *replay* is one post delivered twice, because the listener rebinds and hands
 * back everything still on the shade — measured at up to 51 hours of backlog —
 * and it must collapse. A *re-post* is Wallet publishing the same purchase again
 * with a fresh `postTime`, which is real evidence of how Wallet behaves and must
 * be kept. Keying on the post time is what tells them apart.
 */
@Entity(
    tableName = "raw_notification_event",
    indices = [
        Index(value = ["sourcePackage", "notificationKey", "postedAtUtcMillis"], unique = true),
        Index("notificationKey"),
        Index("postedAtUtcMillis"),
        Index("parseState"),
    ],
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
    /**
     * When the 90-day sweep stripped this row's content, or null while the
     * payload is still here.
     *
     * A marker rather than an inference. "Old and empty" is not the same fact as
     * "redacted" — a notification can genuinely arrive with no text — and the
     * re-parse sweep has to tell them apart: re-reading a row whose payload is
     * gone would conclude it was never a payment and take its transaction with it.
     */
    val redactedAtUtcMillis: Long? = null,
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
        Index("accountId"),
        Index("toAccountId"),
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
    /** Set only while [status] is NEEDS_REVIEW; cleared once a human has ruled. */
    val reviewReason: ReviewReason?,
    /**
     * When a human last ruled on this row. Non-null freezes the parser out: see
     * [TransactionDao.insertOrUpdate].
     */
    val reviewedAtUtcMillis: Long?,
    val confidence: Float,
    val parserId: String?,
    val parserVersion: Int?,
    val sourcePackage: String,
    val sourceKey: String,
    val cardLabel: String?,
    val cardLast4: String?,
    val note: String?,
    /** Yours to set, so a re-parse keeps it: see [TransactionDao.insertOrUpdate]. */
    @ColumnInfo(defaultValue = "EXPENSE") val kind: TransactionKind = TransactionKind.EXPENSE,
    /**
     * The account [cardLabel] and [cardLast4] resolved to, or the one you picked
     * for a manual entry, or the one a transfer left. The raw pair stays beside it
     * as the bank's evidence, the way [merchantRaw] stays beside [merchantId].
     */
    val accountId: Long? = null,
    /** Where a transfer landed; null on anything else. */
    val toAccountId: Long? = null,
)

/**
 * An account money sits in: cash, a card Wallet named, a bank account you typed.
 *
 * A card is identified by the issuer's label together with the last four digits —
 * the pair every notification carries. The digits alone collide across issuers
 * often enough to merge two cards into one. SQLite treats NULLs as distinct in a
 * unique index, so an account you add by hand, which has no issuer label, is
 * never refused for sharing its digits with another.
 */
@Entity(
    tableName = "account",
    indices = [Index(value = ["issuerLabel", "last4"], unique = true)],
)
data class AccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String?,
    val type: AccountType,
    val issuerLabel: String?,
    val last4: String?,
    val origin: AccountOrigin,
    /**
     * What the account held before any transaction the ledger knows about, in
     * minor units of the home currency, negative for money owed. Null when it was
     * never given — which an import tells apart from a balance you set to zero.
     */
    val openingBalanceMinor: Long? = null,
)

/**
 * The canonical merchant a raw string resolves to.
 *
 * [normalisedName] is unique because it *is* the identity: without the
 * constraint two sightings of one chain arriving close together would each fail
 * to find the other and create a second merchant, quietly splitting the ranking
 * the merchant table exists to make correct.
 */
@Entity(
    tableName = "merchant",
    indices = [Index(value = ["normalisedName"], unique = true)],
)
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
