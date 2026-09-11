// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jaytech64.depenses.core.data.CardRepository
import io.github.jaytech64.depenses.core.model.Card
import io.github.jaytech64.depenses.core.model.CardOrigin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CardsViewModel
    @Inject
    constructor(
        private val repository: CardRepository,
    ) : ViewModel() {
        val cards: StateFlow<List<Card>> =
            repository.all().stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

        private val removalRefusedState = MutableStateFlow(false)

        /**
         * True after a removal was refused because a transaction still uses the
         * card, until the next change. Said on screen rather than left silent, or a
         * card that will not go away looks like a button that does nothing.
         */
        val removalRefused: StateFlow<Boolean> = removalRefusedState.asStateFlow()

        /**
         * Adds a card, or saves an existing one. A Wallet card only takes a new
         * name — its digits are the bank's — while a card you added takes both.
         */
        fun save(
            card: Card?,
            name: String,
            last4: String,
        ) {
            removalRefusedState.value = false
            viewModelScope.launch {
                when {
                    card == null -> repository.add(name, last4)
                    card.origin == CardOrigin.USER -> repository.updateAdded(card.id, name, last4)
                    else -> repository.rename(card.id, name)
                }
            }
        }

        fun remove(card: Card) {
            viewModelScope.launch { removalRefusedState.value = !repository.remove(card.id) }
        }

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
