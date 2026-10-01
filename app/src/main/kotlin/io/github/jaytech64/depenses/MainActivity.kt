// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import io.github.jaytech64.depenses.core.data.RecurringPosting
import io.github.jaytech64.depenses.core.designsystem.DepensesTheme
import io.github.jaytech64.depenses.navigation.DepensesApp
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var recurring: RecurringPosting

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DepensesTheme {
                DepensesApp()
            }
        }
    }

    /**
     * Every time the app comes back on screen, not only when its process starts:
     * a process Android kept from yesterday has slept through its midnight
     * timer, whose clock stops while the phone sleeps, so a bill due today
     * would wait for that timer instead of being in the ledger when you look.
     */
    override fun onStart() {
        super.onStart()
        recurring.schedule()
    }
}
