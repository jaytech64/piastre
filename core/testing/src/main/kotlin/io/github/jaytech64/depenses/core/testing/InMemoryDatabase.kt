// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.testing

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.jaytech64.depenses.core.database.DepensesDatabase
import java.util.concurrent.Executor

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
        .setQueryExecutor(SAME_THREAD)
        .setTransactionExecutor(SAME_THREAD)
        .build()

/**
 * Queries and invalidations run on the caller's thread.
 *
 * Room's default executor is a real thread pool, and a test driving a virtual
 * clock cannot advance it: a flow that is waiting on an invalidation delivered
 * from that pool passes or times out depending on how loaded the machine is. A
 * test that fails only when the rest of the suite is running is worse than no
 * test, because it teaches you to re-run instead of to look.
 */
private val SAME_THREAD = Executor { it.run() }
