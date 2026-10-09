// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.core.database

import androidx.room.withTransaction

/**
 * Runs [block] as one database transaction: every write in it lands, or none does.
 *
 * Room's own helper lives in a library only this module depends on, so this is
 * the door a caller outside it uses to make several DAO writes succeed or fail
 * together.
 */
suspend fun <R> DepensesDatabase.inTransaction(block: suspend () -> R): R = withTransaction(block)
