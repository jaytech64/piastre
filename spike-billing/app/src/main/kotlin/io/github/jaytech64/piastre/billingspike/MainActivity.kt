package io.github.jaytech64.piastre.billingspike

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.ProductType
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams

private const val TAG = "BillingSpike"
private const val SUBSCRIPTION = "premium"
private const val LIFETIME = "premium_lifetime"

class MainActivity : Activity() {
    private lateinit var log: TextView
    private lateinit var billing: BillingClient
    private val details = mutableMapOf<String, ProductDetails>()

    private val onPurchases =
        PurchasesUpdatedListener { result, purchases ->
            say("purchases updated: ${result.describe()}")
            purchases.orEmpty().forEach(::acknowledge)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        log = TextView(this).apply { setTextIsSelectable(true) }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        listOf(
            "Connect" to ::connect,
            "Query products" to ::queryProducts,
            "Buy subscription (trial)" to { buy(SUBSCRIPTION) },
            "Buy lifetime" to { buy(LIFETIME) },
            "Query purchases" to ::queryPurchases,
        ).forEach { (label, action) ->
            column.addView(Button(this).apply { text = label; setOnClickListener { action() } })
        }
        column.addView(log)
        setContentView(ScrollView(this).apply { fitsSystemWindows = true; addView(column) })

        say("requested permissions: ${requestedPermissions().joinToString().ifEmpty { "none" }}")
        billing =
            BillingClient
                .newBuilder(this)
                .setListener(onPurchases)
                .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
                .build()
    }

    private fun requestedPermissions(): List<String> =
        packageManager
            .getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            .orEmpty()
            .toList()

    private fun connect() {
        billing.startConnection(
            object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) = say("connect: ${result.describe()}")

                override fun onBillingServiceDisconnected() = say("connect: service disconnected")
            },
        )
    }

    private fun queryProducts() {
        query(SUBSCRIPTION, ProductType.SUBS)
        query(LIFETIME, ProductType.INAPP)
    }

    private fun query(id: String, type: String) {
        val product =
            QueryProductDetailsParams.Product
                .newBuilder()
                .setProductId(id)
                .setProductType(type)
                .build()
        val params = QueryProductDetailsParams.newBuilder().setProductList(listOf(product)).build()
        billing.queryProductDetailsAsync(params) { result, found ->
            say("products $type: ${result.describe()}")
            found.productDetailsList.forEach {
                details[it.productId] = it
                say("  found ${it.productId}: ${it.name}")
                it.subscriptionOfferDetails.orEmpty().forEach { offer ->
                    val phases = offer.pricingPhases.pricingPhaseList.joinToString { p -> "${p.formattedPrice}/${p.billingPeriod}" }
                    say("    offer ${offer.offerId ?: "base"}: $phases")
                }
            }
            found.unfetchedProductList.forEach { say("  unfetched ${it.productId}: status ${it.statusCode}") }
        }
    }

    private fun buy(id: String) {
        val product = details[id] ?: return say("buy $id: query products first")
        val params = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(product)
        product.subscriptionOfferDetails
            ?.maxByOrNull { it.pricingPhases.pricingPhaseList.size }
            ?.let { params.setOfferToken(it.offerToken) }
        val flow = BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(params.build())).build()
        say("buy $id: ${billing.launchBillingFlow(this, flow).describe()}")
    }

    private fun queryPurchases() {
        listOf(ProductType.SUBS, ProductType.INAPP).forEach { type ->
            val params = QueryPurchasesParams.newBuilder().setProductType(type).build()
            billing.queryPurchasesAsync(params) { result, purchases ->
                say("purchases $type: ${result.describe()}, ${purchases.size} owned")
                purchases.forEach {
                    say("  ${it.products} state=${it.purchaseState} renews=${it.isAutoRenewing} acked=${it.isAcknowledged} at=${it.purchaseTime}")
                    say("  json keys: ${org.json.JSONObject(it.originalJson).keys().asSequence().toList()}")
                }
            }
        }
    }

    private fun acknowledge(purchase: Purchase) {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED || purchase.isAcknowledged) return
        val params = AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()
        billing.acknowledgePurchase(params) { say("acknowledge ${purchase.products}: ${it.describe()}") }
    }

    private fun BillingResult.describe() = "code $responseCode ${debugMessage.ifEmpty { "(no message)" }}"

    private fun say(line: String) {
        Log.i(TAG, line)
        runOnUiThread { log.append("$line\n") }
    }
}
