// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.capture.listener

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Notification access is granted and revoked entirely outside the app, so it is
 * never cached: every reader asks the system.
 *
 * A capture app that believes a stale "granted" goes silently deaf, which is the
 * worst failure this app has — it looks like you simply did not spend anything.
 */
@Singleton
class NotificationAccess
    @Inject
    constructor(
        private val context: Context,
    ) {
        private val checks = MutableStateFlow(0L)

        /**
         * The permission, re-read on every [refresh] and on every new collector.
         *
         * There is no broadcast to listen to and the setting lives in a screen
         * belonging to the system, so coming back to the app is the only moment
         * this can have changed — and the only moment worth asking again.
         *
         * Deliberately not distinct-until-changed: an unchanged answer still has
         * to push the health rule through a fresh clock reading, which is how
         * silence crossing the 48-hour line ever gets noticed.
         */
        val granted: Flow<Boolean> = checks.map { isGranted() }

        fun refresh() = checks.update { it + 1 }

        fun isGranted(): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

        fun settingsIntent(): Intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    }
