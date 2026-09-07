// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.parsing

import io.github.jaytech64.depenses.core.model.Money

/**
 * What the parser proposes for one notification. [sourceKey] is the transaction's
 * identity and carries the uniqueness constraint downstream, so the same purchase
 * re-posted or replayed can never become two rows.
 */
data class TransactionDraft(
    val sourcePackage: String,
    val sourceKey: String,
    val merchantRaw: String?,
    val amount: Money,
    val cardLabel: String? = null,
    val cardLast4: String? = null,
    val occurredAtUtcMillis: Long,
)

enum class ReviewReason {
    /** Amount read, but the surrounding format was not fully recognised. */
    UNRECOGNISED_FORMAT,

    /** No merchant in the notification. */
    MISSING_MERCHANT,

    /** The transaction has no stable identity, so it cannot be de-duplicated safely. */
    MISSING_SOURCE_KEY,
}

enum class IgnoreReason {
    /** Android's empty roll-up of a notification group. */
    GROUP_SUMMARY,

    /** A payment notification that is not a completed charge. */
    NOT_A_PAYMENT,
}

sealed interface ParseResult {
    /** Confident enough to enter the ledger without asking. */
    data class Parsed(
        val draft: TransactionDraft,
        val confidence: Float,
    ) : ParseResult

    /** Understood, but something needs a human. */
    data class NeedsReview(
        val draft: TransactionDraft?,
        val reason: ReviewReason,
    ) : ParseResult

    /** Recognised and deliberately not recorded. */
    data class Ignored(
        val reason: IgnoreReason,
    ) : ParseResult

    /** Not this parser's notification at all. */
    data object NotApplicable : ParseResult
}
