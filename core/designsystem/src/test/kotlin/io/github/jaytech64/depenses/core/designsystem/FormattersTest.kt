// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.designsystem

import io.github.jaytech64.depenses.core.model.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
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

    @Test
    fun `today and yesterday are named rather than dated`() {
        val today = LocalDate.of(2026, 9, 7)

        assertEquals("Aujourd'hui", Formatters.dayHeader(today, today, LABELS, Locale.CANADA_FRENCH))
        assertEquals("Hier", Formatters.dayHeader(today.minusDays(1), today, LABELS, Locale.CANADA_FRENCH))
    }

    @Test
    fun `an older day is named in the reader's language, without the year`() {
        val today = LocalDate.of(2026, 9, 7)

        val header = Formatters.dayHeader(LocalDate.of(2026, 9, 4), today, LABELS, Locale.CANADA_FRENCH)

        assertTrue(header, header.contains("septembre"))
        assertFalse(header, header.contains("2026"))
    }

    /**
     * A purchase belongs to the clock it was made under. Reading a Montréal coffee
     * back from another zone must not move it.
     */
    @Test
    fun `the time of a purchase is the time where it happened`() {
        val montreal = ZoneId.of("America/Toronto")
        val noonInMontreal =
            LocalDate
                .of(2026, 9, 7)
                .atTime(12, 47)
                .atZone(montreal)
                .toInstant()

        val shown = Formatters.time(noonInMontreal, montreal, Locale.CANADA_FRENCH)

        assertTrue(shown, shown.contains("12"))
        assertTrue(shown, shown.contains("47"))
    }

    @Test
    fun `a card is masked the way wallet masks it`() {
        assertEquals("\u2022\u20226262", Formatters.cardMask("6262"))
        assertNull(Formatters.cardMask(null))
    }

    private companion object {
        val LABELS = Formatters.RelativeDayLabels(today = "Aujourd'hui", yesterday = "Hier")
    }
}
