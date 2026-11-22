// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.core.data

import io.github.jaytech64.piastre.core.database.AccountEntity
import io.github.jaytech64.piastre.core.database.CategoryEntity
import io.github.jaytech64.piastre.core.database.ExpenseTransactionEntity
import io.github.jaytech64.piastre.core.database.MerchantEntity
import io.github.jaytech64.piastre.core.database.PiastreDatabase
import io.github.jaytech64.piastre.core.database.TransactionPartEntity
import io.github.jaytech64.piastre.core.model.AccountOrigin
import io.github.jaytech64.piastre.core.model.AccountType
import io.github.jaytech64.piastre.core.model.Money
import io.github.jaytech64.piastre.core.model.PeriodUnit
import io.github.jaytech64.piastre.core.model.ReportPeriod
import io.github.jaytech64.piastre.core.model.TransactionKind
import io.github.jaytech64.piastre.core.model.TransactionSource
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

/**
 * The figures a merchant report draws, through the real DAO and a real
 * SQLite, held to what the report of the same days counts (ADR-0029).
 */
@RunWith(RobolectricTestRunner::class)
class MerchantSpendingTest {
    private lateinit var database: PiastreDatabase
    private lateinit var reports: ReportRepository

    @Before
    fun open() {
        database = inMemoryDatabase()
        reports = ReportRepository(database.reportDao())
    }

    @After
    fun close() = database.close()

    /**
     * A merchant report is read through a query of its own, so this is what
     * holds it to the report: the merchants and what went out with none add up
     * to the report's spending, total and count, whatever the rows are.
     */
    @Test
    fun `the merchants and what has none add up to what the report of the same days spent`() =
        runTest {
            val bakery = merchant("Boulangerie Test")
            val grocer = merchant("Épicerie Test")
            val savings = account(AccountType.BANK)
            val card = account(AccountType.CARD)
            record(amountMinor = 1_250, merchantId = bakery)
            record(amountMinor = 830, merchantId = bakery, key = "second")
            record(amountMinor = 6_400, merchantId = grocer, key = "split")
            split("split", 4_000L to category("Groceries"), 2_400L to category("Household"))
            record(amountMinor = 900)
            record(amountMinor = 20_000, kind = TransactionKind.TRANSFER, accountId = card, toAccountId = savings)
            record(amountMinor = 50_000, kind = TransactionKind.INCOME, merchantId = grocer, key = "pay")
            record(amountMinor = 700, merchantId = bakery, currency = "USD", key = "abroad")
            record(amountMinor = 3_300, merchantId = grocer, status = TransactionStatus.NEEDS_REVIEW, key = "unread")

            val merchants = reports.merchantSpending(NOVEMBER, TODAY).first()
            val report = reports.spending(NOVEMBER, TODAY).first()

            assertEquals(report.total.minor, merchants.sumOf { it.total.minor })
            assertEquals(report.count, merchants.sumOf { it.visits })
        }

    @Test
    fun `a split is one visit worth every part that went out`() =
        runTest {
            val pharmacy = merchant("Pharmacie Test")
            val cash = account(AccountType.CASH)
            record(amountMinor = 6_000, merchantId = pharmacy, accountId = account(AccountType.CARD), key = "split")
            split("split", 2_000L to category("Health"), 1_000L to category("Household"), intoAccount = 3_000L to cash)

            val merchants = reports.merchantSpending(NOVEMBER, TODAY).first()

            // The cash back moved money between your own accounts, and left nothing at the till.
            assertEquals(
                listOf(MerchantSpending(pharmacy, "Pharmacie Test", Money(3_000, Money.CAD), visits = 1)),
                merchants,
            )
        }

    @Test
    fun `a merchant counts what went out there, and no income from it`() =
        runTest {
            val store = merchant("Magasin Test")
            record(amountMinor = 4_500, merchantId = store)
            record(amountMinor = 1_500, merchantId = store, kind = TransactionKind.INCOME, key = "refund")

            val merchants = reports.merchantSpending(NOVEMBER, TODAY).first()

            assertEquals(listOf(MerchantSpending(store, "Magasin Test", Money(4_500, Money.CAD), 1)), merchants)
        }

