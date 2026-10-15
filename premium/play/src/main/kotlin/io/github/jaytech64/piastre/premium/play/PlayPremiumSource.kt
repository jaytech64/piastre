// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.premium.play

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jaytech64.piastre.core.data.PremiumSource
import javax.inject.Inject

/** The Play build: no donation link, which Play's payments policy would refuse. */
class PlayPremiumSource
    @Inject
    constructor() : PremiumSource {
        override val donationPage: String? = null
    }

@Module
@InstallIn(SingletonComponent::class)
internal interface PlayPremiumModule {
    @Binds
    fun premiumSource(source: PlayPremiumSource): PremiumSource
}
