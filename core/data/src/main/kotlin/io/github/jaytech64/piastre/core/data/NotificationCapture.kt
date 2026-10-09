// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.core.data

import io.github.jaytech64.piastre.parsing.RawNotification
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single call the listener makes: store, then interpret.
 *
 * Splitting it any further would put the ordering in the caller's hands, and the
 * order is the whole design — a payload that is on disk before anyone reads it is
 * a payload a parser fix can still repair years later.
 */
@Singleton
class NotificationCapture
    @Inject
    constructor(
        private val rawEvents: RawEventRepository,
        private val pipeline: ParsePipeline,
    ) {
        /**
         * A post already recorded is not parsed again: it is a replay of something
         * this app has already seen, so the ledger row it would produce already
         * exists. The rare case where it was recorded but never parsed — the process
         * died in between — belongs to [ParsePipeline.drainPending], not here.
         */
        suspend fun capture(
            notification: RawNotification,
            extrasJson: String,
        ) {
            val eventId = rawEvents.record(notification, extrasJson) ?: return
            pipeline.parse(eventId)
        }
    }
