// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.premium.play

import io.github.jaytech64.piastre.core.model.Money
import io.github.jaytech64.piastre.core.model.Premium
import java.time.Instant
import java.time.Period
import java.time.format.DateTimeParseException

/** The products set up in Play Console. Renaming one strands everyone who bought it. */
internal object PlayProducts {
    const val SUBSCRIPTION = "premium"
    const val MONTHLY_PLAN = "monthly"
    const val LIFETIME = "premium_lifetime"

    /** Play's own page for the subscription, where it is managed or cancelled. */
    fun manageUrl(packageName: String) =
        "https://play.google.com/store/account/subscriptions?sku=$SUBSCRIPTION&package=$packageName"
}

/** What Play reports about the one subscription, reduced to what Premium needs. */
internal data class OwnedSubscription(
    val purchaseToken: String,
    val renews: Boolean,
)

/**
 * A trial this app started, written down when Play confirmed it, because Play
 * itself never says that a purchase began as a trial.
 */
internal data class RecordedTrial(
    val purchaseToken: String,
    val endsAt: Instant,
    /** What each month costs once the trial ends, which the reminder must state. */
    val monthlyPrice: Money,
)

/**
 * Lifetime outranks a subscription bought alongside it, and a trial's end is
 * known only for the very purchase it was recorded against, and only until it
 * has passed.
 */
internal fun ownedPremium(
    subscription: OwnedSubscription?,
    lifetimeOwned: Boolean,
    trial: RecordedTrial?,
    now: Instant,
): Premium =
    when {
        lifetimeOwned -> {
            Premium.Lifetime
        }

        subscription != null -> {
            Premium.Subscribed(
                renews = subscription.renews,
                trialEndsAt =
                    trial
                        ?.takeIf { it.purchaseToken == subscription.purchaseToken && it.endsAt.isAfter(now) }
                        ?.endsAt,
            )
        }

        else -> {
            Premium.Free
        }
    }

/**
 * A review code turns on what Play left off, whether Play said Free or could not
 * be asked at all, as on a reviewer's device or an emulator. Whatever Play says
 * was bought wins, since it says more.
 */
internal fun withReviewCode(
    play: Premium?,
    codeEntered: Boolean,
): Premium? = if (codeEntered && (play == null || play == Premium.Free)) Premium.ReviewCode else play

/**
 * Days in a free period as Play states it, or null for a period not counted in
 * days. Play writes 30 days as `P4W2D`, so the text is parsed, never compared.
 */
internal fun freeDays(billingPeriod: String): Int? {
    val period =
        try {
            Period.parse(billingPeriod)
        } catch (unreadable: DateTimeParseException) {
            return null
        }
    return period.days.takeIf { period.years == 0 && period.months == 0 }
}
