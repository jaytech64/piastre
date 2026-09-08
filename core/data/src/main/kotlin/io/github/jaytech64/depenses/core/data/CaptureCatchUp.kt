// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.data

import android.database.sqlite.SQLiteException
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Parses whatever the inline path left behind.
 *
 * The listener parses each notification the moment it stores it, so in normal
 * operation this finds nothing to do. It exists for the case that is invisible
 * from inside the listener: the process dying between the write and the parse,
 * after which the next replay is refused as a duplicate and no one ever retries
 * that row.
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
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /**
         * A failure here is logged and left alone rather than retried: the rows are
         * still on disk in `PENDING`, and the next listener bind or app launch runs
         * the same pass again.
         */
        fun schedule() {
            scope.launch {
                try {
                    val drained = pipeline.drainPending()
                    if (drained > 0) Log.i(TAG, "parsed $drained notification(s) the listener never reached")
                } catch (error: SQLiteException) {
                    Log.e(TAG, "could not drain the pending raw events; the next bind will retry", error)
                }
            }
        }

        private companion object {
            const val TAG = "CaptureCatchUp"
        }
    }
