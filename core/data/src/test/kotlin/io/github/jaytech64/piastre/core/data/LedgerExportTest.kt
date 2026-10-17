// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.core.data

import io.github.jaytech64.piastre.core.database.AccountEntity
import io.github.jaytech64.piastre.core.database.ExpenseTransactionEntity
import io.github.jaytech64.piastre.core.database.MerchantAliasEntity
import io.github.jaytech64.piastre.core.database.MerchantEntity
import io.github.jaytech64.piastre.core.database.PiastreDatabase
import io.github.jaytech64.piastre.core.database.RawNotificationEventEntity
import io.github.jaytech64.piastre.core.database.TransactionPartEntity
import io.github.jaytech64.piastre.core.model.AccountOrigin
import io.github.jaytech64.piastre.core.model.AccountType
import io.github.jaytech64.piastre.core.model.AliasOrigin
import io.github.jaytech64.piastre.core.model.MatchType
import io.github.jaytech64.piastre.core.model.ParseState
import io.github.jaytech64.piastre.core.model.TransactionKind
import io.github.jaytech64.piastre.core.model.TransactionSource
import io.github.jaytech64.piastre.core.model.TransactionStatus
import io.github.jaytech64.piastre.core.testing.inMemoryDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import javax.inject.Provider

/**
 * The export is the one artifact that leaves the app, so it is checked as bytes
 * rather than as objects: the BOM, the delimiter and the decimal comma are the
 * whole reason the CSV format was chosen, and none of them show up in a model.
 */
@RunWith(RobolectricTestRunner::class)
class LedgerExportTest {
    private lateinit var database: PiastreDatabase
    private lateinit var export: LedgerExport

