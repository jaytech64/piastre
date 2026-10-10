// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.core.data

import io.github.jaytech64.piastre.core.database.PiastreDatabase
import io.github.jaytech64.piastre.core.model.Money
import io.github.jaytech64.piastre.core.model.TransactionKind
import io.github.jaytech64.piastre.core.model.TransactionStatus
import io.github.jaytech64.piastre.core.testing.inMemoryDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Provider

/** The order the form offers merchants in, read back from a real database. */
@RunWith(RobolectricTestRunner::class)
class MerchantRepositoryTest {
    private lateinit var database: PiastreDatabase
    private lateinit var merchants: MerchantRepository
    private lateinit var transactions: TransactionRepository

    @Before
    fun open() {
        database = inMemoryDatabase()
        merchants = MerchantRepository(database.merchantDao())
        transactions =
            TransactionRepository(
                database = database,
                transactions = database.transactionDao(),
                merchants = merchants,
                categories =
                    CategoryRepository(
                        database.categoryDao(),
                        database.merchantDao(),
                        database.accountDao(),
                    ),
                zoneId = Provider { ZONE },
            )
    }

    @After
    fun close() = database.close()

    @Test
    fun `the place you go most often is offered first`() =
        runTest {
            spend("Archambault", daysAgo = 3)
            repeat(3) { spend("IGA Extra", daysAgo = it + 1) }
            repeat(2) { spend("Tim Hortons", daysAgo = it + 1) }

            assertEquals(listOf("IGA Extra", "Tim Hortons", "Archambault"), offered(TransactionKind.EXPENSE))
        }

    @Test
    fun `a store you stopped going to gives up its place after ninety days`() =
        runTest {
            repeat(5) { spend("Marché de l'ancien quartier", daysAgo = 120 + it) }
            spend("Marché St-Joseph", daysAgo = 2)

            assertEquals(
                listOf("Marché St-Joseph", "Marché de l'ancien quartier"),
                offered(TransactionKind.EXPENSE),
            )
        }

    @Test
    fun `of two places used as often, the more recent comes first`() =
        runTest {
            spend("Boulangerie Guillaume", daysAgo = 9)
            spend("Pharmaprix", daysAgo = 1)

            assertEquals(listOf("Pharmaprix", "Boulangerie Guillaume"), offered(TransactionKind.EXPENSE))
        }

    @Test
    fun `an income leads with who pays you, and an expense with where you spend`() =
        runTest {
            repeat(4) { spend("Couche-Tard", daysAgo = it + 1) }
            spend("Cégep de Sherbrooke", daysAgo = 14, kind = TransactionKind.INCOME)

            assertEquals(listOf("Cégep de Sherbrooke", "Couche-Tard"), offered(TransactionKind.INCOME))
            assertEquals(listOf("Couche-Tard", "Cégep de Sherbrooke"), offered(TransactionKind.EXPENSE))
        }

    @Test
    fun `a purchase still waiting for review does not move its merchant up`() =
        runTest {
            spend("SAQ", daysAgo = 5)
            spend("Metro", daysAgo = 6)
            val metro =
                database
                    .transactionDao()
                    .allWithMerchant()
                    .first { it.merchantName == "Metro" }
                    .transaction
            repeat(2) {
                database.transactionDao().insert(
                    metro.copy(id = 0, sourceKey = "review-$it", status = TransactionStatus.NEEDS_REVIEW),
                )
            }

            assertEquals(listOf("SAQ", "Metro"), offered(TransactionKind.EXPENSE))
        }

    @Test
    fun `a merchant you have never used is still offered, after the ones you have`() =
        runTest {
            merchants.named("Zone Sports")
            merchants.named("Café Olimpico")
            spend("Tim Hortons", daysAgo = 200)

            assertEquals(listOf("Tim Hortons", "Café Olimpico", "Zone Sports"), offered(TransactionKind.EXPENSE))
        }

    @Test
    fun `every merchant is listed under the name it has now`() =
        runTest {
            val metro = merchants.named("Metro")
            merchants.named("Zone Sports")

            database.merchantDao().renameMerchant(checkNotNull(metro), "Metro Plus")

            assertEquals(listOf("Metro Plus", "Zone Sports"), merchants.all().first().map { it.displayName })
        }

    private suspend fun spend(
        merchant: String,
        daysAgo: Int,
        kind: TransactionKind = TransactionKind.EXPENSE,
    ) {
        transactions.addManual(
            amount = Money(1_000, Money.CAD),
            merchantName = merchant,
            occurredAt =
                TODAY
                    .minusDays(daysAgo.toLong())
                    .atTime(NOON)
                    .atZone(ZONE)
                    .toInstant(),
            note = null,
            kind = kind,
            accountId = null,
            toAccountId = null,
            categoryId = null,
            reach = null,
        )
    }

    private suspend fun offered(kind: TransactionKind): List<String> =
        merchants.byUse(kind, TODAY).first().map { it.displayName }

    private companion object {
        val ZONE: ZoneId = ZoneId.of("America/Toronto")
        val TODAY: LocalDate = LocalDate.of(2026, 9, 24)
        val NOON: LocalTime = LocalTime.NOON
    }
}
