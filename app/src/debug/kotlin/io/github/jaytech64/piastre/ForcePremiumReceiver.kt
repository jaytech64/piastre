// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import io.github.jaytech64.piastre.core.data.PremiumRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Debug builds only: puts Premium in any state, so every screen can be checked
 * in every state on the emulator without buying anything.
 *
 *     adb shell am broadcast -n io.github.jaytech64.piastre/.ForcePremiumReceiver --es state free
 *
 * `state` is `free`, `lifetime`, `unlocked`, or `subscribed:<renews>:<trial end in epoch millis, or ->`;
 * leaving it out hands the decision back to the store.
 */
@AndroidEntryPoint
class ForcePremiumReceiver : BroadcastReceiver() {
    @Inject lateinit var premium: PremiumRepository

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                premium.forceForChecking(intent.getStringExtra("state"))
            } finally {
                pending.finish()
            }
        }
    }
}
