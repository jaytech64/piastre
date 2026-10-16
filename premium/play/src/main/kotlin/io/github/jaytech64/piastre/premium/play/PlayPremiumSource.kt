// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.premium.play

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClient.ProductType
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.jaytech64.piastre.core.data.PremiumOffer
import io.github.jaytech64.piastre.core.data.PremiumOffers
import io.github.jaytech64.piastre.core.data.PremiumSource
import io.github.jaytech64.piastre.core.model.Premium
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Premium as Google Play sells it, through the Play Store app on the device.
 *
 * Play is asked whenever someone first reads [owned] or [offers] and again on
 * every [refresh]; it answers from its own cache when offline, so ownership is
 * known without a connection, while a subscription's price is not (the spike,
 * `spike-billing/README.md`).
 *
 * A new purchase is acknowledged at once: Play refunds one left unacknowledged
 * for three days.
 */
@Singleton
class PlayPremiumSource
    @Inject
    internal constructor(
        @ApplicationContext context: Context,
        private val trials: TrialRecords,
        private val reminder: TrialReminder,
        private val clock: Clock,
    ) : PremiumSource,
        PurchasesUpdatedListener {
        override val donationPage: String? = null

        override val managePage: String = PlayProducts.manageUrl(context.packageName)

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val asking = Mutex()

        private val client =
            BillingClient
                .newBuilder(context)
                .setListener(this)
                .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
                .build()

        private val ownedState = MutableStateFlow<Premium?>(null)
        private val offersState = MutableStateFlow<PremiumOffers>(PremiumOffers.Loading)
        private val products = MutableStateFlow<Map<String, ProductDetails>>(emptyMap())

        /** A trial whose purchase screen is open, until Play says how it ended. */
        @Volatile private var trialInFlight: PremiumOffer? = null

        override val owned: Flow<Premium?> = ownedState.onStart { refresh() }

        override val offers: Flow<PremiumOffers> = offersState.onStart { refresh() }

        override fun refresh() {
            scope.launch { askPlay() }
        }

        /** [refresh], for a caller that must wait for the answer: a receiver about to give its process back. */
        internal suspend fun refreshNow() = askPlay()

        override fun buy(
            activity: Activity,
            offer: PremiumOffer,
        ) {
            val productId =
                if (offer.kind ==
                    PremiumOffer.Kind.LIFETIME
                ) {
                    PlayProducts.LIFETIME
                } else {
                    PlayProducts.SUBSCRIPTION
                }
            val product = products.value[productId] ?: return refresh()
            trialInFlight = offer.takeIf { it.kind == PremiumOffer.Kind.TRIAL && it.freeDays != null }
            val params =
                BillingFlowParams.ProductDetailsParams
                    .newBuilder()
                    .setProductDetails(product)
                    .setOfferToken(offer.token)
                    .build()
            val flow = BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(params)).build()
            val result = client.launchBillingFlow(activity, flow)
            if (result.responseCode != BillingResponseCode.OK) {
                trialInFlight = null
                Log.w(TAG, "Play refused to open its purchase screen: ${result.describe()}")
            }
        }

        override fun onPurchasesUpdated(
            result: BillingResult,
            purchases: MutableList<Purchase>?,
        ) {
            val trialOffer = trialInFlight
            trialInFlight = null
            if (result.responseCode != BillingResponseCode.OK) {
                if (result.responseCode != BillingResponseCode.USER_CANCELED) {
                    Log.w(TAG, "A purchase did not go through: ${result.describe()}")
                }
                return
            }
            scope.launch {
                val trial = purchases?.firstOrNull { PlayProducts.SUBSCRIPTION in it.products && it.isPurchased }
                val freeDays = trialOffer?.freeDays
                if (trial != null && freeDays != null) {
                    val endsAt = Instant.ofEpochMilli(trial.purchaseTime).plus(Duration.ofDays(freeDays.toLong()))
                    trials.record(RecordedTrial(trial.purchaseToken, endsAt, trialOffer.price))
                    reminder.schedule(endsAt)
                }
                askPlay()
            }
        }

        /** One conversation with Play at a time, so two refreshes never interleave their answers. */
        private suspend fun askPlay() =
            asking.withLock {
                if (!client.connected()) {
                    ownedState.value = null
                    offersState.value = PremiumOffers.Unavailable
                    return@withLock
                }
                val owned = readOwned()
                ownedState.value = owned
                owned?.let { reminder.remindIfDue(it) }
                offersState.value = readOffers()
            }

        /** Null when Play cannot answer, which is not the same as owning nothing. */
        private suspend fun readOwned(): Premium? {
            val subscriptions = client.purchases(ProductType.SUBS) ?: return null
            val oneTime = client.purchases(ProductType.INAPP) ?: return null
            (subscriptions + oneTime).filter { it.isPurchased && !it.isAcknowledged }.forEach { client.acknowledge(it) }
            val subscription =
                subscriptions
                    .firstOrNull { PlayProducts.SUBSCRIPTION in it.products && it.isPurchased }
                    ?.let { OwnedSubscription(it.purchaseToken, it.isAutoRenewing) }
            val lifetime = oneTime.any { PlayProducts.LIFETIME in it.products && it.isPurchased }
            return ownedPremium(subscription, lifetime, trials.current(), clock.instant())
        }

        private suspend fun readOffers(): PremiumOffers {
            val subscription = client.details(PlayProducts.SUBSCRIPTION, ProductType.SUBS)
            val lifetime = client.details(PlayProducts.LIFETIME, ProductType.INAPP)
            products.value = listOfNotNull(subscription, lifetime).associateBy { it.productId }
            if (subscription == null && lifetime == null) return PremiumOffers.Unavailable
            val plans =
                subscription?.subscriptionOfferDetails.orEmpty().filter {
                    it.basePlanId ==
                        PlayProducts.YEARLY_PLAN
                }
            return PremiumOffers.Available(
                trial = plans.firstNotNullOfOrNull { it.asTrial() },
                yearly = plans.firstOrNull { it.offerId == null }?.asYearly(),
                lifetime = lifetime?.oneTimePurchaseOfferDetailsList?.firstOrNull()?.asLifetime(),
            )
        }

        private val Purchase.isPurchased get() = purchaseState == Purchase.PurchaseState.PURCHASED

        private fun BillingResult.describe() =
            "code $responseCode${debugMessage.takeIf { it.isNotEmpty() }?.let { ", $it" } ?: ""}"

        private companion object {
            const val TAG = "PlayPremium"
        }
    }

@Module
@InstallIn(SingletonComponent::class)
internal interface PlayPremiumModule {
    @Binds
    fun premiumSource(source: PlayPremiumSource): PremiumSource
}
