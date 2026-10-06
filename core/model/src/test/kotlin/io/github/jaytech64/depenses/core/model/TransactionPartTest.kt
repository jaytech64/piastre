// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TransactionPartTest {
    private val costco = Money(18_742, Money.CAD)

    @Test
    fun `parts that add up to the amount to the cent divide it`() {
        assertTrue(listOf(part(12_000), part(6_742)).divide(costco))
    }

    @Test
    fun `parts a cent short do not divide the amount, and say what is left`() {
        val parts = listOf(part(12_000), part(6_741))

        assertFalse(parts.divide(costco))
        assertEquals(Money(1, Money.CAD), parts.leftOf(costco))
    }

    @Test
    fun `parts that exceed the amount leave less than nothing`() {
        val parts = listOf(part(12_000), part(7_000))

        assertFalse(parts.divide(costco))
        assertEquals(Money(-258, Money.CAD), parts.leftOf(costco))
    }

    @Test
    fun `a single part divides nothing`() {
        assertFalse(listOf(part(18_742)).divide(costco))
    }

    @Test
    fun `a part of nothing is not a share`() {
        assertFalse(listOf(part(18_742), part(0)).divide(costco))
    }

    @Test
    fun `a part in another currency is not a share of this amount`() {
        assertFalse(listOf(part(12_000), TransactionPart(Money(6_742, "USD"))).divide(costco))
    }

    private fun part(minor: Long) = TransactionPart(Money(minor, Money.CAD))
}
