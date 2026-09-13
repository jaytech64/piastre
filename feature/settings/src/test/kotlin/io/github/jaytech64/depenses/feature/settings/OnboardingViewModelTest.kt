// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.feature.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.viewModelScope
import io.github.jaytech64.depenses.core.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
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

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
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

    /**
     * Once on CI this waited a full minute and never saw the value. The disk is
     * checked before the view model, so if it happens again the failure says
     * which of the two lost it.
     */
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
