// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import io.github.jaytech64.depenses.core.data.CaptureCatchUp
import io.github.jaytech64.depenses.core.data.RecurringPosting
import javax.inject.Inject

@HiltAndroidApp
class DepensesApplication : Application() {
    @Inject
    lateinit var catchUp: CaptureCatchUp

    @Inject
    lateinit var recurring: RecurringPosting

    /**
     * Launching the app is the one moment the user is certain to notice a gap, so
     * it is also when anything the listener recorded but never parsed is picked up.
     *
     * The process also starts when Android binds the listener, so this is where
     * recurring transactions begin to be written as their days come, app open or
     * not.
     */
    override fun onCreate() {
        super.onCreate()
        catchUp.schedule()
        recurring.followDays()
    }
}
