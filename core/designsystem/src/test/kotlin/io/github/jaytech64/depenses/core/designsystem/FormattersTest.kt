// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.designsystem

import io.github.jaytech64.depenses.core.model.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class FormattersTest {
    @Test
    fun `fr-CA puts the symbol after the amount with a comma decimal`() {
        val formatted = Formatters.money(Money(1_234_56, Money.CAD), Locale.CANADA_FRENCH)

        assertTrue(formatted, formatted.endsWith("$"))
        assertTrue(formatted, formatted.contains("234,56"))
    }

    @Test
    fun `local money is not disambiguated against dollars nobody is looking at`() {
        val formatted = Formatters.money(Money(1_234_56, Money.CAD), Locale.CANADA_FRENCH)

        assertFalse(formatted, formatted.contains("CA"))
    }

    @Test
    fun `foreign money stays distinguishable from local money`() {
        val cad = Formatters.money(Money(745, Money.CAD), Locale.CANADA_FRENCH)
        val usd = Formatters.money(Money(745, "USD"), Locale.CANADA_FRENCH)

        assertNotEquals(cad, usd)
    }

    @Test
    fun `en-CA puts the symbol first with a period decimal`() {
        val formatted = Formatters.money(Money(1_234_56, Money.CAD), Locale.CANADA)

        assertTrue(formatted, formatted.startsWith("$"))
        assertTrue(formatted, formatted.contains("234.56"))
    }

    @Test
    fun `a refund keeps its sign`() {
        val formatted = Formatters.money(Money(-500, Money.CAD), Locale.CANADA)

        assertTrue(formatted, formatted.contains("5.00"))
        assertTrue(formatted, formatted.contains("-") || formatted.contains("("))
    }

    @Test
    fun `minor units are never truncated to whole dollars`() {
        assertEquals("$0.07", Formatters.money(Money(7, Money.CAD), Locale.CANADA))
    }
}
