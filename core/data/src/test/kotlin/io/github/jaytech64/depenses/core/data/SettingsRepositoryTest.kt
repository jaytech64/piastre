// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.Instant

class SettingsRepositoryTest {
    /**
     * A real scope, not the test one: DataStore does its writes in the scope it is
     * given, and a [kotlinx.coroutines.test.TestScope] belonging to no running test
     * never runs them — every read then waits forever for a write that was queued
     * on a clock nobody is advancing.
     */
    private val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private lateinit var storeFile: File
    private lateinit var repository: SettingsRepository

    @Before
    fun setUp() {
        storeFile = File.createTempFile("settings", ".preferences_pb").apply { delete() }
        repository = SettingsRepository(PreferenceDataStoreFactory.create(scope = storeScope) { storeFile })
    }

    @After
    fun tearDown() {
        storeScope.cancel()
        storeFile.delete()
    }

    @Test
    fun `capture is on and onboarding is unseen on a fresh install`() =
        runTest {
            assertTrue(repository.captureEnabled.first())
            assertFalse(repository.onboardingCompleted.first())
            assertNull(repository.lastListenerEventAt.first())
        }

    @Test
    fun `the first heartbeat is always recorded`() =
        runTest {
            repository.noteListenerEvent(NOW)

            assertEquals(NOW, repository.lastListenerEventAt.first())
        }

    @Test
    fun `a heartbeat within the resolution window is dropped rather than written`() =
        runTest {
            repository.noteListenerEvent(NOW)
            repository.noteListenerEvent(NOW.plusSeconds(60))

            assertEquals(NOW, repository.lastListenerEventAt.first())
        }

    @Test
    fun `a heartbeat past the resolution window moves the timestamp`() =
        runTest {
            val later = NOW.plusSeconds(6 * 60)

            repository.noteListenerEvent(NOW)
            repository.noteListenerEvent(later)

            assertEquals(later, repository.lastListenerEventAt.first())
        }

    @Test
    fun `onboarding stays completed once it has been`() =
        runTest {
            repository.setOnboardingCompleted()

            assertTrue(repository.onboardingCompleted.first())
        }

    @Test
    fun `capture can be turned off and back on`() =
        runTest {
            repository.setCaptureEnabled(false)
            assertFalse(repository.captureEnabled.first())

            repository.setCaptureEnabled(true)
            assertTrue(repository.captureEnabled.first())
        }

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-09T14:00:00Z")
    }
}
