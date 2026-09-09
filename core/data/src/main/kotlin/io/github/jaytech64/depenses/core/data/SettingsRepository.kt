// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

private val Context.preferences: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class SettingsRepository
    @Inject
    constructor(
        private val store: DataStore<Preferences>,
    ) {
        val captureEnabled: Flow<Boolean> = store.data.map { it[CAPTURE_ENABLED] ?: true }

        /**
         * When the notification listener was last handed anything at all.
         *
         * Null until it has bound for the first time. This is a liveness signal
         * rather than a spending one on purpose: counting only payments would read
         * a week without shopping as a broken listener, and reading a broken
         * listener as a week without shopping is the failure this exists to catch.
         */
        val lastListenerEventAt: Flow<Instant?> =
            store.data.map { prefs -> prefs[LAST_LISTENER_EVENT]?.let(Instant::ofEpochMilli) }

        /** False until the first run has explained itself and been dismissed. */
        val onboardingCompleted: Flow<Boolean> = store.data.map { it[ONBOARDING_COMPLETED] ?: false }

        suspend fun setCaptureEnabled(enabled: Boolean) {
            store.edit { it[CAPTURE_ENABLED] = enabled }
        }

        suspend fun setOnboardingCompleted() {
            store.edit { it[ONBOARDING_COMPLETED] = true }
        }

        /**
         * Records that the listener is alive, at most once per [HEARTBEAT_RESOLUTION].
         *
         * The listener is handed every notification on the device, and DataStore
         * rewrites its whole file on each edit, so an unthrottled write here would
         * be hundreds of file rewrites a day to move a number a few seconds.
         *
         * Nothing about the notification is stored — not its package, not a count,
         * only the moment. The health screen needs to know that the pipe is open;
         * it has no business knowing what came through it.
         */
        suspend fun noteListenerEvent(at: Instant) {
            val previous = store.data.first()[LAST_LISTENER_EVENT] ?: 0L
            if (at.toEpochMilli() - previous < HEARTBEAT_RESOLUTION.toMillis()) return
            store.edit { it[LAST_LISTENER_EVENT] = at.toEpochMilli() }
        }

        /**
         * Drops the heartbeat, so health starts again from "waiting" rather than
         * from a moment that belongs to data you asked to have erased.
         */
        suspend fun forgetListenerEvent() {
            store.edit { it.remove(LAST_LISTENER_EVENT) }
        }

        private companion object {
            val CAPTURE_ENABLED = booleanPreferencesKey("capture_enabled")
            val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
            val LAST_LISTENER_EVENT = longPreferencesKey("last_listener_event_utc_millis")

            /** Coarse enough to be cheap, fine enough to be honest against a 48-hour rule. */
            val HEARTBEAT_RESOLUTION: Duration = Duration.ofMinutes(5)
        }
    }

@Module
@InstallIn(SingletonComponent::class)
object SettingsModule {
    @Provides
    @Singleton
    fun preferences(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> = context.preferences
}
