// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.testing

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.jaytech64.depenses.core.database.DepensesDatabase

/**
 * A real SQLite, in memory. Tests that exercise queries catch what a fake DAO
 * cannot: a wrong index, a reserved word, an upsert that resolves on the wrong key.
 */
fun inMemoryDatabase(): DepensesDatabase =
    Room
        .inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            DepensesDatabase::class.java,
        ).allowMainThreadQueries()
        .build()
