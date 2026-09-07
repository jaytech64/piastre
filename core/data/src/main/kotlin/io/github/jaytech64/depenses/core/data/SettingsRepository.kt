// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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

        suspend fun setCaptureEnabled(enabled: Boolean) {
            store.edit { it[CAPTURE_ENABLED] = enabled }
        }

        private companion object {
            val CAPTURE_ENABLED = booleanPreferencesKey("capture_enabled")
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
