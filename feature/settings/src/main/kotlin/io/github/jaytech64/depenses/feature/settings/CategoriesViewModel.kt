// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jaytech64.depenses.core.data.CategoryRepository
import io.github.jaytech64.depenses.core.model.CategoryUsage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Which dialog is open over the categories, if any. */
sealed interface CategoryEdit {
    data object New : CategoryEdit

    /** [refused] is true after a rename onto a name another category already has. */
    data class Existing(
        val usage: CategoryUsage,
        val refused: Boolean = false,
    ) : CategoryEdit

    data class ConfirmDelete(
        val usage: CategoryUsage,
    ) : CategoryEdit
}

@HiltViewModel
class CategoriesViewModel
    @Inject
    constructor(
        private val repository: CategoryRepository,
    ) : ViewModel() {
        /** Sorted by the screen, in its own language's order. */
        val categories: StateFlow<List<CategoryUsage>> =
            repository.all().stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

        private val editingState = MutableStateFlow<CategoryEdit?>(null)
        val editing: StateFlow<CategoryEdit?> = editingState.asStateFlow()

        fun edit(edit: CategoryEdit?) {
            editingState.value = edit
        }

        /**
         * Adds a category, or renames the one being edited. A rename another
         * category's name refuses keeps the dialog open and says why, rather than
         * closing on a change that did not happen.
         */
        fun save(name: String) {
            val edit = editingState.value
            viewModelScope.launch {
                when (edit) {
                    is CategoryEdit.Existing -> {
                        editingState.value =
                            if (repository.rename(edit.usage.category.id, name)) null else edit.copy(refused = true)
                    }

                    CategoryEdit.New -> {
                        repository.named(name)
                        editingState.value = null
                    }

                    is CategoryEdit.ConfirmDelete, null -> {
                        Unit
                    }
                }
            }
        }

        fun delete(usage: CategoryUsage) {
            editingState.value = null
            viewModelScope.launch { repository.delete(usage.category.id) }
        }

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
