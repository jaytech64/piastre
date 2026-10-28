// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.feature.review

import io.github.jaytech64.piastre.core.model.SourceApp
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class SourceAppNameTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun `an english phone calls the wallet app google wallet`() {
        assertEquals("Google Wallet", context.getString(SourceApp.GOOGLE_WALLET.displayName()))
    }

    @Test
    @Config(qualifiers = "fr-rCA")
    fun `a quebec phone calls the wallet app google portefeuille`() {
        assertEquals("Google Portefeuille", context.getString(SourceApp.GOOGLE_WALLET.displayName()))
    }
}
