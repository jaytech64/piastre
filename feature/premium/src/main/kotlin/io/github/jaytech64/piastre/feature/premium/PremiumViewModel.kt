// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.feature.premium

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jaytech64.piastre.core.data.PremiumOffer
import io.github.jaytech64.piastre.core.data.PremiumOffers
import io.github.jaytech64.piastre.core.data.PremiumRepository
import io.github.jaytech64.piastre.core.model.Premium
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class PremiumUiState(
    val premium: Premium? = null,
    val offers: PremiumOffers = PremiumOffers.Loading,
)

@HiltViewModel
class PremiumViewModel
    @Inject
    constructor(
        private val repository: PremiumRepository,
    ) : ViewModel() {
        /** Play's own page for the subscription, where it is managed, cancelled or taken up again. */
        val managePage: String? = repository.managePage

        val uiState: StateFlow<PremiumUiState> =
            combine(repository.premium, repository.offers, ::PremiumUiState)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), PremiumUiState())

        fun retry() = repository.refresh()

        suspend fun redeem(code: String): Boolean = repository.redeem(code)

        fun buy(
            activity: Activity,
            offer: PremiumOffer,
        ) = repository.buy(activity, offer)

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
