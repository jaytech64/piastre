// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.data

import io.github.jaytech64.depenses.core.database.DepensesDatabase
import io.github.jaytech64.depenses.core.model.CardOrigin
import io.github.jaytech64.depenses.core.model.Money
import io.github.jaytech64.depenses.core.model.Payment
import io.github.jaytech64.depenses.core.model.PaymentMethod
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
 * matters: the card a purchase lands on is decided by the parser's reading of
 * the text, and a hand-built row would prove nothing about that.
 */
@RunWith(RobolectricTestRunner::class)
class CardRepositoryTest {
    private lateinit var database: DepensesDatabase
    private lateinit var cards: CardRepository
    private lateinit var capture: NotificationCapture
    private lateinit var transactions: TransactionRepository

    @Before
    fun open() {
        database = inMemoryDatabase()
        cards = CardRepository(database.cardDao())
        val merchants = MerchantRepository(database.merchantDao())
        capture =
            NotificationCapture(
                RawEventRepository(database.rawNotificationEventDao()),
                ParsePipeline(
                    events = database.rawNotificationEventDao(),
                    transactions = database.transactionDao(),
                    registry = ParserRegistry(listOf(GoogleWalletParser())),
                    merchants = merchants,
                    cards = cards,
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
    fun `two purchases on one card land on one card`() =
        runTest {
            captureCoffee(key = "tag-1", postedAt = POSTED_AT)
            captureCoffee(key = "tag-2", postedAt = POSTED_AT + HOUR)

            val purchases = transactions.confirmed().first()
            val card = cards.all().first().single()

            assertEquals(setOf(card.id), purchases.map { it.cardId }.toSet())
            assertEquals(setOf(PaymentMethod.CARD), purchases.map { it.paymentMethod }.toSet())
            assertEquals("Carte d'accès Desrives", card.issuerLabel)
            assertEquals("0000", card.last4)
            assertEquals(CardOrigin.WALLET, card.origin)
            assertEquals(2, card.purchaseCount)
        }

    @Test
    fun `the same digits from another issuer are another card`() =
        runTest {
            val desrives = cards.resolve("Carte d'accès Desrives", "0000")
            val visa = cards.resolve("Récompenses Visa", "0000")

            assertNotEquals(desrives, visa)
            assertEquals(desrives, cards.resolve("Carte d'accès Desrives", "0000"))
        }

    @Test
    fun `a notification without digits is not pinned to any card`() =
        runTest {
            assertNull(cards.resolve("Carte d'accès Desrives", null))
            assertTrue(cards.all().first().isEmpty())
        }

    /** A card you typed in must never be claimed by capture, even with matching digits. */
    @Test
    fun `a card you added is never claimed by a notification`() =
        runTest {
            val added = cards.add(name = "Amex papier", last4 = "0000")

            val resolved = cards.resolve(issuerLabel = null, last4 = "0000")

            assertNotEquals(added, resolved)
        }

    @Test
    fun `naming a card shows on every purchase made with it`() =
        runTest {
            captureCoffee(key = "tag-1", postedAt = POSTED_AT)
            val card = cards.all().first().single()

            cards.rename(card.id, "  Débit Desrives ")

            assertEquals(
                "Débit Desrives",
                transactions
                    .confirmed()
                    .first()
                    .single()
                    .cardName,
            )
        }

    @Test
    fun `a blank name clears it`() =
        runTest {
            val id = cards.add(name = "Amex papier", last4 = "8484")

            cards.rename(id, "   ")

            assertNull(
                cards
                    .all()
                    .first()
                    .single()
                    .name,
            )
        }

    @Test
    fun `a card in use stays, an unused one goes`() =
        runTest {
            captureCoffee(key = "tag-1", postedAt = POSTED_AT)
            val used =
                cards
                    .all()
                    .first()
                    .single()
                    .id
            val unused = cards.add(name = "Amex papier", last4 = "8484")

            assertFalse(cards.remove(used))
            assertTrue(cards.remove(unused))
            assertEquals(listOf(used), cards.all().first().map { it.id })
        }

    @Test
    fun `a manual entry paid by card reads back with the card's name and digits`() =
        runTest {
            val id = cards.add(name = "Amex papier", last4 = "8484")
            transactions.addManual(
                amount = Money(1_250, Money.CAD),
                merchantName = "Dépanneur du coin",
                occurredAt = Instant.ofEpochMilli(POSTED_AT),
                note = null,
                kind = TransactionKind.EXPENSE,
                payment = Payment.ByCard(id),
            )
            val manual = transactions.confirmed().first().single()

            val read = transactions.byId(manual.id)

            assertEquals("Amex papier", read?.cardName)
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
