// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContainsTypedTest {
    @Test
    fun `a search typed without accents finds a name that has them`() {
        assertTrue("Café Myriade".containsTyped("cafe"))
        assertTrue("Marché Jean-Talon".containsTyped("MARCHE"))
    }

    @Test
    fun `a search typed with accents finds a name Wallet wrote without them`() {
        assertTrue("CAFE DEPOT".containsTyped("café"))
    }

    @Test
    fun `a search matches anywhere in the name`() {
        assertTrue("Cafe Depot Ste-Catherine".containsTyped("catherine"))
    }

    @Test
    fun `a search with nothing typed yet finds everything`() {
        assertTrue("Metro Plus".containsTyped(""))
        assertTrue("Metro Plus".containsTyped("  "))
    }

    @Test
    fun `a search for something else finds nothing`() {
        assertFalse("Metro Plus".containsTyped("IGA"))
    }
}
