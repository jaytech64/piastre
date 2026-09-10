// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.data

import io.github.jaytech64.depenses.core.database.DepensesDatabase
import io.github.jaytech64.depenses.core.database.RawNotificationEventDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The two destructive actions Law 25 obliges, kept apart because they answer
 * different questions.
 */
@Singleton
class DataErasure
    @Inject
    constructor(
        private val database: DepensesDatabase,
        private val rawEvents: RawNotificationEventDao,
        private val settings: SettingsRepository,
    ) {
        /**
         * Everything: transactions, merchants, aliases, the raw log, and the one
         * timestamp kept outside the database.
         *
         * The capture switch and the fact that onboarding has been seen are left
         * alone. They are settings, not data about you, and resetting them would
         * greet you with the welcome screen as if the app had been reinstalled —
         * which is a claim about what happened, and a false one.
         */
        suspend fun eraseEverything() {
            // clearAllTables blocks and does not offer a suspending form, so it is
            // moved off whichever thread the confirmation dialog was tapped on.
            withContext(Dispatchers.IO) { database.clearAllTables() }
            settings.forgetListenerEvent()
        }

        /**
         * The raw log only. Your ledger survives, which is the point: this is the
         * sensitive half, and it is the half you can drop without losing history.
         *
         * What it costs is retroactivity — a later parser fix can no longer re-read
         * what it deletes.
         */
        suspend fun clearRawLog() = rawEvents.deleteAll()
    }
