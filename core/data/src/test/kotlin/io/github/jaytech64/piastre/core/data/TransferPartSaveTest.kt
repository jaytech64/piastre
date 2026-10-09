// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.core.data

import io.github.jaytech64.piastre.core.database.AccountEntity
import io.github.jaytech64.piastre.core.database.PiastreDatabase
import io.github.jaytech64.piastre.core.model.AccountOrigin
import io.github.jaytech64.piastre.core.model.AccountType
import io.github.jaytech64.piastre.core.model.Money
import io.github.jaytech64.piastre.core.model.TransactionKind
import io.github.jaytech64.piastre.core.model.TransactionPart
import io.github.jaytech64.piastre.core.testing.inMemoryDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import javax.inject.Provider

/** A part of a split that goes into another account, as a save takes it or refuses it. */
@RunWith(RobolectricTestRunner::class)
class TransferPartSaveTest {
    private lateinit var database: PiastreDatabase
    private lateinit var repository: TransactionRepository
    private lateinit var editor: TransactionEditor

    @Before
    fun open() {
        database = inMemoryDatabase()
        val merchants = MerchantRepository(database.merchantDao())
        val categories = database.categoryRepository()
        repository =
            TransactionRepository(database, database.transactionDao(), merchants, categories, Provider { ZONE })
        editor = TransactionEditor(database, database.transactionDao(), merchants, categories, Clock.systemUTC())
    }

    @After
    fun close() = database.close()

    @Test
    fun `a part that goes into another account is saved with it, and that account gains its share`() =
        runTest {
            val chequing = account("Chèques", AccountType.CARD)
            val mortgage = account("Hypothèque", AccountType.BANK)

            val id = addSplit(part(12_000), into(mortgage, 6_742), accountId = chequing)

            val capital = repository.byId(id)?.parts?.last()
            assertEquals(mortgage, capital?.toAccountId)
            assertEquals("Hypothèque", capital?.toAccountName)
            assertEquals(AccountType.BANK, capital?.toAccountType)
            assertEquals(mapOf(chequing to -18_742L, mortgage to 6_742L), balances())
        }

    @Test
    fun `a part into the account the expense came from is refused and nothing is written`() =
        runTest {
            val chequing = account("Chèques", AccountType.CARD)

            val refused = runCatching { addSplit(part(12_000), into(chequing, 6_742), accountId = chequing) }

            assertTrue(refused.exceptionOrNull() is IllegalArgumentException)
            assertEquals(0, repository.confirmed().first().size)
        }

    @Test
    fun `an edit to income is refused while a part still goes into another account`() =
        runTest {
            val chequing = account("Chèques", AccountType.CARD)
            val mortgage = account("Hypothèque", AccountType.BANK)
            val parts = listOf(part(12_000), into(mortgage, 6_742))
            val id = addSplit(*parts.toTypedArray(), accountId = chequing)

            val refused =
                runCatching {
                    editor.edit(
                        id,
                        COSTCO,
                        TransactionKind.INCOME,
                        "Costco",
                        OCCURRED_AT,
                        note = null,
                        accountId = chequing,
                        toAccountId = null,
                        categoryId = null,
                        reach = null,
                        parts = parts,
                    )
                }

            assertTrue(refused.exceptionOrNull() is IllegalArgumentException)
            assertEquals(TransactionKind.EXPENSE, repository.byId(id)?.kind)
        }

    @Test
    fun `a lone part cannot be a transfer`() =
        runTest {
            val chequing = account("Chèques", AccountType.CARD)
            val mortgage = account("Hypothèque", AccountType.BANK)

            val refused = runCatching { addSplit(into(mortgage, 18_742), accountId = chequing) }

            assertTrue(refused.exceptionOrNull() is IllegalArgumentException)
        }

    private fun part(minor: Long) = TransactionPart(Money(minor, Money.CAD))

    private fun into(
        accountId: Long,
        minor: Long,
    ) = TransactionPart(Money(minor, Money.CAD), toAccountId = accountId)

    private suspend fun account(
        name: String,
        type: AccountType,
    ) = database.accountDao().insert(
        AccountEntity(name = name, type = type, issuerLabel = null, last4 = null, origin = AccountOrigin.USER),
    )

    private suspend fun balances(): Map<Long, Long> =
        database
            .accountDao()
            .allWithCounts(Money.CAD, Int.MAX_VALUE)
            .first()
            .associate { it.account.id to it.balanceMinor }

    private suspend fun addSplit(
        vararg parts: TransactionPart,
        accountId: Long,
    ): Long =
        repository.addManual(
            COSTCO,
            "Costco",
            OCCURRED_AT,
            note = null,
            kind = TransactionKind.EXPENSE,
            accountId = accountId,
            toAccountId = null,
            categoryId = null,
            reach = null,
            parts = parts.toList(),
        )

    private companion object {
        val ZONE: ZoneId = ZoneId.of("America/Toronto")
        val OCCURRED_AT: Instant = Instant.parse("2026-09-08T21:17:00Z")
        val COSTCO = Money(18_742, Money.CAD)
    }
}
