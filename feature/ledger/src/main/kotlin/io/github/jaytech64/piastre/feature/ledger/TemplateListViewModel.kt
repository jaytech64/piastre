// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.feature.ledger

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jaytech64.piastre.core.data.TemplateRepository
import io.github.jaytech64.piastre.core.model.TransactionTemplate
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Every template, the one last used first; null while they are still being read. */
@HiltViewModel
class TemplateListViewModel
    @Inject
    constructor(
        templates: TemplateRepository,
    ) : ViewModel() {
        val templates: StateFlow<List<TransactionTemplate>?> =
            templates
                .all()
                .stateIn<List<TransactionTemplate>?>(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                    null,
                )

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
