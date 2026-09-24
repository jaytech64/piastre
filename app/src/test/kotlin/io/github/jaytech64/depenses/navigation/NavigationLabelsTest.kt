// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationLabelsTest {
    @Test
    fun `labels that fit their share of the bar keep their words`() {
        assertTrue(labelsFit(listOf(80, 90, 70, 100), barWidth = 424, gap = 8))
    }

    @Test
    fun `a label exactly as wide as its slot still fits`() {
        assertTrue(labelsFit(listOf(100, 100, 100, 100), barWidth = 424, gap = 8))
    }

    @Test
    fun `one label a pixel too wide takes every label away`() {
        assertFalse(labelsFit(listOf(80, 101, 70, 60), barWidth = 424, gap = 8))
    }
}
