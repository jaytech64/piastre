// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.core.model

import java.time.Instant

/**
 * Whether Premium is on, and why. A store decides it; nothing in the ledger does,
 * and no figure anywhere reads it.
 */
sealed interface Premium {
    val isOn: Boolean

    data object Free : Premium {
        override val isOn = false
    }

    /**
     * A Play subscription. Play never says when one renews or ends, nor whether it
     * began as a trial, so [trialEndsAt] is known only for a trial this app
     * started and recorded itself, and [renews] turns false once it is cancelled.
     */
    data class Subscribed(
        val renews: Boolean,
        val trialEndsAt: Instant?,
    ) : Premium {
        override val isOn = true
    }

    data object Lifetime : Premium {
        override val isOn = true
    }

    /**
     * A Play build where a review code was entered, so Google's reviewers, who
     * cannot buy, reach every paid feature. Anything Play says was bought wins.
     */
    data object ReviewCode : Premium {
        override val isOn = true
    }

    /** The F-Droid build, which sells nothing and has everything. */
    data object Unlocked : Premium {
        override val isOn = true
    }
}
