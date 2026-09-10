// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.data

import io.github.jaytech64.depenses.core.database.ExpenseTransactionEntity
import io.github.jaytech64.depenses.core.database.RawNotificationEventDao
import io.github.jaytech64.depenses.core.database.RawNotificationEventEntity
import io.github.jaytech64.depenses.core.database.TransactionDao
import io.github.jaytech64.depenses.core.model.ParseState
import io.github.jaytech64.depenses.core.model.ReviewReason
import io.github.jaytech64.depenses.core.model.TransactionSource
import io.github.jaytech64.depenses.core.model.TransactionStatus
import io.github.jaytech64.depenses.core.model.localDateEpochDay
import io.github.jaytech64.depenses.parsing.NotificationParser
import io.github.jaytech64.depenses.parsing.ParseResult
import io.github.jaytech64.depenses.parsing.ParserRegistry
import io.github.jaytech64.depenses.parsing.RawNotification
import io.github.jaytech64.depenses.parsing.TransactionDraft
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Turns recorded notifications into ledger rows.
 *
 * Runs against rows that are already durable, never against a live notification,
 * which is what lets the same code serve two callers: the listener parsing a row
 * it just wrote, and the catch-up pass parsing rows an earlier run never reached.
 * Both are idempotent, because [TransactionDao.insertOrUpdate] resolves on the
 * transaction's own identity rather than on anything about when it was parsed.
 */
@Singleton
class ParsePipeline
    @Inject
    constructor(
        private val events: RawNotificationEventDao,
        private val transactions: TransactionDao,
        private val registry: ParserRegistry,
        private val merchants: MerchantRepository,
        private val zoneId: Provider<ZoneId>,
    ) {
        suspend fun parse(eventId: Long) {
            events.byId(eventId)?.let { interpret(it) }
        }

        /**
         * Parses everything the inline path never reached, and returns how many rows
         * that was.
         *
         * A row is left pending when the process dies between the write and the
         * parse. The listener cannot notice this on its own: the next rebind replays
         * the same notification, the raw log correctly refuses the duplicate, and the
         * inline parse is skipped along with it. This pass is the only thing that
         * closes that gap.
         */
        suspend fun drainPending(): Int {
            val pending = events.byState(ParseState.PENDING)
            pending.forEach { interpret(it) }
            return pending.size
        }

        /**
         * Re-reads every recorded notification an older parser version produced, and
         * returns how many rows that was.
         *
         * This is the raw log paying for itself. A parser fix repairs the history it
         * got wrong instead of only improving the future, because the payload is
         * still on disk exactly as it arrived — which is the entire argument for
         * keeping the most sensitive table in the app at all.
         */
        suspend fun resweep(): Int {
            val stale = registry.reach.flatMap { events.parsedBelowVersion(it.packages, it.version) }
            stale.forEach { event -> transactions.deleteSupersededBy(event.id, interpret(event)) }
            return stale.size
        }

        /**
         * Reads one recorded notification and returns the ledger row it produced, or
         * [TransactionDao.NOTHING_RECORDED] if it produced none.
         *
         * Only [resweep] acts on that id. On the two paths that read an event for the
         * first time nothing can already point at it, so the tidy-up would be a table
         * scan per captured notification to delete nothing.
         */
        private suspend fun interpret(event: RawNotificationEventEntity): Long {
            val notification = event.toRawNotification()
            val parser = registry.parserFor(notification)
            val recorded =
                when (val result = registry.parse(notification)) {
                    is ParseResult.Parsed -> {
                        record(event, parser, result.draft, TransactionStatus.CONFIRMED, result.confidence)
                    }

                    is ParseResult.NeedsReview -> {
                        result.draft?.let {
                            record(event, parser, it, TransactionStatus.NEEDS_REVIEW, UNCONFIRMED, result.reason)
                        } ?: markUnread(event, parser?.version)
                    }

                    is ParseResult.Ignored -> {
                        events.markParsed(event.id, ParseState.IGNORED, parser?.version)
                        TransactionDao.NOTHING_RECORDED
                    }

                    ParseResult.NotApplicable -> {
                        markUnread(event, version = null)
                    }
                }
            return recorded
        }

        private suspend fun markUnread(
            event: RawNotificationEventEntity,
            version: Int?,
        ): Long {
            events.markParsed(event.id, ParseState.UNPARSED, version)
            return TransactionDao.NOTHING_RECORDED
        }

        private suspend fun record(
            event: RawNotificationEventEntity,
            parser: NotificationParser?,
            draft: TransactionDraft,
            status: TransactionStatus,
            confidence: Float,
            reviewReason: ReviewReason? = null,
        ): Long {
            val id =
                transactions.insertOrUpdate(
                    event.toTransaction(
                        parser,
                        draft,
                        status,
                        confidence,
                        reviewReason,
                        merchants.resolve(draft.merchantRaw),
                    ),
                )
            events.markParsed(event.id, ParseState.PARSED, parser?.version)
            return id
        }

        /**
         * The local day is resolved here rather than at read time, in the zone the
         * phone is in at the moment of the purchase. Reading it later would let a
         * flight re-bucket last month's coffee.
         */
        private fun RawNotificationEventEntity.toTransaction(
            parser: NotificationParser?,
            draft: TransactionDraft,
            status: TransactionStatus,
            confidence: Float,
            reviewReason: ReviewReason?,
            merchantId: Long?,
        ): ExpenseTransactionEntity {
            val zone = zoneId.get()
            return ExpenseTransactionEntity(
                rawEventId = id,
                amountMinor = draft.amount.minor,
                currency = draft.amount.currency,
                occurredAtUtcMillis = draft.occurredAtUtcMillis,
                zoneId = zone.id,
                localDateEpochDay = localDateEpochDay(draft.occurredAtUtcMillis, zone),
                merchantId = merchantId,
                merchantRaw = draft.merchantRaw,
                categoryId = null,
                source = TransactionSource.NOTIFICATION,
                status = status,
                reviewReason = reviewReason,
                reviewedAtUtcMillis = null,
                confidence = confidence,
                parserId = parser?.id,
                parserVersion = parser?.version,
                sourcePackage = draft.sourcePackage,
                sourceKey = draft.sourceKey,
                cardLabel = draft.cardLabel,
                cardLast4 = draft.cardLast4,
                note = null,
            )
        }

        private companion object {
            /** A draft that needs a human carries no confidence by definition. */
            const val UNCONFIRMED = 0f
        }
    }

internal fun RawNotificationEventEntity.toRawNotification(): RawNotification =
    RawNotification(
        sourcePackage = sourcePackage,
        notificationKey = notificationKey,
        postedAtUtcMillis = postedAtUtcMillis,
        deviceLocale = deviceLocale,
        title = title,
        text = text,
        bigText = bigText,
        subText = subText,
        isGroupSummary = isGroupSummary,
    )
