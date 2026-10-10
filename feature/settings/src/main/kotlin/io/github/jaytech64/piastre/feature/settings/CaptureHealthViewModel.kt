// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.feature.settings

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jaytech64.piastre.capture.listener.CaptureHealthRepository
import io.github.jaytech64.piastre.core.model.CaptureHealth
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.Clock
import java.time.Instant
import javax.inject.Inject

data class CaptureHealthUiState(
    /** Null only for the frame before the first answer arrives. */
    val health: CaptureHealth? = null,
    /**
     * When [health] was decided, so "3 days ago" on screen is measured from the
     * same clock reading the verdict was, and not from a later one.
     */
    val checkedAt: Instant = Instant.EPOCH,
)

@HiltViewModel
class CaptureHealthViewModel
    @Inject
    constructor(
        private val repository: CaptureHealthRepository,
        private val clock: Clock,
    ) : ViewModel() {
        val uiState: StateFlow<CaptureHealthUiState> =
            repository.health
                .map { CaptureHealthUiState(it, clock.instant()) }
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                    initialValue = CaptureHealthUiState(),
                )

        /**
         * Notification access can only change in a screen belonging to the system,
         * so coming back from it is the one moment worth asking again.
         */
        fun refresh() = repository.refresh()

        fun notificationAccessIntent(): Intent = repository.settingsIntent()

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
