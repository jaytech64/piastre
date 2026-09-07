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
    val source: TransactionSource,
    val status: TransactionStatus,
    val cardLabel: String?,
    val cardLast4: String?,
    val note: String?,
)

data class Merchant(
    val id: Long,
    val displayName: String,
    val normalisedName: String,
)
