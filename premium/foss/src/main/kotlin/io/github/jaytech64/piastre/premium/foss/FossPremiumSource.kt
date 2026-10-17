// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.premium.foss

import android.app.Activity
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jaytech64.piastre.core.data.PremiumOffer
import io.github.jaytech64.piastre.core.data.PremiumOffers
import io.github.jaytech64.piastre.core.data.PremiumSource
import io.github.jaytech64.piastre.core.model.Premium
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject

/**
 * The F-Droid build sells nothing: every feature is on, and the only ask is a
 * link to a donation page that unlocks nothing (ADR-0022).
 */
class FossPremiumSource
    @Inject
    constructor() : PremiumSource {
        override val donationPage: String = DONATION_PAGE

        override val managePage: String? = null

        override val storeName: String = "F-Droid"

        override val orderNumber: Flow<String?> = flowOf(null)

        override val owned: Flow<Premium?> = flowOf(Premium.Unlocked)

        override val offers: Flow<PremiumOffers> = flowOf(PremiumOffers.None)

        override fun refresh() = Unit

        override fun buy(
            activity: Activity,
            offer: PremiumOffer,
        ) = error("The F-Droid build sells nothing, so it never offers anything to buy")

        private companion object {
            const val DONATION_PAGE = "https://liberapay.com/jaytech64"
        }
    }

@Module
@InstallIn(SingletonComponent::class)
internal interface FossPremiumModule {
    @Binds
    fun premiumSource(source: FossPremiumSource): PremiumSource
}
