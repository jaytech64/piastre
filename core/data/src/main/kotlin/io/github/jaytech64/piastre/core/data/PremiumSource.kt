// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.core.data

import android.app.Activity
import io.github.jaytech64.piastre.core.model.Money
import io.github.jaytech64.piastre.core.model.Premium
import kotlinx.coroutines.flow.Flow

/**
 * What the store this build was made for lets the app ask of the person using it,
 * and what that store says they own.
 *
 * Only `:app` knows which store that is: its `play` and `foss` flavours each
 * bring one `:premium:*` module that binds this, so no feature can tell the two
 * builds apart, and Google's billing library reaches the Play build's classpath
 * and no other.
 */
interface PremiumSource {
    /**
     * The web page a donation is made on, or null where the build must not ask:
     * Play's payments policy bars a Play app from pointing to another way to pay.
     */
    val donationPage: String?

    /** The store's own page where a subscription is managed or cancelled, or null where nothing is sold. */
    val managePage: String?

    /** The store's name, as a suggestion e-mail states which build it came from. A proper name, never translated. */
    val storeName: String

    /**
     * The store's order number for the Premium owned, or null for none: a
     * suggestion e-mail quotes it to claim the priority Premium buyers get.
     */
    val orderNumber: Flow<String?>

    /**
     * What the store says is owned, or null while it cannot be asked. Null is not
     * Free: [PremiumRepository] keeps the last answer until the store gives another.
     */
    val owned: Flow<Premium?>

    /** What can be bought now. */
    val offers: Flow<PremiumOffers>

    /** Asks the store again, as when the app comes back on screen. */
    fun refresh()

    /** Hands [offer] to the store's own purchase screen, over [activity]. */
    fun buy(
        activity: Activity,
        offer: PremiumOffer,
    )
}

sealed interface PremiumOffers {
    /** A build that sells nothing. */
    data object None : PremiumOffers

    data object Loading : PremiumOffers

    /** The store could not be reached, which offline is the usual reason. */
    data object Unavailable : PremiumOffers

    data class Available(
        val trial: PremiumOffer?,
        val yearly: PremiumOffer?,
        val lifetime: PremiumOffer?,
    ) : PremiumOffers
}

/**
 * One way to buy Premium, priced by the store in the buyer's own currency.
 * [freeDays] is set only for the trial, from the period the store states.
 */
data class PremiumOffer(
    val kind: Kind,
    val price: Money,
    val freeDays: Int?,
    /** The store's own handle on this offer; nothing outside the store reads it. */
    val token: String,
) {
    enum class Kind { TRIAL, YEARLY, LIFETIME }
}
