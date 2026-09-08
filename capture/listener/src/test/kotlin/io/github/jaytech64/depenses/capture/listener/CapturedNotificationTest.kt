// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.capture.listener

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
import android.os.UserHandle
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Covers the one piece of the listener that is pure data movement, against a real
 * [Notification] rather than a hand-built fake — the fields Wallet uses are
 * exactly the ones a fake would get subtly wrong.
 */
@RunWith(RobolectricTestRunner::class)
class CapturedNotificationTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context
            .getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, CHANNEL, NotificationManager.IMPORTANCE_DEFAULT))
    }

    @Test
    fun `a wallet payment keeps the fields the parser reads`() {
        val captured = walletPayment().capture(LOCALE)

        with(captured.notification) {
            assertEquals(WALLET, sourcePackage)
            assertEquals(TITLE, title)
            assertEquals(TEXT, text)
            assertEquals(LOCALE, deviceLocale)
            assertEquals(POSTED_AT, postedAtUtcMillis)
            assertFalse(isGroupSummary)
        }
    }

    /** Identity comes from the tag, so a key the parser cannot split is a lost transaction. */
    @Test
    fun `the notification key carries the tag wallet uses as a transaction id`() {
        val key = walletPayment().capture(LOCALE).notification.notificationKey

        assertTrue("key was '$key'", key.split('|').contains(TAG))
    }

    @Test
    fun `extras that are not text are left out`() {
        val extras =
            Bundle().apply {
                putInt(Notification.EXTRA_PROGRESS, 42)
                putString("com.example.unrelated", "kept out because it is not an android key")
            }

        val captured = walletPayment(extras).capture(LOCALE).extras

        assertEquals(TITLE, captured[Notification.EXTRA_TITLE])
        assertNull(captured[Notification.EXTRA_PROGRESS])
        assertNull(captured["com.example.unrelated"])
    }

    @Test
    fun `two identical notifications serialise to the same json`() {
        assertEquals(
            walletPayment().capture(LOCALE).extrasJson(),
            walletPayment().capture(LOCALE).extrasJson(),
        )
    }

    @Test
    fun `accents and apostrophes survive serialisation`() {
        val json = walletPayment().capture(LOCALE).extrasJson()

        assertTrue(json, json.contains("Carte d'accès Coopérative"))
    }

    @Test
    fun `a group summary is recorded as one`() {
        val summary =
            Notification
                .Builder(context, CHANNEL)
                .setGroup("wallet")
                .setGroupSummary(true)
                .build()

        assertTrue(statusBarNotification(summary).capture(LOCALE).notification.isGroupSummary)
    }

    private fun walletPayment(extras: Bundle = Bundle.EMPTY): StatusBarNotification =
        statusBarNotification(
            Notification
                .Builder(context, CHANNEL)
                .setContentTitle(TITLE)
                .setContentText(TEXT)
                .addExtras(extras)
                .build(),
        )

    /**
     * Positional because the only public constructor is: package, opPackage, id,
     * tag, uid, initialPid, score, notification, user, postTime. The tag is the
     * argument that matters — it is what Wallet uses as a transaction id.
     */
    @Suppress("DEPRECATION")
    private fun statusBarNotification(notification: Notification): StatusBarNotification =
        StatusBarNotification(
            WALLET,
            WALLET,
            NOTIFICATION_ID,
            TAG,
            UID,
            UNUSED_PID,
            UNUSED_SCORE,
            notification,
            UserHandle.getUserHandleForUid(UID),
            POSTED_AT,
        )

    private companion object {
        const val CHANNEL = "payments"
        const val WALLET = "com.google.android.apps.walletnfcrel"
        const val TITLE = "MARCHE ST JOSEPH  1234"
        const val TEXT = "23,42 $ avec Carte d'accès Coopérative ••0000"
        const val LOCALE = "fr-CA"
        const val TAG = "txn-a1b2c3"
        const val NOTIFICATION_ID = 1001
        const val UID = 10123
        const val POSTED_AT = 1_788_740_166_564L
        const val UNUSED_PID = 0
        const val UNUSED_SCORE = 0
    }
}
