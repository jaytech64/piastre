// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Where a transaction came from. Manual entry exists because Wallet is not the only way to pay. */
enum class TransactionSource { NOTIFICATION, MANUAL }

/**
 * NEEDS_REVIEW is not an error state: it is the parser declining to guess, which
 * is the behaviour that keeps the ledger trustworthy.
 */
enum class TransactionStatus { CONFIRMED, NEEDS_REVIEW, DISMISSED }

/**
 * Why a parser declined to enter a purchase in the ledger unattended.
 *
 * Persisted on the transaction rather than recomputed when the review inbox is
 * opened: it is what the parser actually decided at the time, and re-deriving it
 * later would mean reimplementing the parser's judgement in the screen that
 * exists to question it.
 */
enum class ReviewReason {
    /** Amount read, but the surrounding format was not fully recognised. */
    UNRECOGNISED_FORMAT,

    /** No merchant in the notification. */
    MISSING_MERCHANT,

    /** The transaction has no stable identity, so it cannot be de-duplicated safely. */
    MISSING_SOURCE_KEY,
}

/** How far a raw notification has travelled through the parse pipeline. */
enum class ParseState { PENDING, PARSED, UNPARSED, IGNORED }

/** How a merchant alias pattern is matched against a raw merchant string. */
enum class MatchType { EXACT, PREFIX, REGEX }

/** A user-made alias always outranks one the parser invented. */
enum class AliasOrigin { AUTO, USER }

data class Transaction(
    val id: Long,
    val amount: Money,
    val occurredAt: Instant,
    val zoneId: ZoneId,
    val localDate: LocalDate,
    val merchantRaw: String?,
    val merchantId: Long?,
    val merchantName: String?,
    val source: TransactionSource,
    val status: TransactionStatus,
    val cardLabel: String?,
    val cardLast4: String?,
    val note: String?,
) {
    /**
     * What to put on screen. The canonical merchant if the raw string resolved to
     * one, the raw string if it did not, and null only when the notification
     * carried no merchant at all — which is a case the review inbox owns.
     */
    val displayMerchant: String? get() = merchantName ?: merchantRaw
}

/**
 * One purchase waiting for a human, with the notification it was read from.
 *
 * The raw title and text are carried verbatim and never cleaned up on the way
 * through: the inbox exists to ask whether the parser read them correctly, and a
 * tidied string cannot answer that. It is also what makes a correction
 * reproducible as a parser fixture afterwards.
 */
data class ReviewItem(
    val transaction: Transaction,
    val reason: ReviewReason?,
    val sourceName: String,
    val rawTitle: String?,
    val rawText: String?,
    val postedAt: Instant?,
)

data class Merchant(
    val id: Long,
    val displayName: String,
    val normalisedName: String,
)
