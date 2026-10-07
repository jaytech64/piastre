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

    @Test
    fun `a split with a share moved on is worth what the expense and the transfer are, entered apart`() {
        val kinds: List<AccountType?> = AccountType.entries + null
        for (from in kinds) {
            for (into in kinds) {
                val apart =
                    row(TransactionKind.EXPENSE, FORTY, from, into = null).movements +
                        row(TransactionKind.TRANSFER, SIXTY, from, into).movements
                val split = row(TransactionKind.EXPENSE, HUNDRED, from, into = null, parts = movedOn(into))

                assertEquals(apart, split.movements, "from $from into $into")
            }
        }
    }

    @Test
    fun `the capital of a mortgage payment has left like its interest`() {
        val payment = row(TransactionKind.EXPENSE, HUNDRED, AccountType.CARD, null, movedOn(AccountType.BANK))

        assertEquals(-HUNDRED, payment.signedAmount)
    }

    @Test
    fun `cash back at the till is not spent`() {
        val purchase = row(TransactionKind.EXPENSE, HUNDRED, AccountType.CARD, null, movedOn(AccountType.CASH))

        assertEquals(-FORTY, purchase.signedAmount)
    }

    @Test
    fun `a split with no share moved on is worth its whole amount`() {
        val parts = listOf(TransactionPart(FORTY), TransactionPart(SIXTY))

        assertEquals(-HUNDRED, row(TransactionKind.EXPENSE, HUNDRED, AccountType.CARD, null, parts).signedAmount)
        assertEquals(HUNDRED, row(TransactionKind.INCOME, HUNDRED, AccountType.CARD, null, parts).signedAmount)
        assertEquals(
            -HUNDRED,
            row(TransactionKind.TRANSFER, HUNDRED, AccountType.CARD, AccountType.BANK, parts).signedAmount,
        )
    }

    /** Forty spent, and sixty moved on into an account of the kind given. */
    private fun movedOn(into: AccountType?) =
        listOf(TransactionPart(FORTY), TransactionPart(SIXTY, toAccountId = 2, toAccountType = into))

    private fun transfer(
        from: AccountType?,
        into: AccountType?,
    ) = row(TransactionKind.TRANSFER, SIXTY, from, into)

    private fun row(
        kind: TransactionKind,
        amount: Money,
        from: AccountType?,
        into: AccountType?,
        parts: List<TransactionPart> = emptyList(),
    ) = Transaction(
        id = 1,
        amount = amount,
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
        kind = kind,
        accountType = from,
        toAccountType = into,
        parts = parts,
    )

    private companion object {
        val FORTY = Money(4_000, Money.CAD)
        val HUNDRED = Money(10_000, Money.CAD)
        val SIXTY = Money(6_000, Money.CAD)
        val NOTHING = Money(0, Money.CAD)
    }
}
