// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.data

import io.github.jaytech64.depenses.core.database.DepensesDatabase
import io.github.jaytech64.depenses.core.model.AccountType
import io.github.jaytech64.depenses.core.model.Money
import io.github.jaytech64.depenses.core.model.TransactionKind
import io.github.jaytech64.depenses.core.testing.inMemoryDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import javax.inject.Provider

/**
 * An export read back, with a real database on each side: the file is the only
 * thing that crosses between them, exactly as it crosses between two phones.
 */
@RunWith(RobolectricTestRunner::class)
class LedgerImportTest {
    private lateinit var source: DepensesDatabase
    private lateinit var target: DepensesDatabase

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

    @Test
    fun `an export read into an empty ledger comes back whole, balances included`() =
        runTest {
            val bank = source.accounts().add("Débit", AccountType.BANK, last4 = null, openingBalance = cad(1_850_00))
            val card = source.accounts().add("Visa", AccountType.CARD, last4 = "7373", openingBalance = cad(-640_25))
            source.enter(8_82, TransactionKind.EXPENSE, accountId = card, merchant = "Métro", note = "épicerie")
            source.enter(1_500_00, TransactionKind.INCOME, accountId = bank, merchant = "Desrives")
            source.enter(412_50, TransactionKind.TRANSFER, accountId = bank, toAccountId = card)

            val outcome = target.importing(source.export())

            assertEquals(3, (outcome as ImportOutcome.Imported).summary.newTransactions)
            assertEquals(source.ledger(), target.ledger())
            assertEquals(source.balances(), target.balances())
        }

    @Test
    fun `importing the same file twice adds nothing the second time`() =
        runTest {
            val file = document()
            target.importing(file)

            val again = target.reading(file) as ImportReading.Ready
            target.importer().apply(again)

            assertEquals(0, again.summary.newTransactions)
            assertEquals(1, again.summary.alreadyHere)
            assertEquals(1, target.transactionDao().count().first())
        }

    @Test
    fun `an account is found by its name whatever its case, but not across accents`() =
        runTest {
            target.accounts().add("Débit", AccountType.BANK, last4 = null)

            val folded = target.reading(document(accountName = "débit")) as ImportReading.Ready
            val unaccented = target.reading(document(accountName = "Debit")) as ImportReading.Ready

            assertEquals(1, folded.summary.accountsFound)
            assertEquals(0, folded.summary.accountsCreated)
            assertEquals(1, unaccented.summary.accountsCreated)
        }

    @Test
    fun `two accounts answering to one name refuse the whole file, and nothing is written`() =
        runTest {
            target.accounts().add("Visa", AccountType.CARD, last4 = "7373")
            target.accounts().add("VISA", AccountType.CARD, last4 = "8484")

            val reading = target.reading(document(accountName = "Visa"))

            assertEquals(ImportReading.Refused(ImportProblem.AmbiguousAccount("Visa")), reading)
            assertEquals(0, target.transactionDao().count().first())
        }

    @Test
    fun `a file from another version, or no export at all, is refused rather than guessed at`() =
        runTest {
            assertEquals(refused(ImportProblem.UnsupportedVersion(1)), target.reading(document(version = 1)))
            assertEquals(refused(ImportProblem.NotAnExport), target.reading("marchand;montant"))
            assertEquals(refused(ImportProblem.NotAnExport), target.reading("""{"hello": "world"}"""))
        }

    @Test
    fun `a transaction on an account the file does not list refuses the file before anything is written`() =
        runTest {
            val reading = target.reading(document(transactionAccountId = 7))

            assertTrue(reading.toString(), (reading as ImportReading.Refused).problem is ImportProblem.Inconsistent)
            assertTrue(
                target
                    .accounts()
                    .all()
                    .first()
                    .isEmpty(),
            )
        }

    /** Decided in review: a balance you typed is never overwritten by a file, and the summary names the difference. */
    @Test
    fun `an opening balance you set is kept, and one nobody gave is filled in`() =
        runTest {
            source.accounts().add("Visa", AccountType.CARD, last4 = null, openingBalance = cad(-640_25))
            source.accounts().add("Débit", AccountType.BANK, last4 = null, openingBalance = cad(1_850_00))
            target.accounts().add("Visa", AccountType.CARD, last4 = null, openingBalance = cad(-1_000_00))
            target.accounts().add("Débit", AccountType.BANK, last4 = null)

            val reading = target.reading(source.export()) as ImportReading.Ready
            target.importer().apply(reading)

            assertEquals(1, reading.summary.openingBalancesSet)
            assertEquals(
                listOf(KeptOpeningBalance("Visa", inFile = cad(-640_25), kept = cad(-1_000_00))),
                reading.summary.openingBalancesKept,
            )
            val opening =
                target
                    .accounts()
                    .all()
                    .first()
                    .associate { it.name to it.openingBalance }
            assertEquals(cad(-1_000_00), opening["Visa"])
            assertEquals(cad(1_850_00), opening["Débit"])
        }

