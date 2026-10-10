// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.core.data

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Brings a file you picked into the ledger, through the Storage Access Framework.
 *
 * Like [ExportWriter], this needs no storage permission: the system picker hands
 * back a grant for the one document you chose, and the app sees nothing else.
 * The file is read off the main thread, because a year of history is a file
 * worth not freezing the screen for.
 */
@Singleton
class ImportReader
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val import: LedgerImport,
    ) {
        /**
         * Failures are reported, never swallowed: the picker can hand back a
         * document that has since been deleted, and a storage provider can refuse
         * to open it.
         */
        suspend fun read(uri: Uri): ImportReading =
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { stream -> import.read(stream) }
                } ?: ImportReading.Refused(ImportProblem.Unreadable(reason = null))
            } catch (error: IOException) {
                ImportReading.Refused(ImportProblem.Unreadable(error.localizedMessage))
            } catch (error: SecurityException) {
                ImportReading.Refused(ImportProblem.Unreadable(error.localizedMessage))
            }

        suspend fun apply(reading: ImportReading.Ready): ImportOutcome = import.apply(reading)
    }
