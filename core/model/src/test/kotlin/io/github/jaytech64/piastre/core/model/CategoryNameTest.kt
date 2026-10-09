// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class CategoryNameTest {
    @Test
    fun `two spellings that differ only in case and accents are one category`() {
        assertEquals(key("Épicerie"), key("epicerie"))
        assertEquals(key("Épicerie"), key("ÉPICERIE"))
        assertEquals(key("Hypothèque"), key("hypotheque"))
    }

    @Test
    fun `spacing does not make a second category`() {
        assertEquals(key("Soins personnels"), key("  Soins   personnels "))
    }

    @Test
    fun `the name you see is the one you typed, tidied of stray spaces`() {
        assertEquals("Épicerie fine", CategoryName.of("  Épicerie   fine ")?.displayName)
    }

    @Test
    fun `a number in a name is part of the name`() {
        assertEquals("Loisirs 2026", CategoryName.of("Loisirs 2026")?.displayName)
        assertNotEquals(key("Loisirs"), key("Loisirs 2026"))
    }

    @Test
    fun `a name with nothing in it is no name`() {
        assertNull(CategoryName.of(""))
        assertNull(CategoryName.of("   "))
    }

    private fun key(typed: String): String? = CategoryName.of(typed)?.normalisedName
}
