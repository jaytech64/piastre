// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.data

import io.github.jaytech64.depenses.core.database.AccountEntity
import io.github.jaytech64.depenses.core.database.DepensesDatabase
import io.github.jaytech64.depenses.core.database.ExpenseTransactionEntity
import io.github.jaytech64.depenses.core.database.MerchantAliasEntity
import io.github.jaytech64.depenses.core.database.MerchantEntity
import io.github.jaytech64.depenses.core.database.RawNotificationEventEntity
import io.github.jaytech64.depenses.core.model.AccountOrigin
import io.github.jaytech64.depenses.core.model.AccountType
import io.github.jaytech64.depenses.core.model.AliasOrigin
import io.github.jaytech64.depenses.core.model.MatchType
import io.github.jaytech64.depenses.core.model.ParseState
import io.github.jaytech64.depenses.core.model.TransactionKind
import io.github.jaytech64.depenses.core.model.TransactionSource
import io.github.jaytech64.depenses.core.model.TransactionStatus
import io.github.jaytech64.depenses.core.testing.inMemoryDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * The export is the one artifact that leaves the app, so it is checked as bytes
 * rather than as objects: the BOM, the delimiter and the decimal comma are the
 * whole reason the CSV format was chosen, and none of them show up in a model.
 */
@RunWith(RobolectricTestRunner::class)
class LedgerExportTest {
    private lateinit var database: DepensesDatabase
    private lateinit var export: LedgerExport

