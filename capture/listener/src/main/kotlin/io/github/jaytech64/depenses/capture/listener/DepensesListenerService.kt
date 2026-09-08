// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.capture.listener

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dagger.hilt.android.AndroidEntryPoint
import io.github.jaytech64.depenses.core.data.RawEventRepository
import io.github.jaytech64.depenses.core.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Watches the notification shade and writes what it sees to the raw log.
 *
 * This service records and does not interpret. Everything about *what a
 * notification means* — is it a payment, whose, for how much — happens later
 * against a row that is already on disk.
 */
@AndroidEntryPoint
class DepensesListenerService : NotificationListenerService() {
    @Inject
    lateinit var rawEvents: RawEventRepository

    @Inject
    lateinit var settings: SettingsRepository

    /**
     * Survives an individual callback but not the service. A write already handed
     * to the database is not interrupted by cancellation; one still queued is
     * simply replayed the next time the listener connects.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Android delivers everything still on the shade when it binds, so this is
     * where a gap gets filled.
     *
     * The gap can be long: the capture spike measured backlogs of 22, 22 and 51
     * hours after a rebind. Skipping this replay is how a week of spending
     * silently goes missing.
     */
    override fun onListenerConnected() {
        super.onListenerConnected()
        record(activeNotifications?.asList().orEmpty())
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        record(listOfNotNull(sbn))
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Copies each notification on the calling thread, then leaves.
     *
     * `onNotificationPosted` runs on the main thread and Android stops trusting a
     * listener that is slow to return, so the only work done here is reading a
     * bundle. Serialising, checking the setting and writing all happen after the
     * callback has already given the thread back.
     */
    private fun record(notifications: List<StatusBarNotification>) {
        if (notifications.isEmpty()) return
        val locale = resources.configuration.locales[0].toLanguageTag()
        val captured = notifications.map { it.capture(locale) }
        scope.launch {
            if (!settings.captureEnabled.first()) return@launch
            captured.forEach { rawEvents.record(it.notification, it.extrasJson()) }
        }
    }
}