    @Test
    fun `a Wallet card finds its card by issuer and digits, whatever either side named it`() =
        runTest {
            val theirs = source.accounts().resolve("Carte d'accès Desrives", "0000")!!
            source.accounts().rename(theirs, "Débit")
            source.enter(6_25, TransactionKind.EXPENSE, accountId = theirs, merchant = "Café Olimpico")
            val mine = target.accounts().resolve("Carte d'accès Desrives", "0000")!!
            target.accounts().rename(mine, "Chèques")

            target.importing(source.export())

            val account =
                target
                    .accounts()
                    .all()
                    .first()
                    .single()
            assertEquals("Chèques", account.name)
            assertEquals(1, account.transactionCount)
        }

    @Test
    fun `a merchant already here is joined rather than duplicated, and keeps its name`() =
        runTest {
            source.enter(8_82, TransactionKind.EXPENSE, merchant = "METRO")
            target.enter(6_85, TransactionKind.EXPENSE, merchant = "Metro")

            val reading = target.reading(source.export()) as ImportReading.Ready
            target.importer().apply(reading)

            assertEquals(0, reading.summary.newMerchants)
            assertEquals(listOf("Metro"), target.merchantDao().allOnce().map { it.displayName })
        }

    private fun DepensesDatabase.accounts() = AccountRepository(accountDao())

    private fun DepensesDatabase.transactions() =
        TransactionRepository(
            transactions = transactionDao(),
            merchants = MerchantRepository(merchantDao()),
            zoneId = Provider { ZONE },
        )

    private fun DepensesDatabase.importer() =
        LedgerImport(
            database = this,
            transactions = transactionDao(),
            merchants = MerchantRepository(merchantDao()),
            merchantRows = merchantDao(),
            accounts = accountDao(),
        )

    private suspend fun DepensesDatabase.reading(file: String): ImportReading = importer().read(file.byteInputStream())

    private suspend fun DepensesDatabase.importing(file: String): ImportOutcome {
        val reading = reading(file)
        assertTrue(reading.toString(), reading is ImportReading.Ready)
        return importer().apply(reading as ImportReading.Ready)
    }

    private suspend fun DepensesDatabase.export(): String {
        val bytes = ByteArrayOutputStream()
        LedgerExport(transactionDao(), merchantDao(), accountDao(), Clock.fixed(EXPORTED_AT, ZoneOffset.UTC))
            .write(bytes, ExportFormat.JSON, LABELS)
        return bytes.toString(Charsets.UTF_8.name())
    }

    private suspend fun DepensesDatabase.enter(
        minor: Long,
        kind: TransactionKind,
        accountId: Long? = null,
        toAccountId: Long? = null,
        merchant: String? = null,
        note: String? = null,
    ) = transactions().addManual(
        amount = cad(minor),
        merchantName = merchant,
        occurredAt = OCCURRED_AT,
        note = note,
        kind = kind,
        accountId = accountId,
        toAccountId = toAccountId,
    )

    /** Everything a person sees of a transaction; ids differ between two databases and are left out. */
    private suspend fun DepensesDatabase.ledger() =
        transactions()
            .confirmed()
            .first()
            .map {
                listOf(
                    it.amount,
                    it.occurredAt,
                    it.zoneId,
                    it.merchantName,
                    it.kind,
                    it.accountName,
                    it.toAccountName,
                    it.note,
                )
            }.sortedBy { it.toString() }

    private suspend fun DepensesDatabase.balances() =
        accounts().all().first().map { listOf(it.name, it.type, it.openingBalance, it.balance) }

    private fun refused(problem: ImportProblem) = ImportReading.Refused(problem)

    private fun cad(minor: Long) = Money(minor, Money.CAD)

    /** Written without `openingBalanceMinor`, as a version-2 file from before opening balances was. */
    private fun document(
        version: Int = 2,
        accountName: String = "Visa",
        transactionAccountId: Long = 1,
    ) = """
        {
          "version": $version,
          "exportedAtUtc": "2026-09-12T20:00:00Z",
          "merchants": [],
          "aliases": [],
          "accounts": [
            {"id": 1, "name": "$accountName", "type": "CARD", "issuerLabel": null, "last4": null, "origin": "USER"}
          ],
          "transactions": [
            {
              "id": 1, "amountMinor": 450, "currency": "CAD", "occurredAtUtcMillis": 1788740166564,
              "zoneId": "America/Toronto", "merchantId": null, "merchantName": null, "merchantRaw": "Dépanneur du coin",
              "source": "MANUAL", "status": "CONFIRMED", "confidence": 1.0, "parserId": null, "parserVersion": null,
              "sourcePackage": "org.totschnig.myexpenses", "sourceKey": "5d1e3c2a-7b40-4f6e-9a81-0c2d4e6f8a10",
              "cardLabel": null, "cardLast4": null, "note": null, "kind": "EXPENSE",
              "accountId": $transactionAccountId, "accountName": null, "toAccountId": null, "toAccountName": null
            }
          ]
        }
        """.trimIndent()

    private companion object {
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
            )
    }
}
