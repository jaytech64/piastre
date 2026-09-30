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
import java.time.YearMonth
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
    fun `a balance states its direction, and zero states none`() {
        val gain = Formatters.signedMoney(Money(10_790, Money.CAD), Locale.CANADA_FRENCH)
        val loss = Formatters.signedMoney(Money(-4_210, Money.CAD), Locale.CANADA_FRENCH)
        val zero = Formatters.signedMoney(Money(0, Money.CAD), Locale.CANADA)

        assertTrue(gain, gain.startsWith("+") && gain.contains("107,90"))
        assertTrue(loss, (loss.startsWith("-") || loss.startsWith("−")) && loss.contains("42,10"))
        assertEquals(Formatters.money(Money(0, Money.CAD), Locale.CANADA), zero)
    }

    @Test
    fun `minor units are never truncated to whole dollars`() {
        assertEquals("$0.07", Formatters.money(Money(7, Money.CAD), Locale.CANADA))
    }

    @Test
    fun `a month carries its year, because stepping back is how you reach last December`() {
        assertEquals("septembre 2026", Formatters.monthYear(YearMonth.of(2026, 9), Locale.CANADA_FRENCH))
        assertEquals("September 2026", Formatters.monthYear(YearMonth.of(2026, 9), Locale.CANADA))
    }

    @Test
    fun `the ends of a week are a day and a month, with no weekday and no year`() {
        val label = Formatters.dayAndMonth(LocalDate.of(2026, 9, 14), Locale.CANADA_FRENCH)

        assertEquals("14 septembre", label)
    }

    @Test
    fun `a week label keeps both months when the week straddles two of them`() {
        val start = Formatters.dayAndMonth(LocalDate.of(2026, 9, 28), Locale.CANADA_FRENCH)
        val end = Formatters.dayAndMonth(LocalDate.of(2026, 10, 4), Locale.CANADA_FRENCH)

        assertEquals("28 septembre", start)
        assertEquals("4 octobre", end)
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
    fun `an account is called by its name, and by its digits until it has one`() {
        assertEquals("Débit Desrives", Formatters.accountName("Débit Desrives", "0000"))
        assertEquals("\u2022\u20220000", Formatters.accountName(null, "0000"))
        assertNull(Formatters.accountName(null, null))
    }

    @Test
    fun `a card is masked the way wallet masks it`() {
        assertEquals("\u2022\u20226262", Formatters.cardMask("6262"))
        assertNull(Formatters.cardMask(null))
    }

    /**
     * The habit case: a fr-CA phone, and a person who types the decimal point
     * anyway. Reading that as 12 rather than 12,50 loses fifty cents in silence,
     * which is the one failure a money field must not have.
     */
    @Test
    fun `an amount is read the same whether it was typed with a comma or a point`() {
        assertEquals(1_250L, Formatters.parseMoney("12,50")?.minor)
        assertEquals(1_250L, Formatters.parseMoney("12.50")?.minor)
    }

    @Test
    fun `grouping marks are not mistaken for decimals`() {
        assertEquals(123_456L, Formatters.parseMoney("1 234,56")?.minor)
        assertEquals(123_456L, Formatters.parseMoney("1\u202F234,56")?.minor)
        assertEquals(123_456L, Formatters.parseMoney("1,234.56")?.minor)
        assertEquals(123_400L, Formatters.parseMoney("1.234")?.minor)
    }

    @Test
    fun `an amount survives the symbol and spacing the app itself prints`() {
        val printed = Formatters.money(Money(6_554, Money.CAD), Locale.CANADA_FRENCH)

        assertEquals(6_554L, Formatters.parseMoney(printed)?.minor)
    }

    @Test
    fun `a typed refund keeps its sign`() {
        assertEquals(-1_250L, Formatters.parseMoney("-12,50")?.minor)
    }

    @Test
    fun `text with no digits is not an amount`() {
        assertNull(Formatters.parseMoney(""))
        assertNull(Formatters.parseMoney("  $ "))
    }

    @Test
    fun `a bare amount is whole dollars, not cents`() {
        assertEquals(1_200L, Formatters.parseMoney("12")?.minor)
    }

    @Test
    fun `an amount put back in a field is plain digits with the locale's decimal mark`() {
        assertEquals("1234,56", Formatters.amountInput(Money(1_234_56, Money.CAD), Locale.CANADA_FRENCH))
        assertEquals("1234.56", Formatters.amountInput(Money(1_234_56, Money.CAD), Locale.CANADA))
        assertEquals("0,07", Formatters.amountInput(Money(7, Money.CAD), Locale.CANADA_FRENCH))
    }

    @Test
    fun `an amount put back in a field reads back unchanged, refunds included`() {
        listOf(Locale.CANADA_FRENCH, Locale.CANADA).forEach { locale ->
            listOf(1_250L, -1_250L, 7L, 1_234_56L).forEach { minor ->
                val typed = Formatters.amountInput(Money(minor, Money.CAD), locale)
                assertEquals(typed, minor, Formatters.parseMoney(typed)?.minor)
            }
        }
    }

    private companion object {
        val LABELS = Formatters.RelativeDayLabels(today = "Aujourd'hui", yesterday = "Hier")
    }

    @Test
    fun `a day of the month in English takes its ordinal, the teens included`() {
        val days = listOf(1, 2, 3, 4, 11, 12, 13, 21, 22, 23, 31).map { Formatters.dayOfMonth(it, Locale.CANADA) }

        assertEquals(listOf("1st", "2nd", "3rd", "4th", "11th", "12th", "13th", "21st", "22nd", "23rd", "31st"), days)
    }

    @Test
    fun `a day of the month in French is a plain number, except the first`() {
        assertEquals("1er", Formatters.dayOfMonth(1, Locale.CANADA_FRENCH))
        assertEquals("25", Formatters.dayOfMonth(25, Locale.CANADA_FRENCH))
    }

    @Test
    fun `a weekday is named in the locale`() {
        val friday = LocalDate.of(2026, 9, 25)

        assertEquals("vendredi", Formatters.weekday(friday, Locale.CANADA_FRENCH))
        assertEquals("Friday", Formatters.weekday(friday, Locale.CANADA))
    }

    @Test
    fun `a chip's date leaves out this year`() {
        val today = LocalDate.of(2026, 9, 29)

        assertEquals("Sep.\u00A01", Formatters.shortDate(LocalDate.of(2026, 9, 1), today, Locale.CANADA))
        assertEquals("1er\u00A0sept.", Formatters.shortDate(LocalDate.of(2026, 9, 1), today, Locale.CANADA_FRENCH))
    }

    @Test
    fun `a chip's date from another year says which`() {
        val today = LocalDate.of(2026, 9, 29)

        assertEquals("Dec.\u00A024,\u00A02025", Formatters.shortDate(LocalDate.of(2025, 12, 24), today, Locale.CANADA))
        assertEquals(
            "24\u00A0déc.\u00A02025",
            Formatters.shortDate(LocalDate.of(2025, 12, 24), today, Locale.CANADA_FRENCH),
        )
    }

    @Test
    fun `a span within one month names the month once`() {
        val today = LocalDate.of(2026, 9, 29)
        val from = LocalDate.of(2026, 9, 1)
        val to = LocalDate.of(2026, 9, 30)

        assertEquals("Sep.\u00A01 – 30", Formatters.dateSpan(from, to, today, Locale.CANADA))
        assertEquals("1er – 30\u00A0sept.", Formatters.dateSpan(from, to, today, Locale.CANADA_FRENCH))
    }

    @Test
    fun `a span across two months names both`() {
        val today = LocalDate.of(2026, 9, 29)
        val from = LocalDate.of(2026, 9, 28)
        val to = LocalDate.of(2026, 10, 3)

        assertEquals("Sep.\u00A028 – Oct.\u00A03", Formatters.dateSpan(from, to, today, Locale.CANADA))
        assertEquals("28\u00A0sept. – 3\u00A0oct.", Formatters.dateSpan(from, to, today, Locale.CANADA_FRENCH))
    }
}
