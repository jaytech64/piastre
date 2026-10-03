// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jaytech64.depenses.core.data.CategoryGroupRepository
import io.github.jaytech64.depenses.core.model.CategoryGroupUsage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The Categories page: every group with what it holds, and what adds one. */
@HiltViewModel
class CategoriesViewModel
    @Inject
    constructor(
        private val repository: CategoryGroupRepository,
    ) : ViewModel() {
        /** Sorted by the screen, in its own language's order; the categories in no group come last. */
        val groups: StateFlow<List<CategoryGroupUsage>> =
            repository
                .usage()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

        private val addingState = MutableStateFlow(false)
        val adding: StateFlow<Boolean> = addingState.asStateFlow()

        fun add(open: Boolean) {
            addingState.value = open
        }

        /** Typing the name of a group you already have adds nothing, so the dialog simply closes. */
        fun save(name: String) {
            addingState.value = false
            viewModelScope.launch { repository.named(name) }
        }

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
