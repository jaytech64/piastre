// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.model

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReportPeriodTest {
    @Test
    fun `a week starts on the Monday before the day you asked about`() {
        val wednesday = LocalDate.of(2026, 9, 16)

        val week = ReportPeriod.containing(PeriodUnit.WEEK, wednesday)

        assertEquals(LocalDate.of(2026, 9, 14), week.start)
        assertEquals(LocalDate.of(2026, 9, 20), week.endInclusive)
    }

    @Test
    fun `a Sunday belongs to the week that began six days earlier, not to the next one`() {
        val sunday = LocalDate.of(2026, 9, 20)

        assertEquals(
            ReportPeriod.containing(PeriodUnit.WEEK, LocalDate.of(2026, 9, 14)),
            ReportPeriod.containing(PeriodUnit.WEEK, sunday),
        )
    }

    @Test
    fun `a Monday is already the start of its own week`() {
        val monday = LocalDate.of(2026, 9, 14)

        assertEquals(monday, ReportPeriod.containing(PeriodUnit.WEEK, monday).start)
    }

    @Test
    fun `a month runs from the first to the last day, however long the month is`() {
        val february = ReportPeriod.containing(PeriodUnit.MONTH, LocalDate.of(2024, 2, 14))

        assertEquals(LocalDate.of(2024, 2, 1), february.start)
        assertEquals(LocalDate.of(2024, 2, 29), february.endInclusive)
    }

    @Test
    fun `a day covers itself and nothing else`() {
        val day = ReportPeriod.containing(PeriodUnit.DAY, LocalDate.of(2026, 9, 16))

        assertEquals(day.start, day.endInclusive)
    }

    @Test
    fun `stepping back from the thirty-first lands on a month that has no thirty-first`() {
        val march = ReportPeriod.containing(PeriodUnit.MONTH, LocalDate.of(2026, 3, 31))

        val february = march.previous()

        assertEquals(LocalDate.of(2026, 2, 1), february.start)
        assertEquals(LocalDate.of(2026, 2, 28), february.endInclusive)
    }

    @Test
    fun `stepping crosses a year boundary in both directions`() {
        val firstWeekOf2027 = ReportPeriod.containing(PeriodUnit.WEEK, LocalDate.of(2027, 1, 1))

        assertEquals(LocalDate.of(2026, 12, 21), firstWeekOf2027.previous().start)
        assertEquals(firstWeekOf2027, firstWeekOf2027.previous().next())
    }

    @Test
    fun `a period knows which days it covers`() {
        val week = ReportPeriod.containing(PeriodUnit.WEEK, LocalDate.of(2026, 9, 16))

        assertTrue(LocalDate.of(2026, 9, 14) in week)
        assertTrue(LocalDate.of(2026, 9, 20) in week)
        assertFalse(LocalDate.of(2026, 9, 13) in week)
        assertFalse(LocalDate.of(2026, 9, 21) in week)
    }

    @Test
    fun `a week straddling two months reports the month it began in`() {
        val week = ReportPeriod.containing(PeriodUnit.WEEK, LocalDate.of(2026, 10, 1))

        assertEquals(LocalDate.of(2026, 9, 28), week.start)
        assertEquals(9, week.month.monthValue)
    }
}
