// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.jaytech64.piastre.core.model.AccountOrigin
import io.github.jaytech64.piastre.core.model.AccountType
import io.github.jaytech64.piastre.core.model.AliasOrigin
import io.github.jaytech64.piastre.core.model.MatchType
import io.github.jaytech64.piastre.core.model.ParseState
import io.github.jaytech64.piastre.core.model.ReviewReason
import io.github.jaytech64.piastre.core.model.Rhythm
import io.github.jaytech64.piastre.core.model.TransactionKind
import io.github.jaytech64.piastre.core.model.TransactionSource
import io.github.jaytech64.piastre.core.model.TransactionStatus

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
        Index("categoryId"),
        Index("recurringId"),
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
    /**
     * Yours to set, so a re-parse keeps it. A merchant's category is copied in
     * when the row is recorded rather than read through at display time: see
     * [MerchantEntity.categoryId].
     */
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
    /**
     * The schedule this row belongs to while that schedule lasts: the typed row
     * it was made from, and every row it has written since. Null on anything
     * else, and cleared on every row when the schedule is stopped.
     */
    val recurringId: Long? = null,
)

/**
 * One share of a transaction's amount, and what that share was for.
 *
 * A split is still one transaction: its row in `expense_transaction` keeps the
 * whole amount, the account, the day and the merchant, so every balance goes on
 * reading that row and nothing else. The parts only divide the amount among
 * categories, and only what reads a category reads them.
 *
 * While a transaction has parts its own [ExpenseTransactionEntity.categoryId] is
 * null, and the parts' amounts add up to its amount to the minor unit; both are
 * held by the one place that writes them, `settleCategory` in `:core:data`.
 * A part is in the currency of its transaction, so it carries none.
 *
 * [categoryId] is written onto the part, as it is onto a transaction, and null
 * is a share nobody has said anything about yet. [position] is the order you
 * typed the parts in, from 0.
 *
 * [toAccountId] makes the share a transfer: it left the transaction's account
 * for this one, so it adds to this account's balance, and the whole ledger
 * counts it by the two accounts it joins rather than as spending (ADR-0019).
 */
@Entity(
    tableName = "transaction_part",
    indices = [Index("transactionId"), Index("categoryId"), Index("toAccountId")],
)
data class TransactionPartEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val transactionId: Long,
    val position: Int,
    val amountMinor: Long,
    val categoryId: Long?,
    val note: String?,
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
    /**
     * Where the account sits in every list of accounts, from 0. You set it by
     * moving the account in Settings; a new one takes the next free place, at the end.
     */
    @ColumnInfo(defaultValue = "0") val sortOrder: Int = 0,
    /**
     * The category every transfer into this account is given as it is recorded,
     * or null. What an account receives says what a transfer was for — a card
     * payment, the mortgage, savings — the way a merchant says it for a purchase.
     */
    val categoryId: Long? = null,
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
    /**
     * The category every purchase here is given as it is recorded, or null.
     *
     * Copied into each transaction rather than read through at display time, so
     * changing it changes the purchases to come and never the ones already in the
     * ledger — past and future are separate choices, and a purchase you
     * categorised differently by hand stays the way you left it.
     */
    val categoryId: Long? = null,
)

/**
 * A kind of spending or income you named.
 *
 * [normalisedName] is unique for the same reason a merchant's is: two spellings
 * of one name — `Épicerie`, `epicerie` — must find one category rather than make
 * a second, which would quietly split every total that groups by it. It stays
 * unique across groups too, so a category's bare name is never ambiguous.
 *
 * [groupId] is read through wherever the group is shown, unlike the category on
 * a transaction: a group only arranges categories, so moving one must move its
 * history with it. Null is a category nobody has put in a group.
 *
 * [icon] is read through the same way, and holds the icon's name rather than a
 * resource id: an id changes from one build to the next, and an export has to
 * mean the same thing on the phone that reads it back. Null is no icon picked.
 */
@Entity(
    tableName = "category",
    indices = [Index(value = ["normalisedName"], unique = true), Index("groupId")],
)
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val normalisedName: String,
    val groupId: Long? = null,
    val icon: String? = null,
)

/**
 * What categories are arranged under, such as Maison. Nothing points at a group
 * but a category, so a transaction can never be given one.
 */
@Entity(
    tableName = "category_group",
    indices = [Index(value = ["normalisedName"], unique = true)],
)
data class CategoryGroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
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

/**
 * A transaction that comes back: what it is, and the days it falls on.
 *
 * Each time a day in [rhythm] comes, a row is written into `expense_transaction`
 * with exactly these fields, and from then on it is an ordinary transaction.
 * Nothing here is read through at display time, so changing a schedule changes
 * the rows still to come and never the ones already written.
 *
 * [nextDueEpochDay] is the cursor that makes a day happen once. It moves past a
 * day in the same database transaction that writes that day's row, so a row you
 * delete afterwards is never written again. It is null once [lastEpochDay] has
 * passed.
 *
 * [uuid] is the schedule's identity outside this database: each row it writes is
 * keyed on it and the day, so two passes racing over the same day collide on the
 * transaction table's unique index, and an import can tell a schedule it already
 * holds from a new one.
 */
@Entity(
    tableName = "recurring_transaction",
    indices = [Index(value = ["uuid"], unique = true)],
)
data class RecurringTransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    val amountMinor: Long,
    val currency: String,
    val kind: TransactionKind,
    val merchantId: Long?,
    val merchantRaw: String?,
    val accountId: Long?,
    val toAccountId: Long?,
    val categoryId: Long?,
    val note: String?,
    val rhythm: Rhythm,
    val firstEpochDay: Int,
    val lastEpochDay: Int?,
    val nextDueEpochDay: Int?,
)

/**
 * A transaction you type often, kept to fill the form with: what it is, and
 * nothing about when.
 *
 * It writes nothing by itself, and a transaction filled from it keeps no link
 * back, so changing or deleting a template never reaches a row already saved.
 *
 * [amountMinor] is null when the amount is different every time. [lastUsedAtUtcMillis]
 * is when a transaction filled from it was last saved, and null until one is:
 * it only decides the order templates are offered in.
 *
 * [uuid] is the template's identity outside this database, so an import can
 * tell one it already holds from a new one.
 */
@Entity(
    tableName = "transaction_template",
    indices = [Index(value = ["uuid"], unique = true)],
)
data class TransactionTemplateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    val name: String,
    val kind: TransactionKind,
    val amountMinor: Long?,
    val currency: String,
    val merchantId: Long?,
    val merchantRaw: String?,
    val accountId: Long?,
    val toAccountId: Long?,
    val categoryId: Long?,
    val note: String?,
    val lastUsedAtUtcMillis: Long?,
    /** Whether the transaction it fills has a day and no hour, because it comes back. */
    @ColumnInfo(defaultValue = "0") val recurring: Boolean = false,
)
