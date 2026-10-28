// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.parsing

import io.github.jaytech64.piastre.core.model.SourceApp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The allowlist the listener filters on. A package missing from here is a
 * payment app the phone silently stops recording; a package wrongly in it is a
 * package whose notification content the app starts keeping on disk.
 */
class ParserRegistryTest {
    private val registry = ParserRegistry(listOf(GoogleWalletParser()))

    @Test
    fun `watches every package its parsers declare`() {
        assertEquals(setOf("com.google.android.apps.walletnfcrel"), registry.watchedPackages)
    }

    @Test
    fun `does not watch an app that merely posts notifications`() {
        assertFalse("ch.protonmail.android" in registry.watchedPackages)
    }

    @Test
    fun `knows which app a package belongs to, and claims none it does not read`() {
        assertEquals(SourceApp.GOOGLE_WALLET, registry.sourceApp("com.google.android.apps.walletnfcrel"))
        assertNull(registry.sourceApp("ch.protonmail.android"))
    }

    @Test
    fun `every watched package has a parser that claims it`() {
        registry.watchedPackages.forEach { watched ->
            val notification =
                RawNotification(
                    sourcePackage = watched,
                    notificationKey = "0|$watched|1001|tag-1|10348",
                    postedAtUtcMillis = 1_787_235_111_656,
                    deviceLocale = "fr-CA",
                    title = null,
                    text = null,
                )
            assertTrue(registry.parserFor(notification) != null, "no parser claims $watched")
        }
    }
}
