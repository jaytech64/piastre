// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class TransferTest {
    @Test
    fun `money moved into a bank account has left`() {
        assertEquals(-SIXTY, transfer(AccountType.CARD, AccountType.BANK).signedAmount)
        assertEquals(-SIXTY, transfer(AccountType.CASH, AccountType.BANK).signedAmount)
    }

    @Test
    fun `money moved out of a bank account has come back`() {
        assertEquals(SIXTY, transfer(AccountType.BANK, AccountType.CARD).signedAmount)
        assertEquals(SIXTY, transfer(AccountType.BANK, AccountType.CASH).signedAmount)
    }

    @Test
    fun `money moved between cards and cash moves the ledger by nothing`() {
        assertEquals(NOTHING, transfer(AccountType.CARD, AccountType.CARD).signedAmount)
        assertEquals(NOTHING, transfer(AccountType.CARD, AccountType.CASH).signedAmount)
        assertEquals(NOTHING, transfer(AccountType.CASH, AccountType.CARD).signedAmount)
        assertEquals(NOTHING, transfer(AccountType.CASH, AccountType.CASH).signedAmount)
    }

    @Test
    fun `money moved between two bank accounts moves the ledger by nothing`() {
        assertEquals(NOTHING, transfer(AccountType.BANK, AccountType.BANK).signedAmount)
    }

    @Test
    fun `a transfer with an account missing moves the ledger by nothing`() {
        assertEquals(NOTHING, transfer(null, AccountType.BANK).signedAmount)
        assertEquals(NOTHING, transfer(AccountType.BANK, null).signedAmount)
        assertEquals(NOTHING, transfer(null, null).signedAmount)
    }

    private fun transfer(
        from: AccountType?,
        into: AccountType?,
    ) = Transaction(
        id = 1,
        amount = SIXTY,
        occurredAt = Instant.EPOCH,
        zoneId = ZoneId.of("America/Toronto"),
        localDate = LocalDate.of(2026, 9, 14),
        merchantRaw = null,
        merchantId = null,
        merchantName = null,
        source = TransactionSource.MANUAL,
        status = TransactionStatus.CONFIRMED,
        cardLabel = null,
        cardLast4 = null,
        note = null,
        kind = TransactionKind.TRANSFER,
        accountType = from,
        toAccountType = into,
    )

    private companion object {
        val SIXTY = Money(6_000, Money.CAD)
        val NOTHING = Money(0, Money.CAD)
    }
}
