// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.data

import io.github.jaytech64.depenses.core.database.DepensesDatabase
import io.github.jaytech64.depenses.core.model.AccountOrigin
import io.github.jaytech64.depenses.core.model.AccountType
import io.github.jaytech64.depenses.core.model.Money
import io.github.jaytech64.depenses.core.model.TransactionKind
import io.github.jaytech64.depenses.core.testing.inMemoryDatabase
import io.github.jaytech64.depenses.parsing.GoogleWalletParser
import io.github.jaytech64.depenses.parsing.ParserRegistry
import io.github.jaytech64.depenses.parsing.RawNotification
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.ZoneId
import javax.inject.Provider

/**
 * Cards against a real database, fed by real Wallet notifications where it
 * matters: the account a purchase lands on is decided by the parser's reading of
 * the text, and a hand-built row would prove nothing about that.
 */
@RunWith(RobolectricTestRunner::class)
class AccountRepositoryTest {
    private lateinit var database: DepensesDatabase
    private lateinit var accounts: AccountRepository
    private lateinit var capture: NotificationCapture
    private lateinit var transactions: TransactionRepository

    @Before
    fun open() {
        database = inMemoryDatabase()
        accounts = AccountRepository(database.accountDao())
        val merchants = MerchantRepository(database.merchantDao())
        capture =
            NotificationCapture(
                RawEventRepository(database.rawNotificationEventDao()),
                ParsePipeline(
                    events = database.rawNotificationEventDao(),
                    transactions = database.transactionDao(),
                    registry = ParserRegistry(listOf(GoogleWalletParser())),
                    merchants = merchants,
                    accounts = accounts,
                    zoneId = Provider { ZONE },
                ),
            )
        transactions =
            TransactionRepository(
                transactions = database.transactionDao(),
                reports = database.reportDao(),
                merchants = merchants,
                zoneId = Provider { ZONE },
            )
    }

    @After
    fun close() = database.close()

    @Test
    fun `two purchases on one account land on one account`() =
        runTest {
            captureCoffee(key = "tag-1", postedAt = POSTED_AT)
            captureCoffee(key = "tag-2", postedAt = POSTED_AT + HOUR)

            val purchases = transactions.confirmed().first()
            val account = accounts.all().first().single()

            assertEquals(setOf(account.id), purchases.map { it.accountId }.toSet())
            assertEquals("Carte d'accès Desrives", account.issuerLabel)
            assertEquals("0000", account.last4)
            assertEquals(AccountOrigin.WALLET, account.origin)
            assertEquals(2, account.transactionCount)
        }

    @Test
    fun `the same digits from another issuer are another account`() =
        runTest {
            val desrives = accounts.resolve("Carte d'accès Desrives", "0000")
            val visa = accounts.resolve("Récompenses Visa", "0000")

            assertNotEquals(desrives, visa)
            assertEquals(desrives, accounts.resolve("Carte d'accès Desrives", "0000"))
        }

    @Test
    fun `a notification without digits is not pinned to any account`() =
        runTest {
            assertNull(accounts.resolve("Carte d'accès Desrives", null))
            assertTrue(accounts.all().first().isEmpty())
        }

    /** An account you typed in must never be claimed by capture, even with matching digits. */
    @Test
    fun `an account you added is never claimed by a notification`() =
        runTest {
            val added = accounts.add(name = "Amex papier", type = AccountType.CARD, last4 = "0000")

            val resolved = accounts.resolve(issuerLabel = null, last4 = "0000")

            assertNotEquals(added, resolved)
        }

    @Test
    fun `naming an account shows on every purchase made with it`() =
        runTest {
            captureCoffee(key = "tag-1", postedAt = POSTED_AT)
            val account = accounts.all().first().single()

            accounts.rename(account.id, "  Débit Desrives ")

            assertEquals(
                "Débit Desrives",
                transactions
                    .confirmed()
                    .first()
                    .single()
                    .accountName,
            )
        }

    @Test
    fun `a blank name clears it`() =
        runTest {
            val id = accounts.add(name = "Amex papier", type = AccountType.CARD, last4 = "8484")

            accounts.rename(id, "   ")

            assertNull(
                accounts
                    .all()
                    .first()
                    .single()
                    .name,
            )
        }

    @Test
    fun `an account in use stays, an unused one goes`() =
        runTest {
            captureCoffee(key = "tag-1", postedAt = POSTED_AT)
            val used =
                accounts
                    .all()
                    .first()
                    .single()
                    .id
            val unused = accounts.add(name = "Amex papier", type = AccountType.CARD, last4 = "8484")

            assertFalse(accounts.remove(used))
            assertTrue(accounts.remove(unused))
            assertEquals(listOf(used), accounts.all().first().map { it.id })
        }

    @Test
    fun `a manual entry on an account reads back with its name and digits`() =
        runTest {
            val id = accounts.add(name = "Amex papier", type = AccountType.CARD, last4 = "8484")
            transactions.addManual(
                amount = Money(1_250, Money.CAD),
                merchantName = "Dépanneur du coin",
                occurredAt = Instant.ofEpochMilli(POSTED_AT),
                note = null,
                kind = TransactionKind.EXPENSE,
                accountId = id,
                toAccountId = null,
            )
            val manual = transactions.confirmed().first().single()

            val read = transactions.byId(manual.id)

            assertEquals("Amex papier", read?.accountName)
            assertEquals("8484", read?.cardLast4)
        }

    private suspend fun captureCoffee(
        key: String,
        postedAt: Long,
    ) = capture.capture(
        RawNotification(
            sourcePackage = WALLET,
            notificationKey = "0|$WALLET|1001|$key|10348",
            postedAtUtcMillis = postedAt,
            deviceLocale = "fr-CA",
            title = "SQ *CAFE OLIMPICO 12",
            text = "6,25 $ avec Carte d'accès Desrives ••0000",
        ),
        extrasJson = "{}",
    )

    private companion object {
        val ZONE: ZoneId = ZoneId.of("America/Toronto")
        const val WALLET = "com.google.android.apps.walletnfcrel"
        const val POSTED_AT = 1_788_740_166_564L
        const val HOUR = 3_600_000L
    }
}
