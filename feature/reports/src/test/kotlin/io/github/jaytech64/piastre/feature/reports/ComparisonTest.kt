// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.feature.reports

import io.github.jaytech64.piastre.core.model.Money
import org.junit.Assert.assertEquals
import org.junit.Test

class ComparisonTest {
    @Test
    fun `the same amount twice is no change, never a rise of zero`() {
        assertEquals(Comparison.Unchanged, comparisonOf(cad(11_111), cad(11_111)))
    }

    @Test
    fun `the same amount per day over different lengths is no change`() {
        assertEquals(Comparison.Unchanged, comparisonOf(cad(16_000), cad(31_000), nowDays = 16, beforeDays = 31))
    }

    @Test
    fun `a move under one percent keeps its direction and is not called the same`() {
        assertEquals(Comparison.Slight(up = true), comparisonOf(cad(100_400), cad(100_000)))
        assertEquals(Comparison.Slight(up = false), comparisonOf(cad(99_600), cad(100_000)))
    }

    @Test
    fun `a move of a whole percent or more is that percent`() {
        assertEquals(Comparison.Change(1), comparisonOf(cad(101_000), cad(100_000)))
        assertEquals(Comparison.Change(-1), comparisonOf(cad(99_000), cad(100_000)))
    }

    @Test
    fun `nothing before has no percent to report`() {
        assertEquals(Comparison.NothingBefore, comparisonOf(cad(500), cad(0)))
        assertEquals(Comparison.NothingEither, comparisonOf(cad(0), cad(0)))
    }

    private fun cad(minor: Long) = Money(minor, Money.CAD)
}
