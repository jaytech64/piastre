// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.data

import io.github.jaytech64.depenses.core.database.RawNotificationEventDao
import io.github.jaytech64.depenses.core.database.RawNotificationEventEntity
import io.github.jaytech64.depenses.core.model.ParseState
import io.github.jaytech64.depenses.parsing.RawNotification
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The write end of the capture pipeline: a notification lands here before
 * anything tries to understand it.
 *
 * Recording is deliberately the only thing this does. Deciding what a payload
 * means is a separate step against a row that is already durable, so a parser
 * that crashes, guesses wrong, or does not exist yet cannot cost you the
 * evidence.
 */
@Singleton
class RawEventRepository
    @Inject
    constructor(
        private val events: RawNotificationEventDao,
    ) {
        /**
         * Returns the id of the row that was written, or `null` when this exact post
         * was already recorded.
         *
         * A `null` is the ordinary case, not a failure: every listener rebind
         * replays the notification shade, and the caller uses the `null` to skip
         * parsing work it has already done.
         */
        suspend fun record(
            notification: RawNotification,
            extrasJson: String,
        ): Long? {
            val id =
                events.insert(
                    RawNotificationEventEntity(
                        sourcePackage = notification.sourcePackage,
                        notificationKey = notification.notificationKey,
                        postedAtUtcMillis = notification.postedAtUtcMillis,
                        deviceLocale = notification.deviceLocale,
                        title = notification.title,
                        text = notification.text,
                        bigText = notification.bigText,
                        subText = notification.subText,
                        isGroupSummary = notification.isGroupSummary,
                        extrasJson = extrasJson,
                        parseState = ParseState.PENDING,
                        parsedByVersion = null,
                    ),
                )
            return id.takeIf { it != RawNotificationEventDao.INSERT_IGNORED }
        }

        fun count(): Flow<Int> = events.count()
    }
