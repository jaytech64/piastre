// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.data

import io.github.jaytech64.depenses.core.database.AccountDao
import io.github.jaytech64.depenses.core.database.AccountEntity
import io.github.jaytech64.depenses.core.database.AccountWithCount
import io.github.jaytech64.depenses.core.model.Account
import io.github.jaytech64.depenses.core.model.AccountOrigin
import io.github.jaytech64.depenses.core.model.AccountType
import io.github.jaytech64.depenses.core.model.Money
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The accounts money sits in: the cards Wallet names in its notifications, found
 * or created as purchases arrive, the cash account every database is created
 * with, and whatever else you add.
 */
@Singleton
class AccountRepository
    @Inject
    constructor(
        private val accounts: AccountDao,
    ) {
        /**
         * The card a Wallet notification names, created the first time it is seen.
         *
         * Null when the notification carried no digits: without them one card
         * cannot be told from another, and guessing would merge two.
         */
        suspend fun resolve(
            issuerLabel: String?,
            last4: String?,
        ): Long? {
            if (last4.isNullOrBlank()) return null
            accounts.walletAccount(issuerLabel, last4)?.let { return it.id }
            accounts.insertLast(
                AccountEntity(
                    name = null,
                    type = AccountType.CARD,
                    issuerLabel = issuerLabel,
                    last4 = last4,
                    origin = AccountOrigin.WALLET,
                ),
            )
            return accounts.walletAccount(issuerLabel, last4)?.id
        }

        fun all(): Flow<List<Account>> =
            accounts.allWithCounts(Money.CAD).map { rows -> rows.map(AccountWithCount::toDomain) }

        /** A blank name clears it, and a card goes back to showing its digits. */
        suspend fun rename(
            id: Long,
            name: String?,
        ) = accounts.rename(id, name.cleaned())

        suspend fun add(
            name: String?,
            type: AccountType,
            last4: String?,
            openingBalance: Money? = null,
        ): Long =
            accounts.insertLast(
                AccountEntity(
                    name = name.cleaned(),
                    type = type,
                    issuerLabel = null,
                    last4 = last4.forType(type),
                    origin = AccountOrigin.USER,
                    openingBalanceMinor = openingBalance?.minor,
                ),
            )

        /** Any account takes one, Wallet's included: the bank knows its digits, not what it held. */
        suspend fun setOpeningBalance(
            id: Long,
            openingBalance: Money?,
        ) = accounts.setOpeningBalance(id, openingBalance?.minor)

        /** Changes an account you added. One Wallet named only takes [rename]. */
        suspend fun updateAdded(
            id: Long,
            name: String?,
            type: AccountType,
            last4: String?,
        ) = accounts.updateAddedAccount(id, name.cleaned(), last4.forType(type), type)

        /** Puts the account at [toIndex] in every list of accounts, counting from the top. */
        suspend fun move(
            id: Long,
            toIndex: Int,
        ) = accounts.move(id, toIndex)

        /** False when a transaction still uses the account, either side of a transfer. */
        suspend fun remove(id: Long): Boolean = accounts.deleteIfUnused(id) == 1
    }

private fun String?.cleaned(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

/** Only a card has last four digits; cash and a bank account would wear them as decoration. */
private fun String?.forType(type: AccountType): String? = cleaned()?.takeIf { type == AccountType.CARD }

private fun AccountWithCount.toDomain(): Account =
    Account(
        id = account.id,
        name = account.name,
        type = account.type,
        issuerLabel = account.issuerLabel,
        last4 = account.last4,
        origin = account.origin,
        transactionCount = transactionCount,
        openingBalance = account.openingBalanceMinor?.let { Money(it, Money.CAD) },
        balance = Money(balanceMinor, Money.CAD),
        categoryId = account.categoryId,
    )
