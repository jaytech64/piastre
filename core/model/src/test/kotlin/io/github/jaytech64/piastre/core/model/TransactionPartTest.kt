// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.core.model

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

    @Test
    fun `a share of an expense can go into another account`() {
        assertTrue(listOf(part(70_000), into(MORTGAGE)).canMoveOnFrom(TransactionKind.EXPENSE, CHEQUING))
    }

    @Test
    fun `a share cannot go into the account the expense came from`() {
        assertFalse(listOf(part(70_000), into(CHEQUING)).canMoveOnFrom(TransactionKind.EXPENSE, CHEQUING))
    }

    @Test
    fun `a share cannot move on from an account nobody named`() {
        assertFalse(listOf(part(70_000), into(MORTGAGE)).canMoveOnFrom(TransactionKind.EXPENSE, accountId = null))
    }

    @Test
    fun `only an expense has a share that moves on`() {
        val parts = listOf(part(70_000), into(MORTGAGE))

        assertFalse(parts.canMoveOnFrom(TransactionKind.INCOME, CHEQUING))
        assertFalse(parts.canMoveOnFrom(TransactionKind.TRANSFER, CHEQUING))
    }

    @Test
    fun `parts that go nowhere suit any kind of transaction`() {
        val parts = listOf(part(12_000), part(6_742))

        assertTrue(parts.canMoveOnFrom(TransactionKind.INCOME, accountId = null))
        assertTrue(parts.canMoveOnFrom(TransactionKind.TRANSFER, CHEQUING))
    }

    private fun part(minor: Long) = TransactionPart(Money(minor, Money.CAD))

    private fun into(accountId: Long) = TransactionPart(Money(50_000, Money.CAD), toAccountId = accountId)

    private companion object {
        const val CHEQUING = 1L
        const val MORTGAGE = 2L
    }
}
