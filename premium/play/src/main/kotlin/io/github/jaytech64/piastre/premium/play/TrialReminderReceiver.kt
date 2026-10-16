// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.premium.play

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Woken by the reminder's alarm, and at boot, which clears every alarm. Either
 * way it asks Play where the subscription stands, which sends the reminder if
 * it is due, and sets the alarm again for a trial still waiting for it.
 */
@AndroidEntryPoint
class TrialReminderReceiver : BroadcastReceiver() {
    @Inject internal lateinit var source: PlayPremiumSource

    @Inject internal lateinit var reminder: TrialReminder

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (intent.action == Intent.ACTION_BOOT_COMPLETED) reminder.scheduleRecorded()
                source.refreshNow()
            } finally {
                pending.finish()
            }
        }
    }
}
