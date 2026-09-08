// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.feature.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jaytech64.depenses.core.data.ReviewRepository
import io.github.jaytech64.depenses.core.model.Money
import io.github.jaytech64.depenses.core.model.ReviewItem
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ReviewUiState(
    val items: List<ReviewItem> = emptyList(),
    val loading: Boolean = true,
) {
    val isEmpty: Boolean get() = items.isEmpty() && !loading
}

/**
 * The inbox of purchases the parser refused to guess at.
 *
 * Every ruling is a one-way write and the list re-emits from the database, so a
 * row leaves the screen because it is no longer in the queue — never because
 * this class removed it optimistically and hoped.
 */
@HiltViewModel
class ReviewViewModel
    @Inject
    constructor(
        private val repository: ReviewRepository,
    ) : ViewModel() {
        val uiState: StateFlow<ReviewUiState> =
            repository
                .queue()
                .map { ReviewUiState(items = it, loading = false) }
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                    initialValue = ReviewUiState(),
                )

        fun confirm(id: Long) {
            viewModelScope.launch { repository.confirm(id) }
        }

        fun dismiss(id: Long) {
            viewModelScope.launch { repository.dismiss(id) }
        }

        fun correct(
            id: Long,
            amount: Money,
            merchantName: String?,
        ) {
            viewModelScope.launch { repository.correct(id, amount, merchantName) }
        }

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
