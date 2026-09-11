// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.model

/**
 * An exact monetary amount in minor units.
 *
 * Never a [Double]: 0.1 + 0.2 is not 0.3, and an expense ledger that drifts by a
 * cent per operation is worse than no ledger. [minor] is negative for a refund.
 */
data class Money(
    val minor: Long,
    val currency: String,
) {
    init {
        require(currency.length == ISO_4217_LENGTH) { "currency must be an ISO-4217 code, was '$currency'" }
    }

    operator fun plus(other: Money): Money {
        require(currency == other.currency) { "cannot add $currency to ${other.currency}" }
        return copy(minor = minor + other.minor)
    }

    operator fun unaryMinus(): Money = copy(minor = -minor)

    companion object {
        const val CAD: String = "CAD"

        /** Minor units per major unit. Two decimal places for every currency this app sees. */
        const val MINOR_UNITS: Long = 100L

        private const val ISO_4217_LENGTH = 3
    }
}
