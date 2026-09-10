// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.feature.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jaytech64.depenses.core.data.CsvLabels
import io.github.jaytech64.depenses.core.data.DataErasure
import io.github.jaytech64.depenses.core.data.ExportFormat
import io.github.jaytech64.depenses.core.data.ExportResult
import io.github.jaytech64.depenses.core.data.ExportWriter
import io.github.jaytech64.depenses.core.data.RawEventRepository
import io.github.jaytech64.depenses.core.data.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val captureEnabled: Boolean = true,
    val rawEventCount: Int = 0,
    val loading: Boolean = true,
)

/** What the last destructive or exporting action did, until another one happens. */
sealed interface LastAction {
    data class Exported(
        val transactions: Int,
    ) : LastAction

    data class ExportFailed(
        val reason: String?,
    ) : LastAction

    data object RawLogCleared : LastAction

    data object Erased : LastAction
}

@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        private val settings: SettingsRepository,
        private val exports: ExportWriter,
        private val erasure: DataErasure,
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

        private val lastActionState = MutableStateFlow<LastAction?>(null)

        /**
         * Kept on screen rather than flashed as a snackbar: "did that export
         * actually write anything" is a question you ask after looking away, and
         * an answer that has already faded is no answer.
         */
        val lastAction: StateFlow<LastAction?> = lastActionState.asStateFlow()

        fun setCaptureEnabled(enabled: Boolean) {
            viewModelScope.launch { settings.setCaptureEnabled(enabled) }
        }

        fun suggestedFileName(format: ExportFormat): String = exports.suggestedFileName(format)

        fun export(
            uri: Uri,
            format: ExportFormat,
            labels: CsvLabels,
        ) {
            viewModelScope.launch {
                lastActionState.value =
                    when (val result = exports.writeTo(uri, format, labels)) {
                        is ExportResult.Written -> LastAction.Exported(result.transactions)
                        is ExportResult.Failed -> LastAction.ExportFailed(result.reason)
                    }
            }
        }

        fun clearRawLog() {
            viewModelScope.launch {
                erasure.clearRawLog()
                lastActionState.value = LastAction.RawLogCleared
            }
        }

        fun eraseEverything() {
            viewModelScope.launch {
                erasure.eraseEverything()
                lastActionState.value = LastAction.Erased
            }
        }

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
