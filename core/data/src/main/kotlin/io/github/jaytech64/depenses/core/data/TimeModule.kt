// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.data

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import java.time.ZoneId

@Module
@InstallIn(SingletonComponent::class)
object TimeModule {
    /**
     * Deliberately unscoped, so the zone is read at the moment a purchase is
     * parsed rather than once at startup. Caching it would bucket everything you
     * buy on a trip into the zone you happened to launch the app in.
     */
    @Provides
    fun zoneId(): ZoneId = ZoneId.systemDefault()

    /** UTC because the only thing read off it is an instant to store. */
    @Provides
    fun clock(): Clock = Clock.systemUTC()
}
