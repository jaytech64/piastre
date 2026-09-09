// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.model

import java.time.Duration
import java.time.Instant

/**
 * Whether the app is still hearing the notification shade.
 *
 * Going deaf without saying so is this app's worst failure: notification access
 * revoked by a system update or a battery optimiser looks exactly like a quiet
 * week. No other screen can tell those apart — an empty ledger is what both
 * produce — so the answer is state in its own right.
 */
sealed interface CaptureHealth {
    /**
     * When anything last reached the listener — null only before it has ever
     * bound. Carried by every state, because "when did this app last hear
     * anything" is the useful fact in all four of them.
     */
    val lastEventAt: Instant?

    /** Access is granted, capture is on, and the listener is being fed. */
    data class Listening(
        override val lastEventAt: Instant?,
    ) : CaptureHealth

    /** Capture was turned off here, on purpose. Silence is the point, not a fault. */
    data class Paused(
        override val lastEventAt: Instant?,
    ) : CaptureHealth

    /** Notification access is not granted, so nothing can arrive at all. */
    data class AccessRevoked(
        override val lastEventAt: Instant?,
    ) : CaptureHealth

    /** Access is granted, yet nothing has reached the listener since then. */
    data class Silent(
        override val lastEventAt: Instant,
    ) : CaptureHealth

    /** Whether this is something the user has to go and fix. */
    val needsAttention: Boolean get() = this is AccessRevoked || this is Silent

    companion object {
        /**
         * How long the shade may stay quiet before the app stops trusting itself.
         *
         * Every notification on the device counts toward this, not only payments,
         * so a phone in daily use never reaches it honestly. Two days is long
         * enough to survive a weekend the phone spent face-down on a table.
         */
        val SILENT_AFTER: Duration = Duration.ofHours(48)

        /**
         * The whole rule, as a function of what can be observed.
         *
         * [lastEventAt] is null until the listener has bound for the first time,
         * and that case is deliberately *not* an alarm: a grant takes a second or
         * two to reach the service, and a banner that accuses the user of a broken
         * install while they watch it work is worse than one that waits.
         */
        fun of(
            accessGranted: Boolean,
            captureEnabled: Boolean,
            lastEventAt: Instant?,
            now: Instant,
        ): CaptureHealth =
            when {
                !captureEnabled -> Paused(lastEventAt)
                !accessGranted -> AccessRevoked(lastEventAt)
                lastEventAt != null && Duration.between(lastEventAt, now) >= SILENT_AFTER -> Silent(lastEventAt)
                else -> Listening(lastEventAt)
            }
    }
}
