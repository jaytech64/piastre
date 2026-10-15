// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.premium.foss

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jaytech64.piastre.core.data.PremiumSource
import javax.inject.Inject

/**
 * The F-Droid build sells nothing: every feature is on, and the only ask is a
 * link to a donation page that unlocks nothing (ADR-0022).
 */
class FossPremiumSource
    @Inject
    constructor() : PremiumSource {
        override val donationPage: String = DONATION_PAGE

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
