// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.model

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class RecurrenceTest {
    @Test
    fun `a monthly bill on the 31st falls on the last day of a short month, then back on the 31st`() {
        val rent = Recurrence(Rhythm.MONTHLY, LocalDate.of(2026, 1, 31))

        assertEquals(
            listOf(
                LocalDate.of(2026, 2, 28),
                LocalDate.of(2026, 3, 31),
                LocalDate.of(2026, 4, 30),
                LocalDate.of(2026, 5, 31),
            ),
            rent.datesBetween(after = LocalDate.of(2026, 1, 31), through = LocalDate.of(2026, 5, 31)),
        )
    }

    @Test
    fun `a yearly date on 29 February falls on the 28th in the years without one`() {
        val premium = Recurrence(Rhythm.YEARLY, LocalDate.of(2028, 2, 29))

        assertEquals(
            listOf(
                LocalDate.of(2029, 2, 28),
                LocalDate.of(2030, 2, 28),
                LocalDate.of(2031, 2, 28),
                LocalDate.of(2032, 2, 29),
            ),
            premium.datesBetween(after = LocalDate.of(2028, 2, 29), through = LocalDate.of(2032, 12, 31)),
        )
    }

    @Test
    fun `a pay every two weeks keeps its weekday across months and years`() {
        val pay = Recurrence(Rhythm.EVERY_TWO_WEEKS, LocalDate.of(2026, 12, 17))

        val dates = pay.datesBetween(after = LocalDate.of(2026, 12, 17), through = LocalDate.of(2027, 2, 28))

        assertEquals(
            listOf(
                LocalDate.of(2026, 12, 31),
                LocalDate.of(2027, 1, 14),
                LocalDate.of(2027, 1, 28),
                LocalDate.of(2027, 2, 11),
                LocalDate.of(2027, 2, 25),
            ),
            dates,
        )
    }

    @Test
    fun `a weekly transaction comes back seven days later`() {
        val lesson = Recurrence(Rhythm.WEEKLY, LocalDate.of(2026, 9, 25))

        assertEquals(LocalDate.of(2026, 10, 2), lesson.nextAfter(LocalDate.of(2026, 9, 25)))
        assertEquals(LocalDate.of(2026, 10, 2), lesson.nextAfter(LocalDate.of(2026, 10, 1)))
    }

    @Test
    fun `the day after a date before the first one is the first one`() {
        val bill = Recurrence(Rhythm.MONTHLY, LocalDate.of(2026, 10, 25))

        assertEquals(LocalDate.of(2026, 10, 25), bill.nextAfter(LocalDate.of(2026, 9, 1)))
    }

    @Test
    fun `the next date after one years away is found without drifting`() {
        val bill = Recurrence(Rhythm.MONTHLY, LocalDate.of(2020, 1, 31))

        assertEquals(LocalDate.of(2031, 3, 31), bill.nextAfter(LocalDate.of(2031, 2, 28)))
    }

    @Test
    fun `an end date on a due day is the last day it falls on`() {
        val loan = Recurrence(Rhythm.MONTHLY, LocalDate.of(2026, 9, 15), lastDate = LocalDate.of(2026, 12, 15))

        assertEquals(
            listOf(LocalDate.of(2026, 10, 15), LocalDate.of(2026, 11, 15), LocalDate.of(2026, 12, 15)),
            loan.datesBetween(after = LocalDate.of(2026, 9, 15), through = LocalDate.of(2027, 6, 1)),
        )
        assertNull(loan.nextAfter(LocalDate.of(2026, 12, 15)))
    }

    @Test
    fun `nothing is due between two dates that fall inside one period`() {
        val bill = Recurrence(Rhythm.MONTHLY, LocalDate.of(2026, 9, 25))

        assertEquals(
            emptyList(),
            bill.datesBetween(after = LocalDate.of(2026, 9, 25), through = LocalDate.of(2026, 10, 24)),
        )
    }

    @Test
    fun `a recurrence cannot end before it starts`() {
        assertFailsWith<IllegalArgumentException> {
            Recurrence(Rhythm.MONTHLY, LocalDate.of(2026, 9, 25), lastDate = LocalDate.of(2026, 9, 24))
        }
    }
}
