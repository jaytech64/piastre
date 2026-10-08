// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.feature.settings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jaytech64.depenses.core.data.CategoryGroupRepository
import io.github.jaytech64.depenses.core.data.CategoryRepository
import io.github.jaytech64.depenses.core.model.CategoryGroup
import io.github.jaytech64.depenses.core.model.CategoryUsage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The id of the group a page shows; absent for the page of categories in none. */
const val CATEGORY_GROUP_ID_ARG = "categoryGroupId"

/** Which dialog is open over a group's page, if any. */
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

    /** [refused] is true after a rename onto a name another group already has. */
    data class RenameGroup(
        val refused: Boolean = false,
    ) : CategoryEdit

    data object ConfirmDeleteGroup : CategoryEdit
}

/**
 * One group's page: its categories, what adds, renames, moves and deletes them,
 * and the group's own name. The page of categories in no group is the same page
 * with no group to rename or delete.
 */
@HiltViewModel
class CategoryGroupViewModel
    @Inject
    constructor(
        savedState: SavedStateHandle,
        private val repository: CategoryRepository,
        private val categoryGroups: CategoryGroupRepository,
    ) : ViewModel() {
        /** Null on the page of categories in no group. */
        val groupId: Long? = savedState.get<Long>(CATEGORY_GROUP_ID_ARG)

        /** Sorted by the screen, in its own language's order. */
        val categories: StateFlow<List<CategoryUsage>> =
            categoryGroups.categoriesIn(groupId).stateIn(viewModelScope, SHARING, emptyList())

        /** Every group, for the field that moves a category to another. */
        val groups: StateFlow<List<CategoryGroup>> =
            categoryGroups.all().stateIn(viewModelScope, SHARING, emptyList())

        /** This page's group; null on the ungrouped page, and once the group is deleted. */
        val group: StateFlow<CategoryGroup?> =
            categoryGroups
                .all()
                .map { groups -> groups.firstOrNull { it.id == groupId } }
                .stateIn(viewModelScope, SHARING, null)

        private val editingState = MutableStateFlow<CategoryEdit?>(null)
        val editing: StateFlow<CategoryEdit?> = editingState.asStateFlow()

        private val deletedState = MutableStateFlow(false)

        /** True once this page's group is deleted, so the page can close. */
        val deleted: StateFlow<Boolean> = deletedState.asStateFlow()

        fun edit(edit: CategoryEdit?) {
            editingState.value = edit
        }

        /**
         * Adds a category to [toGroupId] with [icon], or renames the one being
         * edited, moves it there and gives it that icon. A rename another category's
         * name refuses keeps the dialog open and says why, and changes nothing else,
         * rather than closing on a change that did not happen.
         */
        fun save(
            name: String,
            toGroupId: Long?,
            icon: String?,
        ) {
            val edit = editingState.value
            viewModelScope.launch {
                when (edit) {
                    is CategoryEdit.Existing -> {
                        val category = edit.usage.category
                        if (repository.rename(category.id, name)) {
                            if (toGroupId != category.groupId) categoryGroups.move(category.id, toGroupId)
                            if (icon != category.icon) categoryGroups.setIcon(category.id, icon)
                            editingState.value = null
                        } else {
                            editingState.value = edit.copy(refused = true)
                        }
                    }

                    CategoryEdit.New -> {
                        repository.named(name, toGroupId, icon)
                        editingState.value = null
                    }

                    is CategoryEdit.ConfirmDelete,
                    is CategoryEdit.RenameGroup,
                    CategoryEdit.ConfirmDeleteGroup,
                    null,
                    -> {
                        Unit
                    }
                }
            }
        }

        fun delete(usage: CategoryUsage) {
            editingState.value = null
            viewModelScope.launch { repository.delete(usage.category.id) }
        }

        /** Keeps the dialog open, saying why, when another group already has the name. */
        fun renameGroup(name: String) {
            val id = groupId ?: return
            viewModelScope.launch {
                editingState.value =
                    if (categoryGroups.rename(id, name)) null else CategoryEdit.RenameGroup(refused = true)
            }
        }

        fun deleteGroup() {
            val id = groupId ?: return
            editingState.value = null
            viewModelScope.launch {
                categoryGroups.delete(id)
                deletedState.value = true
            }
        }

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
            val SHARING = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS)
        }
    }
