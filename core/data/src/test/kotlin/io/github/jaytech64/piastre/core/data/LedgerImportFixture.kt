// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.core.data

import io.github.jaytech64.piastre.core.database.DepensesDatabase
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
    protected lateinit var source: DepensesDatabase
    protected lateinit var target: DepensesDatabase

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

    protected fun DepensesDatabase.accounts() = AccountRepository(accountDao())

    protected fun DepensesDatabase.transactions() =
        TransactionRepository(
            database = this,
            transactions = transactionDao(),
            merchants = MerchantRepository(merchantDao()),
            categories = CategoryRepository(categoryDao(), merchantDao(), accountDao()),
            zoneId = Provider { ZONE },
        )

    protected fun DepensesDatabase.importer() =
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
            posting = posting(),
        )

    protected fun DepensesDatabase.posting() =
        RecurringPosting(
            this,
            recurringTransactionDao(),
            transactionDao(),
            LocalDateTicker(Clock.fixed(EXPORTED_AT, ZONE), Provider { ZONE }),
            Clock.fixed(EXPORTED_AT, ZONE),
            Provider { ZONE },
        )

    protected suspend fun DepensesDatabase.reading(file: String): ImportReading =
        importer().read(file.byteInputStream())

    protected suspend fun DepensesDatabase.importing(file: String): ImportOutcome {
        val reading = reading(file)
        assertTrue(reading.toString(), reading is ImportReading.Ready)
        return importer().apply(reading as ImportReading.Ready)
    }

    protected suspend fun DepensesDatabase.export(): String {
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
            Clock.fixed(EXPORTED_AT, ZoneOffset.UTC),
        ).write(bytes, ExportFormat.JSON, LABELS)
        return bytes.toString(Charsets.UTF_8.name())
    }

    protected suspend fun DepensesDatabase.enterSplit(
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

    protected suspend fun DepensesDatabase.balances() =
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
