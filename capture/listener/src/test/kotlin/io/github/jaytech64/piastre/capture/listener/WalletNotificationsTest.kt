// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.capture.listener

import android.content.Context
import android.content.pm.PackageInfo
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class WalletNotificationsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val wallet = WalletNotifications(context)

    @Test
    fun `a phone without Google Wallet is said to have none`() {
        assertFalse(wallet.isInstalled())
    }

    @Test
    fun `Google Wallet is found once it is installed`() {
        shadowOf(context.packageManager).installPackage(PackageInfo().apply { packageName = WALLET })

        assertTrue(wallet.isInstalled())
    }

    @Test
    fun `Google Wallet installed while the app was away is noticed on the next refresh`() =
        runTest {
            assertFalse(wallet.installed.first())

            shadowOf(context.packageManager).installPackage(PackageInfo().apply { packageName = WALLET })
            wallet.refresh()

            assertTrue(wallet.installed.first())
        }

    @Test
    fun `the screen it opens is Google Wallet's notification settings`() {
        val intent = wallet.settingsIntent()

        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, intent.action)
        assertEquals(WALLET, intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
    }

    private companion object {
        const val WALLET = "com.google.android.apps.walletnfcrel"
    }
}
