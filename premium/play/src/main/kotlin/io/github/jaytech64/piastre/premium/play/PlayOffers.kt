// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.premium.play

import com.android.billingclient.api.ProductDetails
import io.github.jaytech64.piastre.core.data.PremiumOffer
import io.github.jaytech64.piastre.core.model.Money

/** The trial offer: a free phase, then the price it renews at. Null for an offer with no free phase. */
internal fun ProductDetails.SubscriptionOfferDetails.asTrial(): PremiumOffer? {
    val phases = pricingPhases.pricingPhaseList
    val free = phases.firstOrNull { it.priceAmountMicros == 0L } ?: return null
    val paid = phases.lastOrNull { it.priceAmountMicros > 0L } ?: return null
    return PremiumOffer(PremiumOffer.Kind.TRIAL, paid.money(), freeDays(free.billingPeriod), offerToken)
}

internal fun ProductDetails.SubscriptionOfferDetails.asYearly(): PremiumOffer? {
    val paid = pricingPhases.pricingPhaseList.lastOrNull { it.priceAmountMicros > 0L } ?: return null
    return PremiumOffer(PremiumOffer.Kind.YEARLY, paid.money(), freeDays = null, offerToken)
}

internal fun ProductDetails.OneTimePurchaseOfferDetails.asLifetime(): PremiumOffer? =
    offerToken?.let { PremiumOffer(PremiumOffer.Kind.LIFETIME, money(priceAmountMicros, priceCurrencyCode), null, it) }

private fun ProductDetails.PricingPhase.money() = money(priceAmountMicros, priceCurrencyCode)

private fun money(
    micros: Long,
    currency: String,
) = Money(micros / MICROS_PER_MINOR_UNIT, currency)

/** Play prices in millionths of a unit; [Money] counts hundredths. */
private const val MICROS_PER_MINOR_UNIT = 10_000L
