// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.data

import android.database.sqlite.SQLiteException
import android.util.Log
import io.github.jaytech64.depenses.parsing.ParserRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Parses whatever the inline path left behind, and re-parses what an older
 * parser got wrong.
 *
 * The listener parses each notification the moment it stores it, so in normal
 * operation this finds nothing to do. It exists for the case that is invisible
 * from inside the listener: the process dying between the write and the parse,
 * after which the next replay is refused as a duplicate and no one ever retries
 * that row.
 *
 * It is also where a parser fix reaches the past: the same two moments that
 * drain the pending rows check whether the parsers have moved, and re-read the
 * raw log when they have.
 *
 * And it is where the raw log stops growing: the retention sweep runs last, so a
 * parser fix always gets to repair history before the payload it would have read
 * is stripped.
 *
 * This was going to be a WorkManager job. WorkManager declares
 * `ACCESS_NETWORK_STATE`, `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED` and
 * `FOREGROUND_SERVICE` in its own manifest, and the merged-manifest check caught
 * the first of those — this app ships with no network permission so that the data
 * physically cannot leave the device. What WorkManager would have bought is
 * running without the app: worth nothing here, because both moments that trigger
 * a drain, the listener binding and the app launching, already have a live
 * process, and a row left pending is only ever waiting, never lost.
 */
@Singleton
class CaptureCatchUp
    @Inject
    constructor(
        private val pipeline: ParsePipeline,
        private val registry: ParserRegistry,
        private val retention: RawLogRetention,
        private val settings: SettingsRepository,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /**
         * A failure here is logged and left alone rather than retried: the rows are
         * still on disk, and the next listener bind or app launch runs the same pass
         * again. The [IOException] is DataStore's — a preference file it could not
         * read costs a sweep that is redone next launch, not a lost notification.
         */
        fun schedule() {
            scope.launch {
                try {
                    val drained = pipeline.drainPending()
                    if (drained > 0) Log.i(TAG, "parsed $drained notification(s) the listener never reached")
                    val reread = resweepIfParsersChanged()
                    if (reread > 0) Log.i(TAG, "re-read $reread notification(s) after a parser change")
                    val redacted = retention.purgeExpired()
                    if (redacted > 0) Log.i(TAG, "stripped the content of $redacted notification(s) over 90 days old")
                } catch (error: SQLiteException) {
                    Log.e(TAG, "could not catch up on the raw events; the next bind will retry", error)
                } catch (error: IOException) {
                    Log.e(TAG, "could not read the parser fingerprint; the next bind will retry", error)
                }
            }
        }

        /**
         * Re-reads history when, and only when, the parser line-up has moved since
         * the last pass, and returns how many rows that was.
         *
         * The guard matters more than the sweep. Both callers run on a moment the
         * user is waiting through — the app launching, the listener binding — and a
         * sweep with nothing to fix still costs a scan of every notification ever
         * captured. Comparing a fingerprint costs one preference read.
         *
         * The fingerprint is stamped only after the sweep completes, so a pass that
         * dies partway is retried on the next launch rather than recorded as done.
         */
        suspend fun resweepIfParsersChanged(): Int {
            val fingerprint = registry.fingerprint
            if (settings.lastParserFingerprint.first() == fingerprint) return 0
            val reread = pipeline.resweep()
            settings.setParserFingerprint(fingerprint)
            return reread
        }

        private companion object {
            const val TAG = "CaptureCatchUp"
        }
    }
