// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.core.model

import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BudgetTest {
    private val groceries = BudgetTarget.Category(1)
    private val home = BudgetTarget.Group(1)
    private val september = YearMonth.of(2026, 9)
    private val october = YearMonth.of(2026, 10)
    private val november = YearMonth.of(2026, 11)

    private fun cad(minor: Long) = Money(minor, Money.CAD)

    @Test
    fun `a budget counts from the month it was set, and not before`() {
        val changes = listOf(BudgetChange(groceries, october, cad(40_000)))

        assertEquals(emptyMap<BudgetTarget, Money>(), budgetsIn(september, changes))
        assertEquals(mapOf<BudgetTarget, Money>(groceries to cad(40_000)), budgetsIn(october, changes))
        assertEquals(mapOf<BudgetTarget, Money>(groceries to cad(40_000)), budgetsIn(november, changes))
    }

    @Test
    fun `a changed amount leaves the months already judged as they were`() {
        val changes =
            listOf(
                BudgetChange(groceries, september, cad(40_000)),
                BudgetChange(groceries, november, cad(45_000)),
            )

        assertEquals(cad(40_000), budgetsIn(october, changes)[groceries])
        assertEquals(cad(45_000), budgetsIn(november, changes)[groceries])
    }

    @Test
    fun `a stopped budget is gone from that month and kept before it`() {
        val changes =
            listOf(
                BudgetChange(groceries, september, cad(40_000)),
                BudgetChange(groceries, november, null),
            )

        assertEquals(cad(40_000), budgetsIn(october, changes)[groceries])
        assertNull(budgetsIn(november, changes)[groceries])
    }

    @Test
    fun `a category and the group it is in are budgeted apart`() {
        val changes =
            listOf(
                BudgetChange(groceries, october, cad(40_000)),
                BudgetChange(home, october, cad(150_000)),
            )

        assertEquals(
            mapOf<BudgetTarget, Money>(groceries to cad(40_000), home to cad(150_000)),
            budgetsIn(october, changes),
        )
    }

    @Test
    fun `what is left is shared among the days left, today included`() {
        assertEquals(cad(1_000), leftPerDay(cad(3_000), october, LocalDate.of(2026, 10, 29)))
        assertEquals(cad(4_000), leftPerDay(cad(4_000), october, LocalDate.of(2026, 10, 31)))
    }

    @Test
    fun `nothing is shared when nothing is left, or the month is not this one`() {
        assertNull(leftPerDay(cad(0), october, LocalDate.of(2026, 10, 15)))
        assertNull(leftPerDay(cad(-500), october, LocalDate.of(2026, 10, 15)))
        assertNull(leftPerDay(cad(3_000), september, LocalDate.of(2026, 10, 15)))
    }
}
