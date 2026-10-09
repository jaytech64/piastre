// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.core.designsystem

import io.github.jaytech64.piastre.core.model.Account
import io.github.jaytech64.piastre.core.model.AccountOrigin
import io.github.jaytech64.piastre.core.model.AccountType
import io.github.jaytech64.piastre.core.model.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `accountLabel` itself needs a composition to reach its strings, and this repo
 * has no UI test framework. What can be pinned here is the precondition it
 * exists for: the two label builders answer with an empty string for an account
 * that carries nothing, and a screen printing one of them directly shows a row
 * with no title.
 */
class AccountLabelTest {
    @Test
    fun `an account carrying no name, no issuer and no digits has no label to build`() {
        assertEquals("", accountMenuLabel(account()))
        assertEquals("", walletLabel(account()))
    }

    @Test
    fun `the cash account every database is created with is exactly that account`() {
        val cash = account(type = AccountType.CASH)

        assertTrue(cash.name == null && cash.issuerLabel == null && cash.last4 == null)
        assertEquals("", accountMenuLabel(cash))
    }

    @Test
    fun `a named account leads with the name, and carries its digits when it has them`() {
        assertEquals("Visa perso ••7373", accountMenuLabel(account(name = "Visa perso", last4 = "7373")))
        assertEquals("Chèques", accountMenuLabel(account(name = "Chèques")))
    }

    @Test
    fun `a card Wallet named but you have not is shown the way Wallet shows it`() {
        val fromWallet = account(issuerLabel = "Carte d'accès Desrives", last4 = "0000")

        assertEquals("Carte d'accès Desrives ••0000", accountMenuLabel(fromWallet))
    }

    private fun account(
        name: String? = null,
        type: AccountType = AccountType.BANK,
        issuerLabel: String? = null,
        last4: String? = null,
    ) = Account(
        id = 1,
        name = name,
        type = type,
        issuerLabel = issuerLabel,
        last4 = last4,
        origin = AccountOrigin.USER,
        transactionCount = 0,
        openingBalance = null,
        balance = Money(0, Money.CAD),
    )
}
