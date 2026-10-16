// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.feature.premium

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jaytech64.piastre.core.data.PremiumOffer
import io.github.jaytech64.piastre.core.data.PremiumOffers
import io.github.jaytech64.piastre.core.designsystem.Formatters
import io.github.jaytech64.piastre.core.model.Premium
import java.time.ZoneId

/**
 * What Premium is, whether it is on, and the store's ways to buy, manage or
 * cancel it. Play gives no renewal or end date, so none is shown but a trial's,
 * which this app recorded itself when it started one.
 */
@Composable
fun PremiumScreen(viewModel: PremiumViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val buy: (PremiumOffer) -> Unit = { offer -> context.activity()?.let { viewModel.buy(it, offer) } }
    val manage: () -> Unit = {
        viewModel.managePage?.let { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.premium_title), style = MaterialTheme.typography.headlineSmall)

        when (val premium = state.premium) {
            null -> CircularProgressIndicator()
            Premium.Free -> Free(state.offers, buy, viewModel::retry)
            is Premium.Subscribed -> Subscribed(premium, state.offers, buy, manage)
            Premium.Lifetime -> Body(stringResource(R.string.premium_lifetime))
            Premium.Unlocked -> Body(stringResource(R.string.premium_unlocked))
        }
    }
}

@Composable
private fun Free(
    offers: PremiumOffers,
    buy: (PremiumOffer) -> Unit,
    retry: () -> Unit,
) {
    Body(stringResource(R.string.premium_free_lead))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Bullet(stringResource(R.string.premium_benefit_budgets))
        Bullet(stringResource(R.string.premium_benefit_future))
    }
    when (offers) {
        PremiumOffers.None -> {
            Unit
        }

        PremiumOffers.Loading -> {
            Quiet(stringResource(R.string.premium_offers_loading))
        }

        PremiumOffers.Unavailable -> {
            Body(stringResource(R.string.premium_offers_unavailable))
            TextButton(onClick = retry) { Text(stringResource(R.string.premium_retry)) }
        }

        is PremiumOffers.Available -> {
            val subscription = offers.trial ?: offers.yearly
            subscription?.let { offer ->
                Button(onClick = { buy(offer) }, Modifier.fillMaxWidth()) { Text(offer.label()) }
            }
            offers.lifetime?.let { offer ->
                OutlinedButton(onClick = { buy(offer) }, Modifier.fillMaxWidth()) { Text(offer.label()) }
            }
            Quiet(stringResource(R.string.premium_terms))
        }
    }
}

@Composable
private fun Subscribed(
    premium: Premium.Subscribed,
    offers: PremiumOffers,
    buy: (PremiumOffer) -> Unit,
    manage: () -> Unit,
) {
    val trialEnd = premium.trialEndsAt?.let { Formatters.date(it.atZone(ZoneId.systemDefault()).toLocalDate()) }
    Body(
        when {
            !premium.renews -> stringResource(R.string.premium_cancelled)
            trialEnd != null -> stringResource(R.string.premium_trial, trialEnd)
            else -> stringResource(R.string.premium_subscribed)
        },
    )
    LinkRow(
        title = stringResource(if (premium.renews) R.string.premium_manage else R.string.premium_resubscribe),
        body = stringResource(R.string.premium_in_google_play),
        onClick = manage,
    )
    if (trialEnd != null && premium.renews) TrialReminderCard()
    val lifetime = (offers as? PremiumOffers.Available)?.lifetime
    if (lifetime != null) {
        Body(stringResource(R.string.premium_lifetime_instead))
        Quiet(stringResource(R.string.premium_lifetime_instead_body))
        OutlinedButton(onClick = { buy(lifetime) }, Modifier.fillMaxWidth()) { Text(lifetime.label()) }
    }
}

@Composable
private fun PremiumOffer.label(): String {
    val price = Formatters.money(price)
    return when (kind) {
        PremiumOffer.Kind.TRIAL -> {
            freeDays?.let { stringResource(R.string.premium_buy_trial, it, price) }
                ?: stringResource(R.string.premium_buy_yearly, price)
        }

        PremiumOffer.Kind.YEARLY -> {
            stringResource(R.string.premium_buy_yearly, price)
        }

        PremiumOffer.Kind.LIFETIME -> {
            stringResource(R.string.premium_buy_lifetime, price)
        }
    }
}
