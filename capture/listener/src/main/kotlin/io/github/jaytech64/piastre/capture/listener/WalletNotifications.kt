// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.capture.listener

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Google Wallet's own notification switch, the second thing capture depends on
 * and the one the app is blind to.
 *
 * With Wallet's notifications off there is nothing for the listener to hear, and
 * Android lets no other app read that setting: the one call that lists another
 * app's channels answers only a listener paired with a companion device. So this
 * can say whether Wallet is there and open the screen where its notifications
 * are set, and it can never say that they are on.
 *
 * The package is written out a second time in this module's manifest, under
 * `<queries>`, without which [isInstalled] is false on every phone.
 */
@Singleton
class WalletNotifications
    @Inject
    constructor(
        private val context: Context,
    ) {
        private val checks = MutableStateFlow(0L)

        /**
         * Whether Wallet is on this phone, re-read on every [refresh] and on every
         * new collector: it is installed from another app, so coming back to this
         * one is the only moment the answer can have changed.
         */
        val installed: Flow<Boolean> = checks.map { isInstalled() }

        fun refresh() = checks.update { it + 1 }

        fun isInstalled(): Boolean =
            try {
                context.packageManager.getPackageInfo(WALLET_PACKAGE, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }

        /** The system screen holding Wallet's notification switch and its categories. */
        fun settingsIntent(): Intent =
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, WALLET_PACKAGE)

        private companion object {
            const val WALLET_PACKAGE = "com.google.android.apps.walletnfcrel"
        }
    }
