// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * The current local day, re-emitted when it changes.
 *
 * A screen that reads `LocalDate.now()` once is correct until midnight and then
 * silently wrong, still labelling yesterday "today" and totalling the wrong day.
 * Nothing else forces a refresh: a day with no spending produces no database
 * change to react to, which is exactly the day the error is easiest to believe.
 */
@Singleton
class LocalDateTicker
    @Inject
    constructor(
        private val clock: Clock,
        private val zoneId: Provider<ZoneId>,
    ) {
        fun dates(): Flow<LocalDate> =
            flow {
                while (true) {
                    val zone = zoneId.get()
                    val today = LocalDate.now(clock.withZone(zone))
                    emit(today)
                    delay(millisUntilNextDay(today, zone))
                }
            }

        /** Waking a moment past the boundary avoids re-emitting the same day twice. */
        private fun millisUntilNextDay(
            today: LocalDate,
            zone: ZoneId,
        ): Long =
            Duration
                .between(clock.instant(), today.plusDays(1).atStartOfDay(zone).toInstant())
                .toMillis()
                .coerceAtLeast(1) + BOUNDARY_MARGIN_MILLIS

        private companion object {
            const val BOUNDARY_MARGIN_MILLIS = 1_000L
        }
    }
