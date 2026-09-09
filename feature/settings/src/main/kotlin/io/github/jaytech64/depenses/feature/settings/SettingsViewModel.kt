// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jaytech64.depenses.core.data.RawEventRepository
import io.github.jaytech64.depenses.core.data.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val captureEnabled: Boolean = true,
    val rawEventCount: Int = 0,
    val loading: Boolean = true,
)

@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        private val settings: SettingsRepository,
        rawEvents: RawEventRepository,
    ) : ViewModel() {
        val uiState: StateFlow<SettingsUiState> =
            combine(settings.captureEnabled, rawEvents.count()) { enabled, count ->
                SettingsUiState(captureEnabled = enabled, rawEventCount = count, loading = false)
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = SettingsUiState(),
            )

        fun setCaptureEnabled(enabled: Boolean) {
            viewModelScope.launch { settings.setCaptureEnabled(enabled) }
        }

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
