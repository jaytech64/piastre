// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.premium.play

import io.github.jaytech64.piastre.core.model.Money
import io.github.jaytech64.piastre.core.model.Premium
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration
import java.time.Instant

class PlayOwnershipTest {
    private val now = Instant.parse("2026-10-15T12:00:00Z")
    private val inThreeWeeks = now.plus(Duration.ofDays(21))
    private val yearly = Money(1_500, Money.CAD)
    private val subscription = OwnedSubscription(purchaseToken = "token-a", renews = true)

    @Test
    fun `owning nothing is free`() {
        assertEquals(Premium.Free, ownedPremium(null, lifetimeOwned = false, trial = null, now = now))
    }

    @Test
    fun `lifetime wins over a subscription still running beside it`() {
        assertEquals(Premium.Lifetime, ownedPremium(subscription, lifetimeOwned = true, trial = null, now = now))
    }

    @Test
    fun `a trial this app recorded gives the subscription its end`() {
        val trial = RecordedTrial("token-a", inThreeWeeks, yearly)
        assertEquals(
            Premium.Subscribed(renews = true, trialEndsAt = inThreeWeeks),
            ownedPremium(subscription, lifetimeOwned = false, trial = trial, now = now),
        )
    }

    @Test
    fun `a trial recorded against another purchase says nothing about this one`() {
        val trial = RecordedTrial("token-b", inThreeWeeks, yearly)
        assertEquals(
            Premium.Subscribed(renews = true, trialEndsAt = null),
            ownedPremium(subscription, lifetimeOwned = false, trial = trial, now = now),
        )
    }

    @Test
    fun `a trial that has ended is no longer shown`() {
        val trial = RecordedTrial("token-a", now.minusSeconds(1), yearly)
        assertEquals(
            Premium.Subscribed(renews = true, trialEndsAt = null),
            ownedPremium(subscription, lifetimeOwned = false, trial = trial, now = now),
        )
    }

    @Test
    fun `a cancelled subscription is still owned, and no longer renews`() {
        assertEquals(
            Premium.Subscribed(renews = false, trialEndsAt = null),
            ownedPremium(subscription.copy(renews = false), lifetimeOwned = false, trial = null, now = now),
        )
    }

    @Test
    fun `thirty free days read the same however play writes them`() {
        assertEquals(30, freeDays("P4W2D"))
        assertEquals(30, freeDays("P30D"))
    }

    @Test
    fun `a free period counted in months or unreadable has no day count`() {
        assertNull(freeDays("P1M"))
        assertNull(freeDays("thirty days"))
    }
}