    @Before
    fun setUp() {
        database = inMemoryDatabase()
        export =
            LedgerExport(
                transactions = database.transactionDao(),
                merchants = database.merchantDao(),
                accounts = database.accountDao(),
                clock = Clock.fixed(EXPORTED_AT, ZoneOffset.UTC),
            )
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `the csv opens as french - byte-order mark, semicolons, decimal comma`() =
        runTest {
            store(merchantName = "MARCHE ST DAVID", amountMinor = 1_609)

            val csv = write(ExportFormat.CSV)

            assertTrue("missing UTF-8 BOM", csv.startsWith(BOM))
            assertEquals(
                "date;heure;marchand;montant;devise;source;statut;carte;note;type;compte;compte de destination",
                csv.lines()[0].removePrefix(BOM),
            )
            assertTrue(csv, "MARCHE ST DAVID;16,09;CAD" in csv)
        }

    @Test
    fun `the csv says which way the money went and between which accounts`() =
        runTest {
            val card = storeAccount()
            val cash = storeAccount(name = null, type = AccountType.CASH, issuerLabel = null, last4 = null)
            store(merchantName = "MARCHE ST DAVID", amountMinor = 1_609, accountId = card)
            store(
                merchantName = "ACME INC",
                amountMinor = 150_000,
                kind = TransactionKind.INCOME,
                sourceKey = "income-1",
            )
            store(
                merchantName = "VISA",
                amountMinor = 45_000,
                kind = TransactionKind.TRANSFER,
                accountId = cash,
                toAccountId = card,
                sourceKey = "transfer-1",
            )

            val rows = write(ExportFormat.CSV).lines()

            assertTrue(rows.toString(), rows.any { it.endsWith(";EXPENSE;Débit Desrives;") })
            assertTrue(rows.toString(), rows.any { it.endsWith(";INCOME;;") })
            assertTrue(rows.toString(), rows.any { it.endsWith(";TRANSFER;CASH;Débit Desrives") })
        }

    /** The names you give your accounts are data about you, so they leave with the rest. */
    @Test
    fun `the json carries your accounts and the account of each transaction`() =
        runTest {
            store(merchantName = "MARCHE ST DAVID", amountMinor = 1_609, accountId = storeAccount())

            val json = write(ExportFormat.JSON)

            assertTrue(json, "\"version\": 2" in json)
            assertTrue(json, "\"name\": \"Débit Desrives\"" in json)
            assertTrue(json, "\"type\": \"CARD\"" in json)
            assertTrue(json, "\"issuerLabel\": \"Carte d'accès Desrives\"" in json)
            assertTrue(json, "\"kind\": \"EXPENSE\"" in json)
            assertTrue(json, "\"accountName\": \"Débit Desrives\"" in json)
        }

    @Test
    fun `a merchant carrying the delimiter cannot break the row`() =
        runTest {
            store(merchantName = "CAFE; BAR \"LE COIN\"", amountMinor = 500)

            val fields = write(ExportFormat.CSV).lines()[1]

            assertTrue(fields, "\"CAFE; BAR \"\"LE COIN\"\"\"" in fields)
        }

    @Test
    fun `an amount under a dollar keeps both its digits`() =
        runTest {
            store(merchantName = "THE MATCHA", amountMinor = 5)

            val csv = write(ExportFormat.CSV)

            assertTrue(csv, ";0,05;" in csv)
        }

    @Test
    fun `a refund keeps its sign`() =
        runTest {
            store(merchantName = "THE MATCHA", amountMinor = -1_250)

            val csv = write(ExportFormat.CSV)

            assertTrue(csv, ";-12,50;" in csv)
        }

    @Test
    fun `accents survive the round trip through both formats`() =
        runTest {
            store(merchantName = "MARCHÉ DU FAUBOURG — CAFÉ ÉTÉ", amountMinor = 1_609)

            val csv = write(ExportFormat.CSV)
            val json = write(ExportFormat.JSON)

            assertTrue(csv, "MARCHÉ DU FAUBOURG — CAFÉ ÉTÉ" in csv)
            assertTrue(json, "MARCHÉ DU FAUBOURG — CAFÉ ÉTÉ" in json)
        }

    @Test
    fun `the json carries the identity a re-import would have to match on`() =
        runTest {
            store(merchantName = "MARCHE ST DAVID", amountMinor = 1_609)

            val json = write(ExportFormat.JSON)

            assertTrue(json, "\"version\": 2" in json)
            assertTrue(json, "\"sourceKey\": \"tag-1\"" in json)
            assertTrue(json, "\"sourcePackage\": \"com.google.android.apps.walletnfcrel\"" in json)
            assertTrue(json, "\"normalisedName\": \"MARCHE ST DAVID\"" in json)
        }

    @Test
    fun `neither format carries anything out of the raw notification log`() =
        runTest {
            store(merchantName = "MARCHE ST DAVID", amountMinor = 1_609)
            storeRawEvent()

            val exported = write(ExportFormat.CSV) + write(ExportFormat.JSON)

            RAW_LOG_FIELDS.forEach { field ->
                assertFalse("export leaked $field", exported.contains(field, ignoreCase = true))
            }
        }

    @Test
    fun `an empty ledger exports a header and nothing else`() =
        runTest {
            val csv = write(ExportFormat.CSV)

            assertEquals(1, csv.trim().lines().size)
        }

    private suspend fun write(format: ExportFormat): String {
        val bytes = ByteArrayOutputStream()
        export.write(bytes, format, LABELS)
        return bytes.toString(Charsets.UTF_8.name())
    }

    /** A raw row whose every field is a distinctive string, so a leak is unmistakable. */
    private suspend fun storeRawEvent() {
        database.rawNotificationEventDao().insert(
            RawNotificationEventEntity(
                sourcePackage = "com.google.android.apps.walletnfcrel",
                notificationKey = "leaked-notification-key",
                postedAtUtcMillis = OCCURRED_AT.toEpochMilli(),
                deviceLocale = "fr-CA",
                title = "leaked-title",
                text = "leaked-text",
                bigText = "leaked-bigtext",
                subText = "leaked-subtext",
                isGroupSummary = false,
                extrasJson = "{\"leaked\":\"extras\"}",
                parseState = ParseState.PARSED,
                parsedByVersion = 1,
            ),
        )
    }

    private suspend fun storeAccount(
        name: String? = "Débit Desrives",
        type: AccountType = AccountType.CARD,
        issuerLabel: String? = "Carte d'accès Desrives",
        last4: String? = "6262",
    ): Long =
        database.accountDao().insert(
            AccountEntity(
                name = name,
                type = type,
                issuerLabel = issuerLabel,
                last4 = last4,
                origin = AccountOrigin.WALLET,
            ),
        )

    private suspend fun store(
        merchantName: String,
        amountMinor: Long,
        kind: TransactionKind = TransactionKind.EXPENSE,
        accountId: Long? = null,
        toAccountId: Long? = null,
        sourceKey: String = "tag-1",
    ) {
        val merchantId =
            database.merchantDao().insert(
                MerchantEntity(displayName = merchantName, normalisedName = merchantName),
            )
        database.merchantDao().insertAlias(
            MerchantAliasEntity(
                merchantId = merchantId,
                pattern = merchantName,
                matchType = MatchType.EXACT,
                createdBy = AliasOrigin.AUTO,
            ),
        )
        database.transactionDao().insert(
            ExpenseTransactionEntity(
                rawEventId = null,
                amountMinor = amountMinor,
                currency = "CAD",
                occurredAtUtcMillis = OCCURRED_AT.toEpochMilli(),
                zoneId = "America/Toronto",
                localDateEpochDay = 20_705,
                merchantId = merchantId,
                merchantRaw = merchantName,
                categoryId = null,
                source = TransactionSource.NOTIFICATION,
                status = TransactionStatus.CONFIRMED,
                reviewReason = null,
                reviewedAtUtcMillis = null,
                confidence = 1f,
                parserId = "google-wallet",
                parserVersion = 1,
                sourcePackage = "com.google.android.apps.walletnfcrel",
                sourceKey = sourceKey,
                cardLabel = null,
                cardLast4 = "6262",
                note = null,
                kind = kind,
                accountId = accountId,
                toAccountId = toAccountId,
            ),
        )
    }

    private companion object {
        const val BOM = "\uFEFF"

        val RAW_LOG_FIELDS =
            listOf(
                "leaked-notification-key",
                "leaked-title",
                "leaked-text",
                "leaked-bigtext",
                "leaked-subtext",
                "extras",
            )

        val EXPORTED_AT: Instant = Instant.parse("2026-09-09T14:00:00Z")
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
