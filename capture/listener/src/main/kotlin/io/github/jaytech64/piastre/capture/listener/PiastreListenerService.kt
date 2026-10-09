// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.capture.listener

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dagger.hilt.android.AndroidEntryPoint
import io.github.jaytech64.piastre.core.data.CaptureCatchUp
import io.github.jaytech64.piastre.core.data.NotificationCapture
import io.github.jaytech64.piastre.core.data.RecurringPosting
import io.github.jaytech64.piastre.core.data.SettingsRepository
import io.github.jaytech64.piastre.parsing.ParserRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Clock
import javax.inject.Inject

/**
 * Watches the notification shade and writes what it sees to the raw log.
 *
 * This service records and does not interpret. Everything about *what a
 * notification means* — is it a payment, whose, for how much — happens later
 * against a row that is already on disk.
 */
@AndroidEntryPoint
class PiastreListenerService : NotificationListenerService() {
    @Inject
    lateinit var capture: NotificationCapture

    @Inject
    lateinit var settings: SettingsRepository

    @Inject
    lateinit var catchUp: CaptureCatchUp

    @Inject
    lateinit var recurring: RecurringPosting

    @Inject
    lateinit var registry: ParserRegistry

    @Inject
    lateinit var clock: Clock

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
     *
     * A rebind is also the moment a row can be left recorded but unparsed, so the
     * catch-up pass is scheduled from the same place — and, since it can come
     * without the process restarting, so is writing any recurring transaction
     * that has fallen due.
     */
    override fun onListenerConnected() {
        super.onListenerConnected()
        noteAlive()
        catchUp.schedule()
        recurring.schedule()
        record(activeNotifications?.asList().orEmpty())
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        noteAlive()
        record(listOfNotNull(sbn))
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Marks the pipe open, whatever came through it.
     *
     * This runs before the package filter and stores only a timestamp — no
     * package, no count, nothing read from the bundle. Restricting it to Wallet
     * would make a fortnight of cooking at home indistinguishable from a listener
     * the system quietly unbound, which is the whole thing being guarded against.
     */
    private fun noteAlive() {
        val now = clock.instant()
        scope.launch { settings.noteListenerEvent(now) }
    }

    /**
     * Copies each notification on the calling thread, then leaves.
     *
     * `onNotificationPosted` runs on the main thread and Android stops trusting a
     * listener that is slow to return, so the only work done here is reading a
     * bundle. Serialising, checking the setting and writing all happen after the
     * callback has already given the thread back.
     *
     * Notifications from packages no parser claims are dropped before the bundle
     * is opened. Android hands this service every notification on the device —
     * mail subjects, message previews, everything — and the only way not to hold
     * that data is never to read it.
     */
    private fun record(notifications: List<StatusBarNotification>) {
        val watched = notifications.filter { it.packageName in registry.watchedPackages }
        if (watched.isEmpty()) return
        val locale = resources.configuration.locales[0].toLanguageTag()
        val payloads = watched.map { it.toCapture(locale) }
        scope.launch {
            if (!settings.captureEnabled.first()) return@launch
            payloads.forEach { capture.capture(it.notification, it.extrasJson()) }
        }
    }
}
