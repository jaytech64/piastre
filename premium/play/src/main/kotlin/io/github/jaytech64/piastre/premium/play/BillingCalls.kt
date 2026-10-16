// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.premium.play

import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

// Play Billing's callbacks as suspending calls. Each logs a refusal with its
// code and returns what a caller can act on: false, null, or nothing.

internal suspend fun BillingClient.connected(): Boolean {
    if (isReady) return true
    return suspendCancellableCoroutine { continuation ->
        startConnection(
            object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (result.responseCode != BillingResponseCode.OK) {
                        Log.w(TAG, "Play Billing would not connect: ${result.describe()}")
                    }
                    if (continuation.isActive) {
                        continuation.resume(
                            result.responseCode == BillingResponseCode.OK,
                        )
                    }
                }

                override fun onBillingServiceDisconnected() {
                    if (continuation.isActive) continuation.resume(false)
                }
            },
        )
    }
}

internal suspend fun BillingClient.purchases(type: String): List<Purchase>? =
    suspendCancellableCoroutine { continuation ->
        queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(type).build(),
        ) { result, found ->
            if (result.responseCode != BillingResponseCode.OK) {
                Log.w(TAG, "Play could not list $type purchases: ${result.describe()}")
            }
            continuation.resume(found.takeIf { result.responseCode == BillingResponseCode.OK })
        }
    }

internal suspend fun BillingClient.acknowledge(purchase: Purchase) {
    val params = AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()
    val result =
        suspendCancellableCoroutine { continuation ->
            acknowledgePurchase(params) { continuation.resume(it) }
        }
    if (result.responseCode != BillingResponseCode.OK) {
        Log.w(TAG, "Play did not take the acknowledgement of ${purchase.products}: ${result.describe()}")
    }
}

internal suspend fun BillingClient.details(
    productId: String,
    type: String,
): ProductDetails? {
    val product =
        QueryProductDetailsParams.Product
            .newBuilder()
            .setProductId(productId)
            .setProductType(type)
            .build()
    val params = QueryProductDetailsParams.newBuilder().setProductList(listOf(product)).build()
    return suspendCancellableCoroutine { continuation ->
        queryProductDetailsAsync(params) { result, found ->
            if (result.responseCode != BillingResponseCode.OK) {
                Log.w(TAG, "Play could not describe $productId: ${result.describe()}")
            }
            continuation.resume(found.productDetailsList.firstOrNull { it.productId == productId })
        }
    }
}

internal fun BillingResult.describe() =
    "code $responseCode${debugMessage.takeIf { it.isNotEmpty() }?.let { ", $it" } ?: ""}"

private const val TAG = "PlayBilling"
