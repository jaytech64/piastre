// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.data

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.FileNotFoundException
import java.io.IOException
import java.time.Clock
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/** How an export ended, in terms the screen can put on the page. */
sealed interface ExportResult {
    data class Written(
        val transactions: Int,
    ) : ExportResult

    /**
     * [reason] is the system's own message where there is one — a storage
     * provider explains itself better than this app can guess. Null when it
     * refused without saying anything.
     */
    data class Failed(
        val reason: String?,
    ) : ExportResult
}

/**
 * Puts the export in a file you picked, through the Storage Access Framework.
 *
 * SAF is what makes this possible without a storage permission: the system's own
 * picker hands back a grant for one document, so the app can write the file you
 * chose and can still see nothing else on the device.
 */
@Singleton
class ExportWriter
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val export: LedgerExport,
        private val clock: Clock,
        private val zoneId: Provider<ZoneId>,
    ) {
        /**
         * Failures are reported, never swallowed: the picker can hand back a
         * document that has since been deleted, and a storage provider can refuse
         * the write. An export that silently did nothing is worse than one that
         * says why, because you would find out when you needed the file.
         */
        suspend fun writeTo(
            uri: Uri,
            format: ExportFormat,
            labels: CsvLabels,
        ): ExportResult =
            try {
                val written =
                    context.contentResolver.openOutputStream(uri, "wt")?.use { stream ->
                        export.write(stream, format, labels)
                    }
                written?.let(ExportResult::Written) ?: ExportResult.Failed(reason = null)
            } catch (error: FileNotFoundException) {
                ExportResult.Failed(error.localizedMessage)
            } catch (error: IOException) {
                ExportResult.Failed(error.localizedMessage)
            } catch (error: SecurityException) {
                ExportResult.Failed(error.localizedMessage)
            }

        /** `depenses-2026-09-09.csv` — sorts by date and says what it is. */
        fun suggestedFileName(format: ExportFormat): String =
            "depenses-${FILE_DATE.format(clock.instant().atZone(zoneId.get()))}.${format.extension}"

        private companion object {
            val FILE_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        }
    }
