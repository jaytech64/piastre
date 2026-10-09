// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.capture.listener

import android.content.Context
import android.provider.Settings
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import io.github.jaytech64.piastre.core.data.SettingsRepository
import io.github.jaytech64.piastre.core.model.CaptureHealth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * Exercised against the real `enabled_notification_listeners` setting rather than
 * a fake, because the thing worth proving is that revoking access outside the app
 * is noticed — and a fake of the permission would notice by construction.
 */
@RunWith(RobolectricTestRunner::class)
class CaptureHealthRepositoryTest {
    /** Real, not a test scope: DataStore writes never run on a clock nobody advances. */
    private val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private lateinit var context: Context
    private lateinit var storeFile: File
    private lateinit var settings: SettingsRepository
    private lateinit var repository: CaptureHealthRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        storeFile = File.createTempFile("settings", ".preferences_pb").apply { delete() }
        settings = SettingsRepository(PreferenceDataStoreFactory.create(scope = storeScope) { storeFile })
        repository =
            CaptureHealthRepository(
                access = NotificationAccess(context),
                settings = settings,
                clock = Clock.fixed(NOW, ZoneOffset.UTC),
            )
        grantAccess(true)
    }

    @After
    fun tearDown() {
        storeScope.cancel()
        storeFile.delete()
    }

    @Test
    fun `a listener heard from a minute ago is healthy`() =
        runTest {
            settings.noteListenerEvent(NOW.minusSeconds(60))

            assertEquals(CaptureHealth.Listening(NOW.minusSeconds(60)), repository.health.first())
        }

    @Test
    fun `access revoked outside the app is noticed on the next refresh`() =
        runTest {
            settings.noteListenerEvent(NOW.minusSeconds(60))
            assertEquals(CaptureHealth.Listening(NOW.minusSeconds(60)), repository.health.first())

            grantAccess(false)
            repository.refresh()

            assertEquals(CaptureHealth.AccessRevoked(NOW.minusSeconds(60)), repository.health.first())
        }

    @Test
    fun `three days of silence with access granted is an alarm`() =
        runTest {
            val silentSince = NOW.minus(Duration.ofDays(3))
            settings.noteListenerEvent(silentSince)

            assertEquals(CaptureHealth.Silent(silentSince), repository.health.first())
        }

    @Test
    fun `capture turned off here is not reported as a fault`() =
        runTest {
            settings.noteListenerEvent(NOW.minusSeconds(60))
            settings.setCaptureEnabled(false)

            assertEquals(CaptureHealth.Paused(NOW.minusSeconds(60)), repository.health.first())
        }

    private fun grantAccess(granted: Boolean) {
        Settings.Secure.putString(
            context.contentResolver,
            "enabled_notification_listeners",
            if (granted) "${context.packageName}/${DepensesListenerService::class.java.name}" else "",
        )
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-09T14:00:00Z")
    }
}
