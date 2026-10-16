// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.feature.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.jaytech64.piastre.core.designsystem.Formatters
import io.github.jaytech64.piastre.core.model.Premium
import java.time.ZoneId

// What the store this build was made for puts in Settings: Premium in the Play
// build, a donation link in the F-Droid one.

/** Where Premium stands, at the top of Settings in the Play build, opening its page. */
@Composable
internal fun PremiumRow(
    premium: Premium,
    onClick: () -> Unit,
) {
    val body =
        when (premium) {
            Premium.Free -> {
                stringResource(R.string.settings_premium_free)
            }

            Premium.Lifetime, Premium.Unlocked -> {
                stringResource(R.string.settings_premium_lifetime)
            }

            is Premium.Subscribed -> {
                val trialEnd =
                    premium.trialEndsAt?.let {
                        Formatters.date(
                            it.atZone(ZoneId.systemDefault()).toLocalDate(),
                        )
                    }
                when {
                    !premium.renews -> stringResource(R.string.settings_premium_cancelled)
                    trialEnd != null -> stringResource(R.string.settings_premium_trial, trialEnd)
                    else -> stringResource(R.string.settings_premium_on)
                }
            }
        }
    SettingRow(title = stringResource(R.string.settings_premium), body = body, onClick = onClick)
}

/**
 * The F-Droid build's one ask: a donation page, handed to the browser. A donation
 * unlocks nothing, and the app never learns whether one was made. Without a
 * browser to open it, the row gives the address to type elsewhere.
 */
@Composable
internal fun SupportRow(page: String) {
    val context = LocalContext.current
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(page))
    if (intent.resolveActivity(context.packageManager) != null) {
        SettingRow(
            title = stringResource(R.string.settings_support),
            body = stringResource(R.string.settings_support_body),
            onClick = { context.startActivity(intent) },
        )
    } else {
        SettingRow(
            title = stringResource(R.string.settings_support),
            body = stringResource(R.string.settings_support_no_browser, page),
        )
    }
}
