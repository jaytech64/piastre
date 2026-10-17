// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.core.data

import io.github.jaytech64.piastre.core.database.PiastreDatabase
import io.github.jaytech64.piastre.core.model.AccountType
import io.github.jaytech64.piastre.core.model.Money
import io.github.jaytech64.piastre.core.model.Recurrence
import io.github.jaytech64.piastre.core.model.Rhythm
import io.github.jaytech64.piastre.core.model.TemplateContent
import io.github.jaytech64.piastre.core.model.TransactionKind
import io.github.jaytech64.piastre.core.model.TransactionPart
import io.github.jaytech64.piastre.core.testing.inMemoryDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import javax.inject.Provider

/**
 * Two ledgers, one to export from and one to import into, and the ways a test
 * carries a file from the first to the second.
 */
abstract class LedgerImportFixture {
    protected lateinit var source: PiastreDatabase
    protected lateinit var target: PiastreDatabase

    @Before
    fun open() {
        source = inMemoryDatabase()
        target = inMemoryDatabase()
    }

    @After
    fun close() {
        source.close()
        target.close()
    }

    protected fun PiastreDatabase.accounts() = AccountRepository(accountDao())

    protected fun PiastreDatabase.transactions() =
        TransactionRepository(
            database = this,
            transactions = transactionDao(),
            merchants = MerchantRepository(merchantDao()),
            categories = CategoryRepository(categoryDao(), merchantDao(), accountDao()),
            zoneId = Provider { ZONE },
        )

    protected fun PiastreDatabase.budgets() =
        BudgetRepository(budgetDao(), ReportRepository(reportDao()), categoryRepository(), categoryGroupRepository())

    protected fun PiastreDatabase.importer() =
        LedgerImport(
            database = this,
            transactions = transactionDao(),
            merchants = MerchantRepository(merchantDao()),
            merchantRows = merchantDao(),
            accounts = accountDao(),
            categories = categoryRepository(),
            categoryGroups = categoryGroupRepository(),
            recurring = recurringTransactionDao(),
            templates = transactionTemplateDao(),
            budgets = budgetDao(),
            posting = posting(),
        )

    protected fun PiastreDatabase.posting() =
        RecurringPosting(
            this,
            recurringTransactionDao(),
            transactionDao(),
            LocalDateTicker(Clock.fixed(EXPORTED_AT, ZONE), Provider { ZONE }),
            Clock.fixed(EXPORTED_AT, ZONE),
            Provider { ZONE },
        )

    protected suspend fun PiastreDatabase.reading(file: String): ImportReading = importer().read(file.byteInputStream())

    protected suspend fun PiastreDatabase.importing(file: String): ImportOutcome {
        val reading = reading(file)
        assertTrue(reading.toString(), reading is ImportReading.Ready)
        return importer().apply(reading as ImportReading.Ready)
    }

    protected suspend fun PiastreDatabase.export(): String {
        val bytes = ByteArrayOutputStream()
        LedgerExport(
            transactionDao(),
            merchantDao(),
            accountDao(),
            categoryDao(),
            categoryGroupDao(),
            recurringTransactionDao(),
            transactionTemplateDao(),
            transactionPartDao(),
            budgetDao(),
            Clock.fixed(EXPORTED_AT, ZoneOffset.UTC),
        ).write(bytes, ExportFormat.JSON, LABELS)
        return bytes.toString(Charsets.UTF_8.name())
    }

    protected suspend fun PiastreDatabase.enterSplit(
        vararg parts: TransactionPart,
        accountId: Long? = null,
    ) = transactions().addManual(
        amount = cad(187_42),
        merchantName = "Costco",
        occurredAt = OCCURRED_AT,
        note = null,
        kind = TransactionKind.EXPENSE,
        accountId = accountId,
        toAccountId = null,
        categoryId = null,
        reach = null,
        parts = parts.toList(),
    )

    protected suspend fun PiastreDatabase.balances() =
        accounts()
            .all(
                LocalDate.of(2026, 12, 31),
            ).first()
            .map { listOf(it.name, it.type, it.openingBalance, it.balance) }

    protected fun cad(minor: Long) = Money(minor, Money.CAD)

    protected companion object {
        val ZONE: ZoneId = ZoneId.of("America/Toronto")
        val EXPORTED_AT: Instant = Instant.parse("2026-09-12T20:00:00Z")
        val OCCURRED_AT: Instant = Instant.parse("2026-09-08T21:17:00Z")

        val LABELS =
            CsvLabels(
                date = "date",
                time = "heure",
                merchant = "marchand",
                amount = "montant",
                currency = "devise",
                source = "source",
                status = "statut",
                card = "carte",
                note = "note",
                type = "type",
                account = "compte",
                toAccount = "compte de destination",
                category = "catégorie",
                categoryGroup = "groupe",
            )
    }
}
