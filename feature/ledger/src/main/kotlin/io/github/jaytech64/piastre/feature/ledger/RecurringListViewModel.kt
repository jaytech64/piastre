// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.feature.ledger

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jaytech64.piastre.core.data.RecurringRepository
import io.github.jaytech64.piastre.core.model.RecurringTransaction
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Every recurring transaction, the soonest first; null while it is still being read. */
@HiltViewModel
class RecurringListViewModel
    @Inject
    constructor(
        recurring: RecurringRepository,
    ) : ViewModel() {
        val schedules: StateFlow<List<RecurringTransaction>?> =
            recurring
                .all()
                .stateIn<List<RecurringTransaction>?>(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                    null,
                )

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
