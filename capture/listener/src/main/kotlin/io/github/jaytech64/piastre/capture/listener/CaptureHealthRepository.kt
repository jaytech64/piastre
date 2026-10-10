// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.capture.listener

import android.content.Intent
import io.github.jaytech64.piastre.core.data.SettingsRepository
import io.github.jaytech64.piastre.core.model.CaptureHealth
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Answers the one question the ledger cannot: is an empty day empty because you
 * did not spend, or because the app stopped listening?
 *
 * The rule itself is [CaptureHealth.of]; this only gathers the three things it
 * needs from the three places that hold them.
 */
@Singleton
class CaptureHealthRepository
    @Inject
    constructor(
        private val access: NotificationAccess,
        private val settings: SettingsRepository,
        private val clock: Clock,
    ) {
        val health: Flow<CaptureHealth> =
            combine(
                access.granted,
                settings.captureEnabled,
                settings.lastListenerEventAt,
            ) { granted, enabled, lastEvent ->
                CaptureHealth.of(granted, enabled, lastEvent, clock.instant())
            }

        /**
         * Re-reads the permission. Worth calling whenever the app comes forward:
         * the user may have just come back from the system screen that grants it.
         */
        fun refresh() = access.refresh()

        /** The system screen that grants notification access — the only place it can be granted. */
        fun settingsIntent(): Intent = access.settingsIntent()
    }
