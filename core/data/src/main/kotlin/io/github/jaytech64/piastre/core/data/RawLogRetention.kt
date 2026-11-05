// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.core.data

import io.github.jaytech64.piastre.core.database.RawNotificationEventDao
import io.github.jaytech64.piastre.core.model.ParseState
import java.time.Clock
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stops the raw log holding lock-screen text for ever.
 *
 * The payload is kept because it is what lets a parser fix repair history, not
 * because it is worth keeping — and after 90 days that argument has run out
 * while the privacy cost has not. See `docs/adr/0001-raw-log-90-day-purge.md`.
 *
 * The row survives its own redaction. Its id, key, post time and parse state are
 * what collapse a replay, and a shade that hands back a two-year-old
 * notification must not be able to turn it into a second purchase.
 */
@Singleton
class RawLogRetention
    @Inject
    constructor(
        private val events: RawNotificationEventDao,
        private val clock: Clock,
    ) {
        /** Strips everything over [RETENTION] old, and returns how many rows changed. */
        suspend fun purgeExpired(): Int {
            val now = clock.instant()
            return events.redactOlderThan(
                cutoffUtcMillis = now.minus(RETENTION).toEpochMilli(),
                redactedAtUtcMillis = now.toEpochMilli(),
                exempt = KEPT_WHOLE,
            )
        }

        private companion object {
            val RETENTION: Duration = Duration.ofDays(90)

            /**
             * A pending payload has not been read at all yet, and stripping it would
             * lose the purchase it holds. One no parser could read is stripped like
             * any other: the privacy policy promises 90 days without exception.
             */
            val KEPT_WHOLE = listOf(ParseState.PENDING)
        }
    }
