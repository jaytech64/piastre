// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jaytech64.piastre.core.data.AccountRepository
import io.github.jaytech64.piastre.core.data.LocalDateTicker
import io.github.jaytech64.piastre.core.model.Account
import io.github.jaytech64.piastre.core.model.AccountOrigin
import io.github.jaytech64.piastre.core.model.AccountType
import io.github.jaytech64.piastre.core.model.Money
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AccountsViewModel
    @Inject
    constructor(
        private val repository: AccountRepository,
        ticker: LocalDateTicker,
    ) : ViewModel() {
        /** With what each holds today, which moves at midnight when a row dated tomorrow comes due. */
        @OptIn(ExperimentalCoroutinesApi::class)
        val accounts: StateFlow<List<Account>> =
            ticker
                .dates()
                .flatMapLatest(repository::all)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

        private val removalRefusedState = MutableStateFlow(false)

        /**
         * True after a removal was refused because a transaction still uses the
         * account, until the next change. Said on screen rather than left silent, or a
         * account that will not go away looks like a button that does nothing.
         */
        val removalRefused: StateFlow<Boolean> = removalRefusedState.asStateFlow()

        /**
         * Adds an account, or saves an existing one. One Wallet named takes only a
         * new name and an opening balance — its digits and its kind are the bank's —
         * while an account you added takes everything.
         */
        fun save(
            account: Account?,
            name: String,
            type: AccountType,
            last4: String,
            openingBalance: Money?,
        ) {
            removalRefusedState.value = false
            viewModelScope.launch {
                when {
                    account == null -> {
                        repository.add(name, type, last4, openingBalance)
                    }

                    account.origin == AccountOrigin.USER -> {
                        repository.updateAdded(account.id, name, type, last4)
                        repository.setOpeningBalance(account.id, openingBalance)
                    }

                    else -> {
                        repository.rename(account.id, name)
                        repository.setOpeningBalance(account.id, openingBalance)
                    }
                }
            }
        }

        /** Puts [account] at [toIndex] in every list of accounts; one past either end is ignored. */
        fun move(
            account: Account,
            toIndex: Int,
        ) {
            viewModelScope.launch { repository.move(account.id, toIndex) }
        }

        fun remove(account: Account) {
            viewModelScope.launch { removalRefusedState.value = !repository.remove(account.id) }
        }

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