    @Test
    fun `a purchase dated after today counts at no merchant until its day`() =
        runTest {
            val store = merchant("Magasin Test")
            record(amountMinor = 4_500, merchantId = store, on = TODAY.plusDays(3))

            assertEquals(emptyList<MerchantSpending>(), reports.merchantSpending(NOVEMBER, TODAY).first())
            assertEquals(
                1,
                reports
                    .merchantSpending(NOVEMBER, TODAY.plusDays(3))
                    .first()
                    .single()
                    .visits,
            )
        }

    @Test
    fun `a merchant renamed is counted under its name today`() =
        runTest {
            val store = merchant("Magasin Test")
            record(amountMinor = 4_500, merchantId = store)

            database.merchantDao().renameMerchant(store, "Magasin Renommé")

            assertEquals(
                "Magasin Renommé",
                reports
                    .merchantSpending(NOVEMBER, TODAY)
                    .first()
                    .single()
                    .name,
            )
        }

    private suspend fun record(
        amountMinor: Long,
        merchantId: Long? = null,
        on: LocalDate = TODAY,
        kind: TransactionKind = TransactionKind.EXPENSE,
        status: TransactionStatus = TransactionStatus.CONFIRMED,
        currency: String = Money.CAD,
        key: String = "tag-$amountMinor-$kind",
        accountId: Long? = null,
        toAccountId: Long? = null,
    ) {
        database.transactionDao().insert(
            ExpenseTransactionEntity(
                rawEventId = null,
                amountMinor = amountMinor,
                currency = currency,
                occurredAtUtcMillis = on.toEpochDay() * MILLIS_IN_DAY,
                zoneId = "America/Toronto",
                localDateEpochDay = on.toEpochDay().toInt(),
                merchantId = merchantId,
                merchantRaw = null,
                categoryId = null,
                source = TransactionSource.MANUAL,
                status = status,
                reviewReason = null,
                reviewedAtUtcMillis = null,
                confidence = 1f,
                parserId = null,
                parserVersion = null,
                sourcePackage = "manual",
                sourceKey = key,
                cardLabel = null,
                cardLast4 = null,
                note = null,
                kind = kind,
                accountId = accountId,
                toAccountId = toAccountId,
            ),
        )
    }

    /** Divides the row among categories, with a last part of [intoAccount]'s amount going into that account. */
    private suspend fun split(
        key: String,
        vararg parts: Pair<Long, Long?>,
        intoAccount: Pair<Long, Long>? = null,
    ) {
        val id = checkNotNull(database.transactionDao().bySourceKey("manual", key)).id
        val spent = parts.map { (amountMinor, categoryId) -> part(amountMinor, categoryId) }
        val movedOn = intoAccount?.let { (amountMinor, accountId) -> part(amountMinor, null, accountId) }
        database.transactionPartDao().replace(id, spent + listOfNotNull(movedOn))
    }

    private fun part(
        amountMinor: Long,
        categoryId: Long?,
        toAccountId: Long? = null,
    ) = TransactionPartEntity(
        transactionId = 0,
        position = 0,
        amountMinor = amountMinor,
        categoryId = categoryId,
        note = null,
        toAccountId = toAccountId,
    )

    private suspend fun merchant(name: String): Long =
        database.merchantDao().insert(MerchantEntity(displayName = name, normalisedName = name.uppercase()))

    private suspend fun category(name: String): Long =
        database.categoryDao().insert(CategoryEntity(name = name, normalisedName = name.uppercase(), groupId = null))

    private suspend fun account(type: AccountType): Long =
        database.accountDao().insert(
            AccountEntity(name = null, type = type, issuerLabel = null, last4 = null, origin = AccountOrigin.USER),
        )

    private companion object {
        val TODAY: LocalDate = LocalDate.of(2026, 11, 16)
        val NOVEMBER: ReportPeriod = ReportPeriod.containing(PeriodUnit.MONTH, TODAY)
        const val MILLIS_IN_DAY = 86_400_000L
    }
}