    @Before
    fun setUp() {
        database = inMemoryDatabase()
        export =
            LedgerExport(
                transactions = database.transactionDao(),
                merchants = database.merchantDao(),
                accounts = database.accountDao(),
                categories = database.categoryDao(),
                categoryGroups = database.categoryGroupDao(),
                recurring = database.recurringTransactionDao(),
                templates = database.transactionTemplateDao(),
                parts = database.transactionPartDao(),
                budgets = database.budgetDao(),
                clock = Clock.fixed(EXPORTED_AT, ZoneOffset.UTC),
            )
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `an export is named for the app and the day it was written`() {
        val writer =
            ExportWriter(
                context = RuntimeEnvironment.getApplication(),
                export = export,
                clock = Clock.fixed(EXPORTED_AT, ZoneOffset.UTC),
                zoneId = Provider<ZoneId> { ZoneOffset.UTC },
            )

        assertEquals("piastre-2026-09-09.json", writer.suggestedFileName(ExportFormat.JSON))
    }

    @Test
    fun `the csv opens as french - byte-order mark, semicolons, decimal comma`() =
        runTest {
            store(merchantName = "MARCHE ST DAVID", amountMinor = 1_609)

            val csv = write(ExportFormat.CSV)

            assertTrue("missing UTF-8 BOM", csv.startsWith(BOM))
            assertEquals(
                "date;heure;marchand;montant;devise;source;statut;carte;note;type;compte;" +
                    "compte de destination;catégorie;groupe",
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

            assertTrue(rows.toString(), rows.any { it.endsWith(";EXPENSE;Débit Desrives;;;") })
            assertTrue(rows.toString(), rows.any { it.endsWith(";INCOME;;;;") })
            assertTrue(rows.toString(), rows.any { it.endsWith(";TRANSFER;CASH;Débit Desrives;;") })
        }

    @Test
    fun `the csv names each category's group in a column of its own, after the category`() =
        runTest {
            val categories = database.categoryRepository()
            val power = categories.named("Électricité", database.categoryGroupRepository().named("Maison"))!!
            val loose = categories.named("Logement")!!
            store(merchantName = "HYDRO QUEBEC", amountMinor = 84_10)
            store(merchantName = "PROPRIO", amountMinor = 1_200_00, sourceKey = "rent")
            database.transactionDao().categorise(listOf(1L), power)
            database.transactionDao().categorise(listOf(2L), loose)

            val rows = write(ExportFormat.CSV).lines()

            assertTrue(rows.toString(), rows.any { "HYDRO QUEBEC" in it && it.endsWith(";Électricité;Maison") })
            assertTrue(rows.toString(), rows.any { "PROPRIO" in it && it.endsWith(";Logement;") })
        }

    @Test
    fun `the csv gives a split a line per part, and the amounts still add up to the transaction`() =
        runTest {
            val categories = database.categoryRepository()
            val groceries = categories.named("Épicerie", database.categoryGroupRepository().named("Maison"))!!
            store(merchantName = "COSTCO", amountMinor = 187_42)
            split(1L, Triple(120_00L, groceries, null), Triple(67_42L, null, "Ampoules"))

            val rows = write(ExportFormat.CSV).lines().filter { "COSTCO" in it }

            assertEquals(2, rows.size)
            assertTrue(rows.toString(), rows[0].contains(";120,00;CAD;") && rows[0].endsWith(";Épicerie;Maison"))
            assertTrue(rows.toString(), rows[1].contains(";67,42;CAD;") && rows[1].contains(";Ampoules;"))
            assertTrue(rows.toString(), rows[1].endsWith(";;"))
        }

    @Test
    fun `the json carries a split's parts under the transaction, which keeps its whole amount`() =
        runTest {
            val groceries = database.categoryRepository().named("Épicerie")!!
            store(merchantName = "COSTCO", amountMinor = 187_42)
            split(1L, Triple(120_00L, groceries, null), Triple(67_42L, null, "Ampoules"))

            val json = write(ExportFormat.JSON).filterNot(Char::isWhitespace)

            assertTrue(json, "\"version\":4" in json)
            assertTrue(json, "\"amountMinor\":18742" in json)
            assertTrue(json, "{\"amountMinor\":12000,\"categoryId\":$groceries,\"categoryName\":\"Épicerie\"" in json)
            assertTrue(json, "\"note\":\"Ampoules\"" in json)
        }

    @Test
    fun `the csv gives a part that goes into another account a line that reads as a transfer`() =
        runTest {
            val chequing = storeAccount()
            val mortgage = storeAccount(name = "Hypothèque", type = AccountType.BANK, issuerLabel = null, last4 = null)
            store(merchantName = "DESRIVES", amountMinor = 1_200_00, accountId = chequing)
            split(1L, Triple(700_00L, null, null), intoAccount = 500_00L to mortgage)

            val rows = write(ExportFormat.CSV).lines().filter { "DESRIVES" in it }

            assertEquals(2, rows.size)
            assertTrue(
                rows.toString(),
                rows[0].contains(";700,00;CAD;") && rows[0].contains(";EXPENSE;Débit Desrives;;"),
            )
            assertTrue(
                rows.toString(),
                rows[1].contains(";500,00;CAD;") && rows[1].contains(";TRANSFER;Débit Desrives;Hypothèque;"),
            )
        }

    @Test
    fun `the json says which account a part goes into`() =
        runTest {
            val chequing = storeAccount()
            val mortgage = storeAccount(name = "Hypothèque", type = AccountType.BANK, issuerLabel = null, last4 = null)
            store(merchantName = "DESRIVES", amountMinor = 1_200_00, accountId = chequing)
            split(1L, Triple(700_00L, null, null), intoAccount = 500_00L to mortgage)

            val json = write(ExportFormat.JSON).filterNot(Char::isWhitespace)

            assertTrue(json, "\"toAccountId\":$mortgage,\"toAccountName\":\"Hypothèque\"}" in json)
        }

    /** The names you give your accounts are data about you, so they leave with the rest. */
    @Test
    fun `the json carries your accounts and the account of each transaction`() =
        runTest {
            store(merchantName = "MARCHE ST DAVID", amountMinor = 1_609, accountId = storeAccount())

            val json = write(ExportFormat.JSON)

            assertTrue(json, "\"version\": 4" in json)
            assertTrue(json, "\"name\": \"Débit Desrives\"" in json)
            assertTrue(json, "\"type\": \"CARD\"" in json)
            assertTrue(json, "\"issuerLabel\": \"Carte d'accès Desrives\"" in json)
            assertTrue(json, "\"kind\": \"EXPENSE\"" in json)
            assertTrue(json, "\"accountName\": \"Débit Desrives\"" in json)
        }

    /** Added within version 2, which it left unchanged: older files read as having none. */
    @Test
    fun `the json carries each account's opening balance, and null where none was given`() =
        runTest {
            storeAccount(openingBalanceMinor = -640_25)
            storeAccount(name = null, type = AccountType.CASH, issuerLabel = null, last4 = null)

            val json = write(ExportFormat.JSON)

            assertTrue(json, "\"version\": 4" in json)
            assertTrue(json, "\"openingBalanceMinor\": -64025" in json)
            assertTrue(json, "\"openingBalanceMinor\": null" in json)
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

            assertTrue(json, "\"version\": 4" in json)
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

    private suspend fun split(
        transactionId: Long,
        vararg parts: Triple<Long, Long?, String?>,
        intoAccount: Pair<Long, Long>? = null,
    ) = database.transactionPartDao().replace(
        transactionId,
        parts.map { (amountMinor, categoryId, note) -> part(amountMinor, categoryId, note) } +
            listOfNotNull(intoAccount?.let { (amountMinor, accountId) -> part(amountMinor, toAccountId = accountId) }),
    )

    private fun part(
        amountMinor: Long,
        categoryId: Long? = null,
        note: String? = null,
        toAccountId: Long? = null,
    ) = TransactionPartEntity(
        transactionId = 0,
        position = 0,
        amountMinor = amountMinor,
        categoryId = categoryId,
        note = note,
        toAccountId = toAccountId,
    )

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
        openingBalanceMinor: Long? = null,
    ): Long =
        database.accountDao().insert(
            AccountEntity(
                name = name,
                type = type,
                issuerLabel = issuerLabel,
                last4 = last4,
                origin = AccountOrigin.WALLET,
                openingBalanceMinor = openingBalanceMinor,
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
                category = "catégorie",
                categoryGroup = "groupe",
            )
    }
}
