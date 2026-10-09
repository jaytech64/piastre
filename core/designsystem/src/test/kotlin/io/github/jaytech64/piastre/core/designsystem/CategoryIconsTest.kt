// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.core.designsystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CategoryIconsTest {
    @Test
    fun `every icon brought in can be picked under its own name, but for the one a split shows`() {
        val broughtIn =
            R.drawable::class.java.fields
                .filter { it.name.startsWith(PREFIX) }
                .associate { it.name.removePrefix(PREFIX) to it.getInt(null) }

        assertEquals(broughtIn - "call_split", CATEGORY_ICONS)
    }

    @Test
    fun `no name and a name from another build both have no drawable`() {
        assertNull(categoryIconRes(null))
        assertNull(categoryIconRes("an_icon_from_later"))
    }

    @Test
    fun `a category with no icon is its initials whole, and its first letter in half a circle`() {
        assertEquals(Mark.Letters("ÉF"), categoryMark("Épicerie fine", icon = null))
        assertEquals(Mark.Letters("É"), categoryHalfMark("Épicerie fine", icon = null))
        assertEquals(Mark.Letters("?"), categoryMark("  ", icon = null))
    }

    private companion object {
        const val PREFIX = "category_icon_"
    }
}
