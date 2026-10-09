// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jaytech64.piastre.core.data.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel
    @Inject
    constructor(
        private val settings: SettingsRepository,
    ) : ViewModel() {
        /**
         * Null while the answer is still on disk. The app shows nothing at all in
         * that frame rather than guessing: guessing false flashes the welcome
         * screen at someone who onboarded months ago.
         */
        val completed: StateFlow<Boolean?> =
            settings.onboardingCompleted
                .map<Boolean, Boolean?> { it }
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                    initialValue = null,
                )

        fun complete() {
            viewModelScope.launch { settings.setOnboardingCompleted() }
        }

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
