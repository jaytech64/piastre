// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.premium.play

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.jaytech64.piastre.core.model.Money
import kotlinx.coroutines.flow.first
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one trial this app started, kept because Play never says that a purchase
 * began as a trial: the trial reminder and the Premium page's end date both
 * depend on it. Lost on a reinstall, after which nothing about a trial is shown
 * rather than something wrong.
 */
@Singleton
internal class TrialRecords
    @Inject
    constructor(
        private val store: DataStore<Preferences>,
    ) {
        suspend fun current(): RecordedTrial? {
            val prefs = store.data.first()
            val token = prefs[TOKEN] ?: return null
            val endsAt = prefs[ENDS_AT] ?: return null
            val price = prefs[PRICE_MINOR] ?: return null
            val currency = prefs[PRICE_CURRENCY] ?: return null
            return RecordedTrial(token, Instant.ofEpochMilli(endsAt), Money(price, currency))
        }

        suspend fun record(trial: RecordedTrial) {
            store.edit {
                it[TOKEN] = trial.purchaseToken
                it[ENDS_AT] = trial.endsAt.toEpochMilli()
                it[PRICE_MINOR] = trial.yearlyPrice.minor
                it[PRICE_CURRENCY] = trial.yearlyPrice.currency
                it[REMINDED] = false
            }
        }

        suspend fun reminded(): Boolean = store.data.first()[REMINDED] ?: false

        suspend fun markReminded() {
            store.edit { it[REMINDED] = true }
        }

        private companion object {
            val TOKEN = stringPreferencesKey("play_trial_purchase_token")
            val ENDS_AT = longPreferencesKey("play_trial_ends_utc_millis")
            val PRICE_MINOR = longPreferencesKey("play_trial_yearly_price_minor")
            val PRICE_CURRENCY = stringPreferencesKey("play_trial_yearly_price_currency")
            val REMINDED = booleanPreferencesKey("play_trial_reminded")
        }
    }
