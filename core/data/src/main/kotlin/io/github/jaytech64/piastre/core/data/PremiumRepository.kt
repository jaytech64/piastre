// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.core.data

import android.app.Activity
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.jaytech64.piastre.core.model.Premium
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether Premium is on, for every feature that asks.
 *
 * An error is not a "no". When the store cannot be asked — no Play Store, a
 * service that will not bind — the last answer it gave stands, so a person who
 * paid never sees Premium switch off over a hiccup. Premium turns off only when
 * the store itself says it has ended.
 */
@Singleton
class PremiumRepository
    @Inject
    constructor(
        private val source: PremiumSource,
        private val store: DataStore<Preferences>,
    ) {
        private val answered: Flow<Premium> =
            source.owned.map { owned ->
                if (owned != null) {
                    remember(owned)
                    owned
                } else {
                    lastKnown() ?: Premium.Free
                }
            }

        val premium: Flow<Premium> =
            combine(answered, store.data.map { prefs -> prefs[FORCED]?.let(::decoded) }) { answer, forced ->
                forced ?: answer
            }.distinctUntilChanged()

        val offers: Flow<PremiumOffers> = source.offers

        val managePage: String? = source.managePage

        val storeName: String = source.storeName

        fun refresh() = source.refresh()

        fun buy(
            activity: Activity,
            offer: PremiumOffer,
        ) = source.buy(activity, offer)

        /**
         * Shows [state] in place of what the store says, or the store again for
         * null, so every screen can be checked in every state without buying.
         * Only the debug build's `ForcePremiumReceiver` calls it.
         */
        suspend fun forceForChecking(state: String?) {
            store.edit { prefs -> if (state == null) prefs.remove(FORCED) else prefs[FORCED] = state }
        }

        private suspend fun remember(premium: Premium) {
            store.edit { it[LAST_KNOWN] = premium.encoded() }
        }

        private suspend fun lastKnown(): Premium? = store.data.first()[LAST_KNOWN]?.let(::decoded)

        private companion object {
            val LAST_KNOWN = stringPreferencesKey("premium_last_known")
            val FORCED = stringPreferencesKey("premium_forced_for_checking")

            const val FREE = "free"
            const val LIFETIME = "lifetime"
            const val UNLOCKED = "unlocked"
            const val SUBSCRIBED = "subscribed"
            const val NO_TRIAL = "-"

            fun Premium.encoded(): String =
                when (this) {
                    Premium.Free -> {
                        FREE
                    }

                    Premium.Lifetime -> {
                        LIFETIME
                    }

                    Premium.Unlocked -> {
                        UNLOCKED
                    }

                    is Premium.Subscribed -> {
                        listOf(SUBSCRIBED, renews, trialEndsAt?.toEpochMilli() ?: NO_TRIAL).joinToString(":")
                    }
                }

            /** Null for anything unreadable, which then counts as never having been answered. */
            fun decoded(text: String): Premium? {
                val parts = text.split(":")
                return when (parts.first()) {
                    FREE -> {
                        Premium.Free
                    }

                    LIFETIME -> {
                        Premium.Lifetime
                    }

                    UNLOCKED -> {
                        Premium.Unlocked
                    }

                    SUBSCRIBED -> {
                        parts.takeIf { it.size == SUBSCRIBED_PARTS }?.let { (_, renews, trialEnd) ->
                            Premium.Subscribed(
                                renews = renews.toBooleanStrictOrNull() ?: return null,
                                trialEndsAt = trialEnd.toLongOrNull()?.let(Instant::ofEpochMilli),
                            )
                        }
                    }

                    else -> {
                        null
                    }
                }
            }

            const val SUBSCRIBED_PARTS = 3
        }
    }
