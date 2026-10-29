// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.feature.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.viewModelScope
import io.github.jaytech64.piastre.core.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.seconds

/**
 * Runs on real threads only, with no test dispatcher anywhere.
 *
 * DataStore runs an `edit`'s transform back in the caller's context, and the
 * caller here is `viewModelScope` on Main. With an `UnconfinedTestDispatcher`
 * standing in for Main, about one write in eight never reached disk, under
 * `runTest` and `runBlocking` alike (37 and 42 lost of 300); a real single-thread
 * Main lost none in 3000 rounds. The app's own Main is real, so this was only
 * ever the test's failure.
 */
class OnboardingViewModelTest {
    private val main: ExecutorCoroutineDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private lateinit var storeFile: File
    private lateinit var settings: SettingsRepository
    private lateinit var viewModel: OnboardingViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(main)
        storeFile = File.createTempFile("settings", ".preferences_pb").apply { delete() }
        settings = SettingsRepository(PreferenceDataStoreFactory.create(scope = storeScope) { storeFile })
        viewModel = OnboardingViewModel(settings)
    }

    /**
     * Both scopes are joined before Main is reset: DataStore resumes the view
     * model's write on Main only after it publishes the value the test waited
     * for, and that resume must not reach a Main that is no longer there.
     */
    @After
    fun tearDown() {
        runBlocking {
            viewModel.viewModelScope.coroutineContext.job
                .cancelAndJoin()
            storeScope.coroutineContext.job.cancelAndJoin()
        }
        storeFile.delete()
        Dispatchers.resetMain()
        main.close()
    }

    @Test
    fun `nothing is claimed before the answer has been read off disk`() {
        assertNull(viewModel.completed.value)
    }

    @Test
    fun `a fresh install has not been onboarded`() =
        runBlocking {
            assertEquals(false, awaitValue(viewModel.completed) { it != null })
        }

    /** The disk is checked before the view model, so a recurrence says which of the two lost the value. */
    @Test
    fun `completing it is remembered`() =
        runBlocking {
            viewModel.complete()

            assertEquals("the write never reached disk", true, awaitValue(settings.onboardingCompleted) { it })
            assertEquals(
                "the write reached disk, but the view model never saw it",
                true,
                awaitValue(viewModel.completed) { it == true },
            )
        }

    /** Null when the value never came. */
    private suspend fun <T> awaitValue(
        flow: Flow<T>,
        until: (T) -> Boolean,
    ): T? = withTimeoutOrNull(DISK_TIMEOUT) { flow.first(until) }

    private companion object {
        val DISK_TIMEOUT = 10.seconds
    }
}
