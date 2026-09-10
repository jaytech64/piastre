// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.feature.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.jaytech64.depenses.core.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File

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

    @After
    fun tearDown() {
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
            assertEquals(false, viewModel.completed.first { it != null })
        }

    @Test
    fun `completing it is remembered`() =
        runTest {
            viewModel.complete()

            assertEquals(true, viewModel.completed.first { it == true })
        }
}
