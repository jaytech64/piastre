// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.feature.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.viewModelScope
import io.github.jaytech64.piastre.core.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.time.Duration.Companion.seconds

class OnboardingViewModelTest {
    /** Real, not a test scope: DataStore writes never run on a clock nobody advances. */
    private val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private lateinit var storeFile: File
    private lateinit var settings: SettingsRepository
    private lateinit var viewModel: OnboardingViewModel

    /**
     * Main is unconfined rather than queueing, and that is the whole of what this
     * test needs from it.
     *
     * `complete()` launches its write on `viewModelScope`, so a queueing dispatcher
     * only hands that write to DataStore when something drains the scheduler —
     * while the assertion below has already left for a real-time wait on another
     * dispatcher. The write's dispatch is then on the virtual clock and the wait on
     * the real one, and nothing relates the two: on a loaded runner the wait can
     * expire having never let the write start. That is what CI saw twice, once for
     * a full minute, and it reproduces exactly when Main is given a scheduler
     * `runTest` does not drive.
     *
     * Unconfined issues the write inline, so by the time the wait begins the only
     * thing left outstanding is the disk — which is what the wait is for.
     */
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        storeFile = File.createTempFile("settings", ".preferences_pb").apply { delete() }
        settings = SettingsRepository(PreferenceDataStoreFactory.create(scope = storeScope) { storeFile })
        viewModel = OnboardingViewModel(settings)
    }

    /** The view model's scope goes first, so no collector of this test is still running when the next one starts. */
    @After
    fun tearDown() {
        viewModel.viewModelScope.cancel()
        storeScope.cancel()
        storeFile.delete()
        Dispatchers.resetMain()
    }

    @Test
    fun `nothing is claimed before the answer has been read off disk`() {
        assertNull(viewModel.completed.value)
    }

    @Test
    fun `a fresh install has not been onboarded`() =
        runTest {
            assertEquals(false, awaitOnRealTime(viewModel.completed) { it != null })
        }

    /** The disk is checked before the view model, so a recurrence says which of the two lost the value. */
    @Test
    fun `completing it is remembered`() =
        runTest {
            viewModel.complete()

            assertEquals("the write never reached disk", true, awaitOnRealTime(settings.onboardingCompleted) { it })
            assertEquals(
                "the write reached disk, but the view model never saw it",
                true,
                awaitOnRealTime(viewModel.completed) { it == true },
            )
        }

    /**
     * The value comes from a write on an IO thread, which the test's virtual clock
     * cannot hurry, so the wait is on real time, and short. Null when it never came.
     */
    private suspend fun <T> awaitOnRealTime(
        flow: Flow<T>,
        until: (T) -> Boolean,
    ): T? = withContext(Dispatchers.Default) { withTimeoutOrNull(DISK_TIMEOUT) { flow.first(until) } }

    private companion object {
        val DISK_TIMEOUT = 10.seconds
    }
}
