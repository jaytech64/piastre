// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.designsystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two decisions that keep a word whole: whether a button still fits on its
 * line, and whether a choice fits in its share.
 */
class WholeWordsTest {
    @Test
    fun `buttons that fit stay on one line`() {
        assertEquals(listOf(listOf(0, 1, 2)), flowLines(listOf(100, 80, 90), maxWidth = 300, gap = 10))
    }

    @Test
    fun `a button that no longer fits starts the next line whole`() {
        assertEquals(listOf(listOf(0, 1), listOf(2)), flowLines(listOf(160, 120, 140), maxWidth = 300, gap = 10))
    }

    @Test
    fun `the gap counts toward whether a button fits`() {
        assertEquals(listOf(listOf(0), listOf(1)), flowLines(listOf(150, 150), maxWidth = 300, gap = 8))
    }

    @Test
    fun `a button wider than the line gets a line of its own rather than being cut`() {
        assertEquals(
            listOf(listOf(0), listOf(1), listOf(2)),
            flowLines(listOf(100, 400, 100), maxWidth = 300, gap = 10),
        )
    }

    @Test
    fun `labels that fit their share leave the choice side by side`() {
        assertTrue(choicesFit(listOf(120, 110, 130), rowWidth = 900, chrome = 70))
    }

    @Test
    fun `one label too wide for its share stacks the whole choice`() {
        assertFalse(choicesFit(listOf(120, 110, 240), rowWidth = 900, chrome = 70))
    }

    @Test
    fun `the check mark's room is kept on every segment, selected or not`() {
        assertFalse(choicesFit(listOf(231), rowWidth = 300, chrome = 70))
        assertTrue(choicesFit(listOf(230), rowWidth = 300, chrome = 70))
    }
}
